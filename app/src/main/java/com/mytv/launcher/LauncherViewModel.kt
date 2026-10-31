@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.mytv.launcher

import android.app.Application
import coil.imageLoader
import coil.request.ImageRequest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mytv.launcher.ang.AngGuide
import com.mytv.launcher.ang.AngGuideBuilder
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.AngRepository
import com.mytv.launcher.ang.WhereToWatch
import com.mytv.launcher.apps.InstalledApps
import com.mytv.launcher.apps.Search
import com.mytv.launcher.apps.TvApp
import com.mytv.launcher.sports.Broadcasters
import com.mytv.launcher.sports.EspnClient
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.InstalledApp
import com.mytv.launcher.sports.Standings
import com.mytv.launcher.sports.Team
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.sports.WatchOption
import com.mytv.launcher.watching.DeviceCode
import com.mytv.launcher.watching.PollResult
import com.mytv.launcher.topten.TopTenRepository
import com.mytv.launcher.topten.TopTenService
import com.mytv.launcher.topten.TopTitle
import com.mytv.launcher.update.Updater
import com.mytv.launcher.watching.TraktClient
import com.mytv.launcher.watching.WatchNext
import com.mytv.launcher.watching.WatchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class TeamPage(val teamKey: String) : Screen
    /** Every NBA game of the day with live scores; Play opens it on Prime Video (League Pass). */
    data object Nba : Screen
    /** Fantrax's sign-in page (once), so the fantasy panel can show your league's live scoring. */
    data object FantraxSignIn : Screen
    data object EditApps : Screen
    data object TraktLink : Screen
    data object Ang : Screen
    data object Watchlist : Screen
    /** Her List: everything she rated, inside Ang's TV. */
    data object AngList : Screen
    /** MyTV settings: home screen + undo, screensaver, alerts, updates, Send to TV. */
    data object SystemSetup : Screen
}

data class AngState(
    val guide: AngGuide = AngGuide(),
    /** Her shows from the TV's Continue Watching list, to resume where she left off. */
    val continueWatching: List<WatchItem> = emptyList(),
    val loading: Boolean = true,
    /** Still fetching suggestions. */
    val discovering: Boolean = false,
    /** key → what she said about it (for the hold-select menu). */
    val ratings: Map<String, com.mytv.launcher.ang.Rating> = emptyMap(),
    /** Titles she marked "Watching now" (shown in her Continue watching). */
    val watchingNow: List<AngItem> = emptyList(),
    /** Everything she has rated (for Her List). */
    val rated: List<com.mytv.launcher.ang.Rated> = emptyList(),
    /** Her shows with a new episode out today. */
    val tonight: List<AngItem> = emptyList(),
) {
    /** What she saved with "Watch later", newest first. */
    val watchLater: List<AngItem> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        rated.filter { it.rating == com.mytv.launcher.ang.Rating.WANT }.sortedByDescending { it.date }.map { it.toItem() }
    }
}

/** Your ratings and lists. */
data class MyState(
    val guide: com.mytv.launcher.mine.MyGuide = com.mytv.launcher.mine.MyGuide(),
    /** key → what you said about it (Popular row badges, the hold-select menu). */
    val ratings: Map<String, com.mytv.launcher.ang.Rating> = emptyMap(),
    val loading: Boolean = true,
) {
    /** "Shows for you" shows on the home screen once you've rated a few titles. */
    val showForYouRow: Boolean
        get() = (guide.newShows.isNotEmpty() || guide.missed.isNotEmpty() || guide.newMovies.isNotEmpty() || guide.missedMovies.isNotEmpty()) &&
            ratings.values.count { it != com.mytv.launcher.ang.Rating.WONT_WATCH } >= com.mytv.launcher.mine.MyGuide.MIN_RATINGS_FOR_HOME_ROW
}

data class WatchState(
    /** Why Continue Watching shows what it shows (on the apps screen). */
    val status: String = "",
    /** MyTV noting what plays in each app (needs Notification access). */
    val tracking: Boolean = false,
    val trackStatus: String = "",
    val items: List<WatchItem> = emptyList(),
    val loading: Boolean = true,
    /** Watch Next needs the TV listings permission and we don't have it yet. */
    val needsPermission: Boolean = false,
    /** Trakt is optional: offered only when this build has Trakt API keys. */
    val traktConfigured: Boolean = false,
    val traktLinked: Boolean = false,
)

data class TeamDetail(
    val live: Game? = null,
    val liveOptions: List<WatchOption> = emptyList(),
    val upcoming: List<Game> = emptyList(),
    val recent: List<Game> = emptyList(),
    val standings: Standings? = null,
    val loading: Boolean = true,
)

data class NbaState(
    /** 0 = today, -1 = yesterday, 1 = tomorrow. */
    val dayOffset: Int = 0,
    val games: List<Game> = emptyList(),
    val loading: Boolean = true,
    /** When the scores were last fetched. */
    val updatedAt: Long = 0L,
)

/** What the NBA button on the home screen shows. */
data class NbaSummary(val live: Int = 0, val today: Int = 0)

sealed interface LinkState {
    data object Starting : LinkState
    data class ShowCode(val code: DeviceCode) : LinkState
    data object Linked : LinkState
    data class Failed(val message: String) : LinkState
}

/** Continue Watching cards that came from "Watching now" (hold select to change them). */
const val MANUAL_PREFIX = "manual:"
/** Continue Watching cards for shows MyTV saw playing. */
const val NOTED_PREFIX = "noted:"

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val installed = InstalledApps(app)
    private val espn = EspnClient()
    private val leaguePass = com.mytv.launcher.sports.LeaguePassClient()
    private val trakt = TraktClient(app)
    private val watchNext = WatchNext(app)
    private val updater = Updater(app)
    private val angRepo = AngRepository(app)

    init {
        com.mytv.launcher.ratings.Ratings.init(app)
        com.mytv.launcher.ratings.DeepLinks.init(app)
        // Something new played (or its picture arrived): update Continue Watching.
        // While you watch, the tracker updates every 30s: only refresh when MyTV is on screen
        // (otherwise just once when you come back), and batch bursts of changes.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.mytv.launcher.watching.PlayHistory.entries(app) }
                .drop(1)
                .debounce(3_000)
                .collect { if (inForeground) refreshWatching() else watchingDirty = true }
        }
    }
    private val taste = com.mytv.launcher.ang.TasteStore(app)
    private val angTaste = com.mytv.launcher.ang.TasteRepository(
        app, strict = true,
        // Same as yours: "Already watched" counts too, a like / watching counts double.
        positive = mapOf(
            com.mytv.launcher.ang.Rating.LIKE to 2,
            com.mytv.launcher.ang.Rating.WATCHING to 2,
            com.mytv.launcher.ang.Rating.WATCHED to 1,
            com.mytv.launcher.ang.Rating.WANT to 1,
        ),
        follow = setOf(
            com.mytv.launcher.ang.Rating.LIKE,
            com.mytv.launcher.ang.Rating.WATCHING,
            com.mytv.launcher.ang.Rating.WATCHED,
            com.mytv.launcher.ang.Rating.WANT,
        ),
    )

    // ---- Your own taste (Popular row, Shows for you, My Watchlist) ----
    private val myStore = com.mytv.launcher.ang.TasteStore(app, "taste_me")
    private val myTaste = com.mytv.launcher.ang.TasteRepository(
        app, strict = false,
        // "Already watched" is your main signal; a like counts double.
        positive = mapOf(
            com.mytv.launcher.ang.Rating.LIKE to 2,
            com.mytv.launcher.ang.Rating.WATCHING to 2,
            com.mytv.launcher.ang.Rating.WATCHED to 1,
            com.mytv.launcher.ang.Rating.WANT to 1,
        ),
        follow = setOf(
            com.mytv.launcher.ang.Rating.LIKE,
            com.mytv.launcher.ang.Rating.WATCHING,
            com.mytv.launcher.ang.Rating.WATCHED,
            com.mytv.launcher.ang.Rating.WANT,
        ),
        // A big pool: "more like this" from your 45 best-rated shows, up to 150 candidates.
        maxCandidates = 150,
        maxSeeds = 45,
    )
    private val history = com.mytv.launcher.mine.HistoryImport(app)
    private var myFresh: Pair<String, List<AngItem>>? = null
    private var myFreshAt = 0L
    private var myFreshMovies: Pair<String, List<AngItem>>? = null
    private var myFreshMoviesAt = 0L
    private val _my = MutableStateFlow(MyState())
    val my = _my.asStateFlow()
    private var myJob: Job? = null
    private var myUpcoming: Pair<String, List<AngItem>>? = null
    private var myUpcomingAt = 0L
    private val topTenRepo = TopTenRepository()

    /** Top 10 per streaming service, shown above the apps row. */
    private val _topTen = MutableStateFlow<Map<TopTenService, List<TopTitle>>>(emptyMap())
    val topTen = _topTen.asStateFlow()
    val topTenConfigured: Boolean get() = topTenRepo.isConfigured

    /** Bottom row: the Top 10s of the services you have installed, merged into one ranking. */
    // Lazy: it reads _allApps / _my, which are declared further down.
    val popular: StateFlow<List<Pair<TopTenService, TopTitle>>> by lazy {
        kotlinx.coroutines.flow.combine(_topTen, _allApps, _my) { lists, apps, mine ->
            val installedServices = TopTenService.entries.filter { s -> apps.any { s.app.matches(it.packageName, it.label) } }.toSet()
            com.mytv.launcher.topten.TmdbParser.acrossServices(lists.filterKeys { it in installedServices }, limit = 30)
                // Hide what you've watched, disliked or won't watch.
                .filter { (_, t) -> mine.ratings[popularItem(TopTenService.NETFLIX, t).key]?.hides != true }
                .take(20)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }

    /** A Popular-row title as a ratable item. */
    fun popularItem(service: TopTenService, t: TopTitle) = AngItem(
        key = com.mytv.launcher.ang.AngDiscovery.key(t.id, t.isMovie),
        title = t.title,
        detail = service.displayName,
        imageUrl = t.posterUrl,
        service = service.displayName,
        date = t.released,
        badge = null,
        popularity = t.popularity,
        tmdbId = t.id,
        isMovie = t.isMovie,
        voteAverage = t.voteAverage,
        backdropUrl = t.backdropUrl,
    )

    private var topTenLoadedAt = 0L

    private val _ang = MutableStateFlow(AngState())
    val ang = _ang.asStateFlow()
    private var angJob: Job? = null

    /** A newer version is downloaded and needs one click to install. */
    private val _updateReady = MutableStateFlow(false)
    val updateReady = _updateReady.asStateFlow()
    private var updateJob: Job? = null

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen = _screen.asStateFlow()

    private val _allApps = MutableStateFlow<List<TvApp>>(emptyList())
    val allApps = _allApps.asStateFlow()

    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps = _apps.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden = _hidden.asStateFlow()

    private val _watching = MutableStateFlow(WatchState())
    val watching = _watching.asStateFlow()

    /** Team key -> the game on right now (drives the LIVE badge on the home screen). */
    /** Team key -> the game on now and the next one (for the team buttons). */
    private val _teams = MutableStateFlow<Map<String, com.mytv.launcher.sports.TeamInfo>>(emptyMap())
    val teams = _teams.asStateFlow()

    /** Game-day colours for the whole app (Arsenal red, Lakers yellow at home / purple away). */
    private val _gameDay = MutableStateFlow(com.mytv.launcher.sports.GameDayTheme.NONE)
    val gameDay = _gameDay.asStateFlow()

    private var schedules: Map<String, List<Game>> = emptyMap()
    private var schedulesAt = 0L

    private val _teamDetail = MutableStateFlow(TeamDetail())
    val teamDetail = _teamDetail.asStateFlow()

    private val _nba = MutableStateFlow(NbaState())
    val nba = _nba.asStateFlow()
    private val _nbaSummary = MutableStateFlow(NbaSummary())
    val nbaSummary = _nbaSummary.asStateFlow()
    private var nbaJob: Job? = null

    /** The fantasy matchup, for the home screen's Fantasy tile. */
    private val _fantasy = MutableStateFlow<com.mytv.launcher.fantasy.FantasyNow?>(null)
    val fantasy = _fantasy.asStateFlow()

    /** Short "Finding the Arsenal game..." message shown over the home screen. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Starting)
    val link = _link.asStateFlow()

    private var liveJob: Job? = null
    private var linkJob: Job? = null

    fun navigate(to: Screen) {
        _screen.value = to
        when (to) {
            is Screen.TeamPage -> Teams.byKey(to.teamKey)?.let(::loadTeam)
            Screen.Nba -> loadNba(0)
            Screen.TraktLink -> startTraktLink()
            Screen.Ang -> loadAng()
            Screen.Watchlist -> refreshMine()
            Screen.SystemSetup -> { refreshSystem(); checkFantraxSignIn() }
            Screen.FantraxSignIn -> watchFantraxSignIn()
            else -> Unit
        }
    }

    fun back() {
        linkJob?.cancel()
        // Her List lives inside Ang's TV: Back returns there. Fantrax's sign-in returns to settings.
        when (_screen.value) {
            Screen.AngList -> _screen.value = Screen.Ang
            Screen.FantraxSignIn -> navigate(Screen.SystemSetup)
            else -> _screen.value = Screen.Home
        }
    }

    /** The remote's Home button: always the home screen. */
    fun goHome() {
        linkJob?.cancel()
        _screen.value = Screen.Home
    }

    /** Opens Her List (keeps Ang's TV loaded underneath). */
    fun openAngList() {
        _screen.value = Screen.AngList
        viewModelScope.launch { publishAng() }
    }

    @Volatile private var watchingDirty = true
    private var deferredJob: Job? = null
    private var deferredAt = 0L
    private var angWarmAt = 0L
    private var teamsAt = 0L

    /**
     * Called every time the launcher comes to the front (every Home press).
     * What's on screen refreshes now; the heavy background work waits a few
     * seconds, so moving around feels instant, and runs at most every 10 min.
     */
    fun onResume() {
        inForeground = true
        profileOnResume()
        refreshApps()
        refreshWatching() // one quick local query: what you just watched shows up right away
        checkForUpdates()
        if (liveJob?.isActive != true) liveJob = viewModelScope.launch {
            while (isActive) {
                if (System.currentTimeMillis() - teamsAt > 90_000L) {
                    refreshTeams()
                    teamsAt = System.currentTimeMillis()
                }
                delay(2 * 60_000L)
            }
        }
        if (deferredJob?.isActive == true) return
        val stale = System.currentTimeMillis() - deferredAt > 10 * 60_000L
        deferredJob = viewModelScope.launch {
            delay(if (deferredAt == 0L) 1_500 else 4_000)
            refreshTopTen()
            refreshWeather()
            if (stale) {
                deferredAt = System.currentTimeMillis()
                refreshMine()
                syncRatings()
            } else if (System.currentTimeMillis() - syncedAt > 2 * 60_000L) {
                // Light: picks up what you sent from your phone, or rated on the other TV.
                syncRatings()
            }
            warmAng()
        }
    }

    /** Warms Ang's TV in the background so it opens instantly (everything is cached). Every 30 min at most. */
    private fun warmAng() {
        if (angJob?.isActive == true || System.currentTimeMillis() - angWarmAt < 30 * 60_000L) return
        angWarmAt = System.currentTimeMillis()
        angJob = viewModelScope.launch(Dispatchers.Default) {
            _allApps.first { it.isNotEmpty() } // need the installed apps to know which services to search
            val providers = installedProviders()
            angRepo.curatedShows()
            angRepo.candidates(providers)
            val ratings = taste.all()
            angTaste.recommendations(ratings, providers)
            angTaste.followed(ratings)
        }
    }

    /**
     * Scores every 2 minutes; schedules every 10 minutes on a game day, else
     * every 30. Drives the team buttons and the game-day background.
     */
    private suspend fun refreshTeams() = coroutineScope {
        val now = System.currentTimeMillis()
        val gameDayNow = _teams.value.values.any { it.today.isNotEmpty() }
        val live = async {
            Teams.all.map { team -> async { team.key to espn.findLiveGame(team) } }.awaitAll()
                .mapNotNull { (k, g) -> g?.let { k to it } }.toMap()
        }
        if (now - schedulesAt > (if (gameDayNow) 10 else 30) * 60_000L) {
            val fetched = Teams.all.map { team -> async { team.key to espn.schedule(team) } }.awaitAll().toMap()
            // Keep the last good schedule if a fetch failed.
            schedules = Teams.all.associate { t -> t.key to (fetched[t.key].takeIf { !it.isNullOrEmpty() } ?: schedules[t.key].orEmpty()) }
            if (fetched.values.any { it.isNotEmpty() }) schedulesAt = now
        }
        val nbaToday = async { nbaGames(0) }
        val fantasyNow = async { com.mytv.launcher.fantasy.FantasyPanel.fetch() }
        val found = live.await()
        val zone = java.time.ZoneId.systemDefault()
        val info = Teams.all.associate { team ->
            val onNow = found[team.key]
            // The live scoreboard is fresher than the schedule.
            val all = schedules[team.key].orEmpty().map { g -> onNow?.takeIf { it.id == g.id } ?: g }
                .let { list -> if (onNow != null && list.none { it.id == onNow.id }) (list + onNow).sortedBy { it.startMillis } else list }
            team.key to com.mytv.launcher.sports.TeamInfo(
                live = onNow?.takeIf { it.state == com.mytv.launcher.sports.GameState.LIVE },
                next = com.mytv.launcher.sports.GameDay.next(all, now),
                today = com.mytv.launcher.sports.GameDay.today(all, now, zone),
            )
        }
        _teams.value = info
        _fantasy.value = fantasyNow.await()
        nbaToday.await()?.let { games ->
            _nbaSummary.value = NbaSummary(live = games.count { it.state == com.mytv.launcher.sports.GameState.LIVE }, today = games.size)
        }
        _gameDay.value = com.mytv.launcher.sports.GameDay.theme(info, now)
    }

    fun onPause() {}

    /** MyTV went to the background: a good moment to update without interrupting anything. */
    fun onStop() {
        inForeground = false
        profileOnStop()
        // Scores aren't needed while you're in another app; picked up again on the next Home press.
        liveJob?.cancel()
        installSilentlyIfReady()
    }

    private var inForeground = false

    private fun installSilentlyIfReady() {
        // Only while the TV is off (screen off), never in the middle of watching something.
        val screenOn = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        if (inForeground || screenOn || !updater.canInstallSilently()) return
        updater.pendingApk()?.let(updater::install)
    }

    // ---- Self-update ----

    /**
     * Checks now (throttled to every 30 min inside [Updater]) and then every
     * few hours for as long as the process lives, even in the background.
     */
    private fun checkForUpdates() {
        if (updateJob?.isActive == true) return // already checking every few hours
        updateJob = viewModelScope.launch {
            while (isActive) {
                val apk = updater.checkAndDownload()
                _updateReady.value = apk != null && !updater.canInstallSilently()
                installSilentlyIfReady()
                delay(3 * 3600_000L)
            }
        }
    }

    /** Shown next to "Check for updates" on the apps screen. */
    private val _updateStatus = MutableStateFlow("")
    val updateStatus = _updateStatus.asStateFlow()

    fun checkForUpdatesNow() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _updateStatus.value = "Checking… (downloading can take a minute)"
            val apk = updater.checkAndDownload(minIntervalMillis = 0)
            when {
                apk != null -> {
                    _updateStatus.value = "Update downloaded"
                    _updateReady.value = true
                    installUpdate()
                }
                updater.lastSeenVersion == 0 -> _updateStatus.value = "Couldn't reach GitHub. Check the internet connection."
                updater.lastSeenVersion > BuildConfig.VERSION_CODE ->
                    _updateStatus.value = "Version 1.0.${updater.lastSeenVersion} is out but the download failed. Try again."
                else -> _updateStatus.value = "Up to date"
            }
        }
    }

    fun installUpdate() {
        val apk = updater.pendingApk() ?: run { _updateReady.value = false; return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            toast("Turn on MyTV here, then press Back and select Update again")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        if (!updater.install(apk)) toast("Update failed. It will retry later.")
    }

    val versionName: String get() = BuildConfig.VERSION_NAME

    // ---- Apps ----

    /** Reads installed apps and draws their banners on a background thread (never blocks the UI). */
    fun refreshApps() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.Default) { installed.load() }
            _allApps.value = all
            _hidden.value = installed.hiddenPackages()
            _apps.value = installed.visible(all)
        }
    }

    fun launchApp(app: TvApp) {
        if (!installed.launch(app.packageName)) toast("Couldn't open ${app.label}")
    }

    fun moveApp(app: TvApp, delta: Int) {
        installed.move(_apps.value, app.packageName, delta)
        refreshApps()
        pushLayout()
    }

    fun setHidden(app: TvApp, hidden: Boolean) {
        installed.setHidden(app.packageName, hidden)
        refreshApps()
        pushLayout()
    }

    private var layoutJob: Job? = null

    /** Sends the apps row to the other TVs (after a short pause, so a run of moves sends once). */
    private fun pushLayout() {
        layoutJob?.cancel()
        layoutJob = viewModelScope.launch {
            delay(1500)
            com.mytv.launcher.sync.CloudSync.pushLayout(installed)
        }
    }

    // ---- Teams ----

    /**
     * The big button: if the team is playing, open the game in whichever
     * installed app carries it. Otherwise show the team page.
     */
    fun onTeamPressed(team: Team) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Finding the ${team.name} game…"
            val game = espn.findLiveGame(team)
            _busy.value = null
            if (game == null) {
                navigate(Screen.TeamPage(team.key))
                return@launch
            }
            watchGame(team, game, watchOptions(game).firstOrNull())
        }
    }

    /**
     * Straight to the game, never a search:
     * 1. the game itself, if the app showing it lists it on the TV (its live link starts it);
     * 2. otherwise that app, opened on its own;
     * 3. no app of yours has it: the team page says where it's on.
     */
    fun watchGame(team: Team, game: Game, option: WatchOption?) {
        val opponent = game.opponentOf(team.espnId)
        viewModelScope.launch {
            val opponentWord = opponent.name.split(' ').lastOrNull().orEmpty()
            if (!openGame(game, "${team.name} vs ${opponent.name}", listOf(team.name, opponentWord), option)) navigate(Screen.TeamPage(team.key))
        }
    }

    /**
     * Opens [game] in the app carrying it; false (after saying where it's on)
     * if none of your apps has it. [words] find its listing on the TV.
     */
    private suspend fun openGame(game: Game, title: String, words: List<String>, option: WatchOption? = null): Boolean {
        val options = (listOfNotNull(option) + watchOptions(game)).distinctBy { it.app.packageName }
        // NBA on League Pass: straight to that game's page in Prime Video.
        options.firstOrNull()?.takeIf { com.mytv.launcher.sports.Service.PRIME_VIDEO.matches(it.app.packageName, it.app.label) }?.let { prime ->
            if (openOnLeaguePass(game, title, prime.app.packageName)) return true
        }
        val link = watchNext.findGame(words, options.map { it.app.packageName })
        if (link != null && watchNext.openLink(link.first, link.second)) {
            val app = options.firstOrNull { it.app.packageName == link.first }?.app?.label.orEmpty()
            toast("$title · starting on $app")
            return true
        }
        val first = options.firstOrNull()
        // YouTube TV: straight onto the channel showing it (it starts playing), else its Live guide.
        if (first != null && com.mytv.launcher.sports.Service.YOUTUBE_TV.matches(first.app.packageName, first.app.label)) {
            val channel = com.mytv.launcher.sports.YouTubeTvChannels.linkFor(first.network)
            if (channel != null && watchNext.openLink(first.app.packageName, channel)) {
                toast("$title · ${first.network} on YouTube TV")
                return true
            }
            if (watchNext.openLink(first.app.packageName, "https://tv.youtube.com/live")) {
                toast("$title is on ${first.network} · YouTube TV Live")
                return true
            }
        }
        if (first != null && installed.launch(first.app.packageName)) {
            toast("$title is on ${first.network} · opening ${first.app.label}")
            return true
        }
        val where = (game.broadcasts.ifEmpty { game.league.defaultNetworks }).joinToString(", ")
        toast("$title is on $where, which isn't one of your apps")
        return false
    }

    /** A fantasy player's ▶: today's game of their NBA team ([abbr]), wherever it's on. */
    fun watchNbaTeam(abbr: String) {
        viewModelScope.launch {
            _allApps.first { it.isNotEmpty() } // need the apps to know which one shows the game
            _busy.value = "Finding the game…"
            val game = nbaGames(0)?.let { com.mytv.launcher.sports.NbaGames.gameFor(it, abbr) }
            _busy.value = null
            if (game == null) {
                toast("Couldn't find today's $abbr game")
                navigate(Screen.Nba)
                return@launch
            }
            val title = com.mytv.launcher.sports.NbaGames.title(game)
            if (!openGame(game, title, com.mytv.launcher.sports.NbaGames.matchWords(game))) navigate(Screen.Nba)
        }
    }

    /** The Fantasy tile, or "Show it now" in settings: slides the fantasy panel in (or out). */
    fun toggleFantasy() { com.mytv.launcher.fantasy.FantasyPanel.toggle(context) }

    /** MyTV settings → Sign in to Fantrax: Fantrax's sign-in page, once. */
    fun signInToFantrax() = navigate(Screen.FantraxSignIn)

    /** A page loaded in the sign-in screen: once Fantrax shows the league's scores, we're done. */
    /**
     * Fantrax's site changes pages without reloading, so page loads alone miss
     * the moment you're signed in: check every few seconds while its sign-in
     * screen is open, and go back to settings once Fantrax lets us in.
     */
    private fun watchFantraxSignIn() {
        viewModelScope.launch {
            while (_screen.value == Screen.FantraxSignIn) {
                delay(3_000L)
                if (_screen.value == Screen.FantraxSignIn && com.mytv.launcher.fantasy.FantasyPanel.signedIn()) {
                    android.webkit.CookieManager.getInstance().flush()
                    settings.fantraxSignedIn = true
                    toast("Signed in to Fantrax. Press your fantasy button in any app.")
                    navigate(Screen.SystemSetup)
                }
            }
        }
    }

    /** Settings shows whether Fantrax still lets this TV in (it may have signed in, or out, elsewhere). */
    private fun checkFantraxSignIn() {
        viewModelScope.launch {
            val now = com.mytv.launcher.fantasy.FantasyPanel.signedIn()
            if (now) android.webkit.CookieManager.getInstance().flush()
            if (now != settings.fantraxSignedIn) {
                settings.fantraxSignedIn = now
                refreshSystem()
            }
        }
    }

    fun fantraxPageLoaded(url: String) {
        if (url.contains("/login")) return
        viewModelScope.launch {
            if (_screen.value != Screen.FantraxSignIn) return@launch
            if (com.mytv.launcher.fantasy.FantasyPanel.signedIn()) {
                android.webkit.CookieManager.getInstance().flush()
                settings.fantraxSignedIn = true
                toast("Signed in to Fantrax. Press your fantasy button in any app.")
                navigate(Screen.SystemSetup)
            }
        }
    }

    /** MyTV settings: the next button pressed on the remote becomes the fantasy button. */
    fun chooseFantasyButton() {
        val helper = com.mytv.launcher.system.HomeOverride.isRunning
        if (!helper) {
            _system.value = systemNow(message = "The fantasy button needs MyTV's home helper: choose \"Make MyTV my home screen\" first.")
            return
        }
        com.mytv.launcher.system.FantasyButton.onChosen = { code ->
            viewModelScope.launch {
                _system.value = systemNow(message = "Fantasy button: ${com.mytv.launcher.system.FantasyButton.label(code)}. Press it in any app.")
            }
        }
        com.mytv.launcher.system.FantasyButton.choosing = true
        _system.value = systemNow(message = "Press the remote button you want for the fantasy panel (not OK, Back or the arrows)…")
    }

    private fun watchOptions(game: Game): List<WatchOption> =
        Broadcasters.optionsFor(game, _allApps.value.map { InstalledApp(it.packageName, it.label) })

    private fun loadTeam(team: Team) {
        _teamDetail.value = TeamDetail(loading = true)
        viewModelScope.launch {
            val live = async { espn.findLiveGame(team) }
            val upcoming = async { espn.upcomingGames(team) }
            val recent = async { espn.recentResults(team) }
            val standings = async { espn.standings(team) }
            val liveGame = live.await()
            _teamDetail.value = TeamDetail(
                live = liveGame,
                liveOptions = liveGame?.let(::watchOptions).orEmpty(),
                upcoming = upcoming.await().filter { it.id != liveGame?.id },
                recent = recent.await(),
                standings = standings.await(),
                loading = false,
            )
        }
    }

    // ---- NBA ----

    /** The NBA page for today + [dayOffset]; scores refresh every 30 seconds while it's open. */
    fun loadNba(dayOffset: Int) {
        nbaJob?.cancel()
        _nba.value = NbaState(dayOffset = dayOffset, games = _nba.value.games.takeIf { _nba.value.dayOffset == dayOffset }.orEmpty())
        nbaJob = viewModelScope.launch {
            while (isActive && _screen.value == Screen.Nba) {
                val games = nbaGames(dayOffset)
                if (games != null || _nba.value.loading) {
                    _nba.value = NbaState(dayOffset, games ?: _nba.value.games, loading = false, updatedAt = System.currentTimeMillis())
                }
                if (dayOffset == 0 && games != null) {
                    _nbaSummary.value = NbaSummary(live = games.count { it.state == com.mytv.launcher.sports.GameState.LIVE }, today = games.size)
                }
                // Yesterday's finals and tomorrow's schedule don't change; today's scores do.
                if (dayOffset != 0 && games != null) break
                delay(30_000L)
            }
        }
    }

    /** That day's games, live first; null if ESPN couldn't be reached. */
    private suspend fun nbaGames(dayOffset: Int): List<Game>? = coroutineScope {
        val day = java.time.LocalDate.now().plusDays(dayOffset.toLong())
        val games = async { espn.scoreboard(Teams.nba, day) }
        // After midnight, last night's late games are still on: show them on today's page too.
        val before = if (dayOffset == 0) async { espn.scoreboard(Teams.nba, day.minusDays(1)) } else null
        val list = games.await()
        val stillOn = before?.await().orEmpty()
        if (list.isEmpty() && stillOn.isEmpty()) null else com.mytv.launcher.sports.NbaGames.forDay(list, stillOn)
    }

    /**
     * Play: the game on Prime Video (NBA League Pass lives there).
     * 1. that game's own page in Prime Video, from the League Pass channel;
     * 2. the game itself, if Prime Video lists it on the TV;
     * 3. not on League Pass (a national TV game): wherever it is on.
     */
    fun playOnPrime(game: Game) {
        val title = com.mytv.launcher.sports.NbaGames.title(game)
        viewModelScope.launch {
            val prime = primeVideo()
            if (prime == null) {
                toast("$title · Prime Video isn't installed on this TV")
                return@launch
            }
            if (openOnLeaguePass(game, title, prime.packageName)) return@launch
            val link = watchNext.findGame(com.mytv.launcher.sports.NbaGames.matchWords(game), listOf(prime.packageName))
            if (link != null && watchNext.openLink(link.first, link.second)) {
                toast("$title · starting on Prime Video")
                return@launch
            }
            if (!openGame(game, title, com.mytv.launcher.sports.NbaGames.matchWords(game))) navigate(Screen.Nba)
        }
    }

    private fun primeVideo() = _allApps.value.firstOrNull { com.mytv.launcher.sports.Service.PRIME_VIDEO.matches(it.packageName, it.label) }

    /** Opens the game's own page in Prime Video, if League Pass lists it. */
    private suspend fun openOnLeaguePass(game: Game, title: String, primePackage: String): Boolean {
        if (game.league.sport != "basketball") return false
        val card = com.mytv.launcher.sports.LeaguePass.find(leaguePass.cards(), game) ?: return false
        if (!watchNext.openFresh(primePackage, com.mytv.launcher.sports.LeaguePass.link(card))) return false
        toast("$title · opening it on League Pass")
        return true
    }

    // ---- Top 10 ----

    private fun refreshTopTen() {
        if (System.currentTimeMillis() - topTenLoadedAt < 6 * 3600_000L) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.Default) { installed.load() }
            val services = TopTenService.entries.filter { s -> apps.any { s.app.matches(it.packageName, it.label) } }.toSet()
            val lists = topTenRepo.load(services)
            if (lists.isEmpty()) return@launch
            topTenLoadedAt = System.currentTimeMillis()
            _topTen.value = lists
            // Warm the image cache so the Popular row's posters are there instantly.
            val loader = context.imageLoader
            lists.values.flatMap { it.take(4) }.mapNotNull { it.posterUrl }.take(16).forEach { url ->
                loader.enqueue(ImageRequest.Builder(context).data(url).size(252, 378).build())
            }
        }
    }

    // ---- Your taste ----

    /** Rebuilds your lists; the network parts are cached (recommendations 6h, seasons 12h). */
    fun refreshMine() {
        myJob?.cancel()
        myJob = viewModelScope.launch(Dispatchers.Default) {
            val ratings = myStore.all()
            // Instant: your ratings (Watched tab, Popular row filtering) before anything is fetched.
            _my.update { it.copy(ratings = ratings.associate { r -> r.key to r.rating }) }
            _allApps.first { it.isNotEmpty() }
            val providers = installedProviders()
            val recs = myTaste.recommendations(ratings, providers)
            val followed = myTaste.followed(ratings)
            val genres = com.mytv.launcher.mine.MyGuideBuilder.topGenres(recs.items, recs.liked)
            val sig = genres.joinToString() + providers.joinToString { it.name }
            val upcoming = myUpcoming?.takeIf { it.first == sig && System.currentTimeMillis() - myUpcomingAt < 6 * 3600_000L }?.second
                ?: myTaste.upcomingInGenres(providers, genres).also { myUpcoming = sig to it; myUpcomingAt = System.currentTimeMillis() }
            val fresh = myFresh?.takeIf { it.first == sig && System.currentTimeMillis() - myFreshAt < 6 * 3600_000L }?.second
                ?: myTaste.newInGenres(providers, genres).also { myFresh = sig to it; myFreshAt = System.currentTimeMillis() }
            val movieGenres = com.mytv.launcher.mine.MyGuideBuilder.topGenres(recs.items, recs.liked, movies = true)
            val movieSig = movieGenres.joinToString() + providers.joinToString { it.name }
            val freshMovies = myFreshMovies?.takeIf { it.first == movieSig && System.currentTimeMillis() - myFreshMoviesAt < 6 * 3600_000L }?.second
                ?: myTaste.newMoviesInGenres(providers, movieGenres).also { myFreshMovies = movieSig to it; myFreshMoviesAt = System.currentTimeMillis() }
            val guide = withContext(Dispatchers.Default) {
                com.mytv.launcher.mine.MyGuideBuilder.build(
                    java.time.LocalDate.now(), ratings, recs.items, recs.liked, recs.disliked, followed, upcoming, ::isAvailable, fresh, freshMovies,
                )
            }
            _my.value = MyState(guide, ratings.associate { it.key to it.rating }, loading = false)
        }
    }

    /** Pulls ratings made on your other TVs (and uploads ours), then refreshes what changed. */
    private val _syncStatus = MutableStateFlow(com.mytv.launcher.sync.CloudSync.status)
    val syncStatus = _syncStatus.asStateFlow()

    /**
     * Once per TV: your watched shows (IMDb + Serialized) into Watched. Runs
     * after the first sync so it skips anything already rated on another TV.
     */
    private suspend fun importHistory() {
        if (history.isDone) return
        val added = history.run(myStore)
        if (added > 0) {
            toast("Added $added shows and movies to My Watchlist")
            com.mytv.launcher.sync.CloudSync.push(myStore, "me")
            refreshMine()
        }
    }

    private var syncedAt = 0L

    private fun syncRatings() {
        syncedAt = System.currentTimeMillis()
        if (!com.mytv.launcher.sync.CloudSync.isConfigured) {
            viewModelScope.launch { importHistory() }
            return
        }
        viewModelScope.launch {
            com.mytv.launcher.sync.CloudSync.begin()
            if (com.mytv.launcher.sync.CloudSync.syncLayout(installed)) refreshApps()
            if (com.mytv.launcher.sync.CloudSync.sync(myStore, "me")) refreshMine()
            importHistory()
            if (com.mytv.launcher.sync.CloudSync.sync(taste, "ang") && _screen.value == Screen.Ang) publishAng()
            _syncStatus.value = com.mytv.launcher.sync.CloudSync.status
        }
    }

    /** Hold select on a Popular / Shows for you / Watchlist title. */
    fun myRate(item: AngItem, rating: com.mytv.launcher.ang.Rating?) {
        myStore.set(item, rating)
        viewModelScope.launch { com.mytv.launcher.sync.CloudSync.push(myStore, "me") }
        toast(rating?.let { "${item.title}: ${it.label.lowercase()}" } ?: "${item.title}: cleared")
        refreshMine()
    }

    /** Opens a title's streaming app if you have it, else asks Google TV where it plays. */
    fun openTitle(item: AngItem) = openAngShow(item)

    /**
     * Continue Watching = what the TV knows you're in the middle of, plus what
     * you marked "Watching now" (unless the TV already lists it).
     */
    fun withWatchingNow(fromTv: List<WatchItem>, watchingNow: List<AngItem>): List<WatchItem> {
        val tvTitles = fromTv.map { AngGuideBuilder.normalize(it.title) }.toSet()
        val manual = watchingNow.filter { AngGuideBuilder.normalize(it.title) !in tvTitles }.map { i ->
            val app = WhereToWatch.servicesFor(i.service).firstNotNullOfOrNull { s ->
                _allApps.value.firstOrNull { s.matches(it.packageName, it.label) }
            }
            WatchItem(
                key = MANUAL_PREFIX + i.key,
                title = i.title,
                subtitle = listOfNotNull("Watching", i.service).joinToString(" · "),
                imageUrl = i.imageUrl,
                progress = null,
                lastWatchedMillis = i.date?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L,
                searchQuery = i.title,
                source = i.service.orEmpty(),
                packageName = app?.packageName,
            )
        }
        return (fromTv + manual).sortedByDescending { it.lastWatchedMillis }
    }

    // ---- Ang's guide ----

    private var angInputs = com.mytv.launcher.ang.AngInputs(
        announced = com.mytv.launcher.ang.AngShows.curated.filter { it.announced != null },
    )
    private var rateJob: Job? = null

    /** Streaming services you have installed: suggestions only come from these. */
    private fun installedProviders(): List<WhereToWatch.Provider> {
        val apps = _allApps.value
        return WhereToWatch.providers.filter { p -> p.apps.any { s -> apps.any { s.matches(it.packageName, it.label) } } }
    }

    /** Whether a network / service name is watchable on an app you have. */
    private fun isAvailable(serviceName: String?): Boolean {
        val apps = _allApps.value
        return WhereToWatch.servicesFor(serviceName).any { s -> apps.any { s.matches(it.packageName, it.label) } }
    }

    /** Rebuilds the screen from what we have (fast, no network). */
    private suspend fun publishAng(loading: Boolean? = null, discovering: Boolean? = null) {
        val ratings = taste.all()
        angInputs = angInputs.copy(ratings = ratings)
        val inputs = angInputs
        val guide = withContext(Dispatchers.Default) {
            AngGuideBuilder.build(java.time.LocalDate.now(), inputs, ::isAvailable)
        }
        _ang.update {
            it.copy(
                guide = guide,
                ratings = ratings.associate { r -> r.key to r.rating },
                watchingNow = ratings.filter { r -> r.rating == com.mytv.launcher.ang.Rating.WATCHING }
                    .sortedByDescending { r -> r.date }.map { r -> r.toItem().copy(date = r.date) },
                rated = ratings.filter { r -> r.title.isNotBlank() },
                tonight = com.mytv.launcher.mine.Tonight.row(inputs.followed, java.time.LocalDate.now()),
                loading = loading ?: it.loading,
                discovering = discovering ?: it.discovering,
            )
        }
    }

    private fun loadAng() {
        angJob?.cancel()
        angJob = viewModelScope.launch {
            _allApps.first { it.isNotEmpty() }
            val providers = installedProviders()

            // 1. Instant: whatever is cached.
            angRepo.cachedCurated()?.let { angInputs = angInputs.copy(curated = it) }
            angRepo.cachedCandidates(providers)?.let {
                angInputs = angInputs.copy(shows = it.shows, movies = it.movies, upcomingShows = it.upcoming, sitcoms = it.sitcoms)
            }
            angTaste.cachedRecs()?.let { r ->
                angInputs = angInputs.copy(
                    shows = angInputs.shows + r.items.filter { !it.isMovie },
                    movies = angInputs.movies + r.items.filter { it.isMovie },
                    likedRecs = r.liked,
                    dislikedRecs = r.disliked,
                )
            }
            publishAng(loading = angInputs.curated.isEmpty(), discovering = true)
            launch { _ang.update { it.copy(continueWatching = herContinueWatching()) } }

            // 2. Refresh anything stale, updating the screen as each part lands.
            angInputs = angInputs.copy(curated = angRepo.curatedShows())
            publishAng(loading = false)
            refreshAngTaste(providers)
            publishAng(discovering = false)
        }
    }

    /** Suggestions + "more like what she liked" + liked shows' seasons. */
    private suspend fun refreshAngTaste(providers: List<WhereToWatch.Provider>) {
        val ratings = taste.all()
        val base = angRepo.candidates(providers)
        val recs = angTaste.recommendations(ratings, providers)
        val followed = angTaste.followed(ratings)
        angInputs = angInputs.copy(
            shows = base?.shows.orEmpty() + recs.items.filter { !it.isMovie },
            movies = base?.movies.orEmpty() + recs.items.filter { it.isMovie },
            upcomingShows = base?.upcoming.orEmpty(),
            sitcoms = base?.sitcoms.orEmpty(),
            likedRecs = recs.liked,
            dislikedRecs = recs.disliked,
            followed = followed,
        )
    }

    /**
     * Continue Watching, narrowed to her: shows on her list (liked, watching,
     * watch later, watched), her guide's shows, or her kind of show.
     * Anything she disliked or won't watch never shows.
     */
    private suspend fun herContinueWatching(): List<WatchItem> = withContext(Dispatchers.Default) {
        val g = _ang.value.guide
        val ratings = taste.all()
        val norm = AngGuideBuilder::normalize
        val no = ratings.filter { it.rating == com.mytv.launcher.ang.Rating.DISLIKE || it.rating == com.mytv.launcher.ang.Rating.WONT_WATCH }
            .map { norm(it.title) }.toSet()
        val titles = (ratings.filter { norm(it.title) !in no }.map { it.title } +
            angInputs.curated.map { it.second.name } +
            (g.watchNow + g.easyWatch + g.newShows + g.missed + g.movies + g.upcoming).map { it.title }).map(norm).toSet()
        val items = _watching.value.items.ifEmpty { watchNext.load() }
        items.filter { norm(it.title) !in no && angRepo.isHers(it.title, titles) }
    }

    /** Hold select → Like / Already watched / Dislike / Won't watch (null clears it). */
    fun angRate(item: AngItem, rating: com.mytv.launcher.ang.Rating?) {
        taste.set(item, rating)
        viewModelScope.launch { com.mytv.launcher.sync.CloudSync.push(taste, "ang") }
        toast(rating?.let { "${item.title}: ${it.label.lowercase()}" } ?: "${item.title}: cleared")
        rateJob?.cancel()
        rateJob = viewModelScope.launch {
            publishAng() // hide / badge it right away
            // Likes and dislikes change the suggestions: recompute in the background.
            if (rating == com.mytv.launcher.ang.Rating.LIKE || rating == com.mytv.launcher.ang.Rating.DISLIKE) {
                _ang.update { it.copy(discovering = true) }
                refreshAngTaste(installedProviders())
                publishAng(discovering = false)
            }
        }
    }

    /**
     * Opens a title as directly as possible: its page inside the streaming app
     * (via [com.mytv.launcher.ratings.DeepLinks]); else Google TV's page for it,
     * which has a Watch button; else just the app.
     */
    fun openAngShow(item: AngItem) {
        val apps = _allApps.value
        // The app that will open it (e.g. Bravo shows open in Peacock, Paramount+ in Prime Video).
        val match = WhereToWatch.servicesFor(item.service).firstNotNullOfOrNull { service ->
            apps.firstOrNull { service.matches(it.packageName, it.label) }?.let { service to it }
        }
        val app = match?.second
        if (opening) return // already on its way (the first lookup can take a second)
        opening = true
        viewModelScope.launch {
            try { openTitleNow(item, match, app) } finally { opening = false }
        }
    }

    @Volatile private var opening = false

    private suspend fun openTitleNow(
        item: AngItem,
        match: Pair<com.mytv.launcher.sports.Service, com.mytv.launcher.apps.TvApp>?,
        app: com.mytv.launcher.apps.TvApp?,
    ) {
        val direct = match?.let { (service, a) -> com.mytv.launcher.ratings.DeepLinks.intentFor(context, item, service, a.packageName) }
        if (direct != null && runCatching { context.startActivity(direct) }.isSuccess) return
        if (Search.open(context, item.title)) return
        if (app != null && installed.launch(app.packageName)) return
        toast("Couldn't find where ${item.title} is playing")
    }

    // ---- Continue watching ----

    private var watchingJob: Job? = null

    /**
     * Continue Watching = the system Watch Next list (what every app reports)
     * plus, optionally, Trakt. Same title from both shows once, newest first.
     */
    fun refreshWatching() {
        watchingDirty = false
        watchingJob?.cancel()
        watchingJob = viewModelScope.launch {
            _watching.update {
                it.copy(
                    loading = it.items.isEmpty(),
                    needsPermission = !watchNext.hasPermission(),
                    traktConfigured = trakt.isConfigured,
                    traktLinked = trakt.isLinked,
                )
            }
            val local = async { watchNext.load() }
            val remote = async { if (trakt.isConfigured && trakt.isLinked) trakt.continueWatching() else emptyList() }
            val fromApps = local.await().filter { it.packageName == null || com.mytv.launcher.watching.NowPlaying.shouldTrack(it.packageName) }
            val noted = com.mytv.launcher.watching.PlayHistory.entries(context).value
            val tracked = noted.filter { com.mytv.launcher.watching.NowPlaying.shouldTrack(it.packageName) }.mapNotNull(::notedItem)
            val seen = HashSet<String>()
            val merged = (fromApps + tracked + remote.await()).filter { seen.add(it.title.lowercase()) }
                .sortedByDescending { it.lastWatchedMillis }
                .take(25)
            _watching.value = WatchState(
                status = when {
                    !watchNext.hasPermission() -> "Continue Watching: no access yet. Select \"Allow access\"."
                    !watchNext.lastReadOk -> "Continue Watching: this TV refused access to its list."
                    fromApps.isEmpty() -> "Continue Watching: access OK, but no app has shared anything in progress with the TV."
                    else -> "Continue Watching: ${fromApps.size} in progress from " +
                        fromApps.map { it.source }.distinct().joinToString(", ")
                },
                tracking = com.mytv.launcher.watching.PlayingListener.isEnabled(context),
                trackStatus = when {
                    !com.mytv.launcher.watching.PlayingListener.isEnabled(context) ->
                        "Watch tracking: off. Turn it on so MyTV notes what you play in each app."
                    noted.isEmpty() -> "Watch tracking: on. Play something in any app and it shows up in Continue Watching."
                    else -> "Watch tracking: on · ${noted.size} noted · last: ${noted.first().app}: ${noted.first().raw}"
                },
                items = merged,
                loading = false,
                needsPermission = !watchNext.hasPermission(),
                traktConfigured = trakt.isConfigured,
                traktLinked = trakt.isLinked,
            )
            val hers = herContinueWatching()
            _ang.update { it.copy(continueWatching = hers) }
        }
    }

    /** A show MyTV saw playing → a Continue Watching card (finished movies drop off). */
    private fun notedItem(e: com.mytv.launcher.watching.PlayHistory.Entry): WatchItem? {
        val progress = if (e.durationMillis > 0) (e.positionMillis.toFloat() / e.durationMillis).coerceIn(0f, 1f) else null
        if (e.season == null && e.episodeTitle == null && (progress ?: 0f) > 0.95f) return null
        return WatchItem(
            key = NOTED_PREFIX + e.id,
            title = e.show,
            subtitle = listOfNotNull(e.app, e.season?.let { "S$it" }, e.episode?.let { "E$it" }, e.episodeTitle).joinToString(" · "),
            imageUrl = e.imageUrl,
            progress = progress,
            lastWatchedMillis = e.at,
            searchQuery = e.show,
            source = e.app,
            packageName = e.packageName,
        )
    }

    /** Opens the screen where you turn on watch tracking (Notification access for MyTV). */
    fun allowTracking() {
        val me = com.mytv.launcher.watching.PlayingListener.component(context)
        val tries = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 30) add(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString()),
            )
            add(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(android.content.Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"))
        }
        val opened = tries.any { i ->
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            i.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(i) }.isSuccess
        }
        if (!opened) toast("This TV hides that setting. Turn it on with: adb shell cmd notification allow_listener ${me.flattenToString()}")
    }

    fun playWatchItem(item: WatchItem) {
        if (item.key.startsWith(NOTED_PREFIX)) {
            openAngShow(
                com.mytv.launcher.ang.AngItem(
                    key = item.key, title = item.title, detail = "", imageUrl = item.imageUrl,
                    service = item.source, date = null, badge = null,
                ),
            )
            return
        }
        // "Watching now" cards: open the title itself (its page in the app).
        if (item.key.startsWith(MANUAL_PREFIX)) {
            val key = item.key.removePrefix(MANUAL_PREFIX)
            (_my.value.guide.watching + _ang.value.watchingNow).firstOrNull { it.key == key }?.let {
                openAngShow(it)
                return
            }
        }
        if (watchNext.open(item)) return
        if (item.packageName != null && installed.launch(item.packageName)) return
        if (!Search.open(context, item.searchQuery)) toast("Couldn't open ${item.title}")
    }

    /** Shown when the system has no permission prompt for TV listings. */
    fun showPermissionHelp() {
        toast("Allow access from a computer: adb shell pm grant ${context.packageName} ${WatchNext.PERMISSIONS.first()}")
    }

    private fun startTraktLink() {
        linkJob?.cancel()
        if (!trakt.isConfigured) {
            _link.value = LinkState.Failed("This build has no Trakt API key. See README → Continue Watching.")
            return
        }
        if (trakt.isLinked) {
            _link.value = LinkState.Linked
            return
        }
        _link.value = LinkState.Starting
        linkJob = viewModelScope.launch {
            val code = trakt.startDeviceLogin()
            if (code == null) {
                _link.value = LinkState.Failed("Couldn't reach Trakt. Check the internet connection.")
                return@launch
            }
            _link.value = LinkState.ShowCode(code)
            var interval = code.intervalSeconds.coerceAtLeast(1) * 1000L
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(interval)
                when (trakt.pollDeviceLogin(code)) {
                    PollResult.Linked -> {
                        _link.value = LinkState.Linked
                        refreshWatching()
                        delay(1500)
                        back()
                        return@launch
                    }
                    PollResult.SlowDown -> interval += 1000
                    PollResult.Denied -> { _link.value = LinkState.Failed("Trakt access was declined."); return@launch }
                    PollResult.Expired -> break
                    PollResult.Pending -> Unit
                }
            }
            _link.update { if (it is LinkState.Linked) it else LinkState.Failed("The code expired. Press back and try again.") }
        }
    }

    val isTraktLinked: Boolean get() = trakt.isLinked

    fun unlinkTrakt() {
        trakt.unlink()
        refreshWatching()
        startTraktLink()
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    // ---- Instant start: the Home screen as it last looked ----

    private val snapshot = com.mytv.launcher.snapshot.HomeSnapshot(app.filesDir)

    init {
        viewModelScope.launch {
            val (apps, snap, ratings) = withContext(Dispatchers.IO) {
                Triple(installed.cached(), snapshot.load(), myStore.all())
            }
            // Only fills what hasn't loaded for real yet.
            if (_apps.value.isEmpty() && apps.isNotEmpty()) _apps.value = apps
            snap?.let { d ->
                if (_watching.value.items.isEmpty() && d.watching.isNotEmpty()) _watching.update { it.copy(items = d.watching, loading = false) }
                if (_my.value.loading && _my.value.guide == com.mytv.launcher.mine.MyGuide()) {
                    _my.update { it.copy(guide = d.guide, ratings = ratings.associate { r -> r.key to r.rating }) }
                }
                if (_topTen.value.isEmpty()) _topTen.value = d.topTen
            }
            // Keep the saved copy up to date (a few seconds after things settle).
            kotlinx.coroutines.flow.combine(_watching, _my, _topTen) { w, m, t ->
                com.mytv.launcher.snapshot.HomeSnapshot.Data(w.items, m.guide, t)
            }
                .drop(1)
                .debounce(5_000)
                .collect { d ->
                    if (d.watching.isEmpty() && d.guide == com.mytv.launcher.mine.MyGuide() && d.topTen.isEmpty()) return@collect
                    withContext(Dispatchers.IO) { runCatching { snapshot.save(d) } }
                }
        }
    }

    // ---- Profiles: you, Ang, guest ----

    private val settings = com.mytv.launcher.system.MyTvSettings(app)

    enum class Profile(val key: String, val label: String) { ME("me", "Me"), ANG("ang", "Ang"), GUEST("guest", "Guest") }

    private val _profile = MutableStateFlow(Profile.entries.firstOrNull { it.key == settings.profile } ?: Profile.ME)
    val profile = _profile.asStateFlow()

    /** "Who's watching?" shows when the TV comes back after a few hours, or from the profile button. */
    private val _pickProfile = MutableStateFlow(false)
    val pickProfile = _pickProfile.asStateFlow()
    private var lastAway = 0L

    fun openProfilePicker() { _pickProfile.value = true }
    fun closeProfilePicker() { _pickProfile.value = false }

    fun chooseProfile(p: Profile) {
        settings.profile = p.key
        _profile.value = p
        _pickProfile.value = false
        if (p == Profile.ANG) loadAng()
        goHome()
    }

    /** Called from onResume / onStop to decide when to ask "Who's watching?". */
    private fun profileOnResume() {
        if (lastAway > 0 && System.currentTimeMillis() - lastAway > 3 * 3600_000L) _pickProfile.value = true
        lastAway = 0
        if (_profile.value == Profile.ANG && _ang.value.guide == com.mytv.launcher.ang.AngGuide()) loadAng()
    }

    private fun profileOnStop() {
        lastAway = System.currentTimeMillis()
    }

    // ---- System: home screen, screensaver, alerts, sleep timer, updates ----

    private val homeSetup = com.mytv.launcher.system.HomeSetup(app)

    data class SystemState(
        val isHome: Boolean,
        val myScreensaver: Boolean,
        val kickoffAlerts: Boolean,
        val scoreAlerts: Boolean,
        val channel: String,
        val sleepAt: Long,
        val sleepAfterEpisode: Boolean,
        val busy: Boolean = false,
        val message: String = "",
        /** The one line to run from a phone before MyTV can switch things itself (null once done). */
        val phoneSetup: String? = null,
        val helper: String = "",
        val fantasyButton: String = "none",
        val fantraxSignedIn: Boolean = false,
        /** Paired with the TV's Wireless debugging (MyTV sets itself up without a phone). */
        val wirelessPaired: Boolean = false,
    )

    private fun systemNow(busy: Boolean = false, message: String = _system.value.message) = SystemState(
        isHome = homeSetup.isOn,
        myScreensaver = homeSetup.usesMyTvScreensaver,
        kickoffAlerts = settings.kickoffAlerts,
        scoreAlerts = settings.scoreAlerts,
        channel = settings.updateChannel,
        sleepAt = settings.sleepAt,
        sleepAfterEpisode = settings.sleepAfterEpisode,
        busy = busy,
        message = message,
        fantasyButton = com.mytv.launcher.system.FantasyButton.label(settings.fantasyKey),
        fantraxSignedIn = settings.fantraxSignedIn,
        wirelessPaired = com.mytv.launcher.system.WirelessAdb.isPaired(context),
        phoneSetup = if (homeSetup.canWriteSecure) null else com.mytv.launcher.system.HomeSetup.PHONE_COMMAND,
        helper = when {
            !settings.homeOverride -> ""
            homeSetup.helperRunning -> "Home helper: running"
            else -> "Home helper: switched on but Android hasn't started it (blocked as a restricted setting?)"
        },
    )

    private val _system = MutableStateFlow(
        SystemState(false, false, true, true, "stable", 0L, false),
    )
    val system = _system.asStateFlow()

    fun refreshSystem() { _system.value = systemNow() }

    private fun runSetup(waiting: String, action: suspend () -> com.mytv.launcher.system.HomeSetup.Result) {
        if (_system.value.busy) return
        _system.value = systemNow(busy = true, message = waiting)
        viewModelScope.launch {
            // Anything unexpected still ends with a message, never a silent stop.
            val r = runCatching { action() }.getOrElse {
                com.mytv.launcher.system.HomeSetup.Result.NoAccess("That didn't work (${it.message ?: it.javaClass.simpleName}).")
            }
            val msg = when (r) {
                is com.mytv.launcher.system.HomeSetup.Result.Done -> r.message
                is com.mytv.launcher.system.HomeSetup.Result.NoAccess -> r.message
            }
            _system.value = systemNow(busy = false, message = msg)
        }
    }

    /**
     * Wireless debugging instead of a phone: pairs with the code from Settings,
     * then makes MyTV the home screen straight away.
     */
    fun pairWireless(code: String, port: String) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) {
            _system.value = systemNow(message = "Type the 6-digit pairing code from the TV's Wireless debugging screen.")
            return
        }
        runSetup("Pairing with Wireless debugging…") {
            val error = com.mytv.launcher.system.WirelessAdb.pair(context, digits, port.trim().toIntOrNull())
            if (error != null) com.mytv.launcher.system.HomeSetup.Result.NoAccess(error) else homeSetup.takeOver()
        }
    }

    /**
     * The pairing code only works while Settings' pairing screen is open, so
     * MyTV floats a keypad over it (see PairingOverlay).
     */
    fun pairWithKeypad() {
        val msg = when (val r = com.mytv.launcher.system.PairingOverlay.start(context)) {
            com.mytv.launcher.system.PairingOverlay.Start.Shown ->
                "In Developer options: Wireless debugging → turn it on → Pair device with pairing code. MyTV's keypad appears in the corner; type the code there."
            is com.mytv.launcher.system.PairingOverlay.Start.NeedsPermission ->
                if (r.pageOpened) "First allow MyTV to display over other apps (turn it on, then press Back), then choose the keypad again."
                else "This TV won't let MyTV show its keypad over Settings. Use Bugjaeger (below) this one time."
        }
        _system.value = systemNow(message = msg)
    }

    fun makeHome() = runSetup("Setting up… if the TV asks \"Allow USB debugging?\", tick Always allow and choose OK.") { homeSetup.takeOver() }
    fun undoHome() = runSetup("Turning Google TV back on…") { homeSetup.undo() }
    fun setMyScreensaver(on: Boolean) = runSetup("Changing the screensaver…") { homeSetup.setMyTvScreensaver(on) }

    fun setKickoffAlerts(on: Boolean) { settings.kickoffAlerts = on; refreshSystem() }
    fun setScoreAlerts(on: Boolean) { settings.scoreAlerts = on; refreshSystem() }
    fun setUpdateChannel(channel: String) { settings.updateChannel = channel; refreshSystem() }

    /** Sleep timer: [minutes] from now, -1 = at the end of this episode, 0 = off. */
    fun setSleepTimer(minutes: Int) {
        settings.sleepAfterEpisode = minutes == -1
        settings.sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        refreshSystem()
        toast(
            when {
                minutes > 0 -> "The TV will turn off in $minutes minutes"
                minutes == -1 -> "The TV will turn off when this episode ends"
                else -> "Sleep timer off"
            },
        )
    }

    // ---- Weather (top bar, screensaver) ----

    private val _weather = MutableStateFlow<com.mytv.launcher.weather.Weather.Now?>(null)
    val weather = _weather.asStateFlow()

    private fun refreshWeather() {
        viewModelScope.launch { com.mytv.launcher.weather.Weather.now()?.let { _weather.value = it } }
    }

    // ---- Send to TV (a page on your phone that adds to your lists through the sync) ----

    /** The page's address, with this household's sync details after the # (never sent to any server but yours). */
    val sendToTvUrl: String? by lazy {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SYNC_HOUSEHOLD.isBlank()) return@lazy null
        val (owner, repo) = BuildConfig.UPDATE_REPO.split('/').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val cfg = org.json.JSONObject()
            .put("u", BuildConfig.SUPABASE_URL.trim().trimEnd('/').removeSuffix("/rest/v1").trimEnd('/'))
            .put("k", BuildConfig.SUPABASE_KEY)
            .put("h", BuildConfig.SYNC_HOUSEHOLD)
            .put("t", BuildConfig.TMDB_API_KEY)
        val enc = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(cfg.toString().toByteArray())
        "https://${owner.lowercase()}.github.io/$repo/send.html#$enc"
    }

    /** MyTV settings → Test NBC: opens YouTube TV on the NBC channel, as a game on NBC would. */
    fun testNbc() {
        val app = _allApps.value.firstOrNull { com.mytv.launcher.sports.Service.YOUTUBE_TV.matches(it.packageName, it.label) }
        val link = com.mytv.launcher.sports.YouTubeTvChannels.linkFor("NBC")
        when {
            app == null -> toast("YouTube TV isn't installed on this TV")
            link != null && watchNext.openLink(app.packageName, link) -> Unit
            else -> toast("YouTube TV didn't accept the NBC link")
        }
    }

    /** Remote shortcut from a kickoff alert: open that team's game. */
    fun watchTeam(key: String) {
        val team = Teams.byKey(key) ?: return
        viewModelScope.launch {
            _allApps.first { it.isNotEmpty() } // need the apps to know which one shows the game
            onTeamPressed(team)
        }
    }
}
