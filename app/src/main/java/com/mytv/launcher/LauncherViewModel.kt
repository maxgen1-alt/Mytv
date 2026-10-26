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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class TeamPage(val teamKey: String) : Screen
    data object EditApps : Screen
    data object TraktLink : Screen
    data object Ang : Screen
    data object Watchlist : Screen
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
)

/** Your ratings and lists. */
data class MyState(
    val guide: com.mytv.launcher.mine.MyGuide = com.mytv.launcher.mine.MyGuide(),
    /** key → what you said about it (Popular row badges, the hold-select menu). */
    val ratings: Map<String, com.mytv.launcher.ang.Rating> = emptyMap(),
    val loading: Boolean = true,
) {
    /** "Shows for you" shows on the home screen once you've rated a few titles. */
    val showForYouRow: Boolean
        get() = guide.forYou.isNotEmpty() &&
            ratings.values.count { it != com.mytv.launcher.ang.Rating.WONT_WATCH } >= com.mytv.launcher.mine.MyGuide.MIN_RATINGS_FOR_HOME_ROW
}

data class WatchState(
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

sealed interface LinkState {
    data object Starting : LinkState
    data class ShowCode(val code: DeviceCode) : LinkState
    data object Linked : LinkState
    data class Failed(val message: String) : LinkState
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val installed = InstalledApps(app)
    private val espn = EspnClient()
    private val trakt = TraktClient(app)
    private val watchNext = WatchNext(app)
    private val updater = Updater(app)
    private val angRepo = AngRepository(app)

    init {
        com.mytv.launcher.ratings.Ratings.init(app)
    }
    private val taste = com.mytv.launcher.ang.TasteStore(app)
    private val angTaste = com.mytv.launcher.ang.TasteRepository(
        app, strict = true,
        positive = mapOf(com.mytv.launcher.ang.Rating.LIKE to 1),
        follow = setOf(com.mytv.launcher.ang.Rating.LIKE),
    )

    // ---- Your own taste (Popular row, Shows for you, My Watchlist) ----
    private val myStore = com.mytv.launcher.ang.TasteStore(app, "taste_me")
    private val myTaste = com.mytv.launcher.ang.TasteRepository(
        app, strict = false,
        // "Already watched" is your main signal; a like counts double.
        positive = mapOf(com.mytv.launcher.ang.Rating.LIKE to 2, com.mytv.launcher.ang.Rating.WATCHED to 1),
        follow = setOf(com.mytv.launcher.ang.Rating.LIKE, com.mytv.launcher.ang.Rating.WATCHED),
        maxCandidates = 40,
    )
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
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
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
    private val _liveGames = MutableStateFlow<Map<String, Game>>(emptyMap())
    val liveGames = _liveGames.asStateFlow()

    private val _teamDetail = MutableStateFlow(TeamDetail())
    val teamDetail = _teamDetail.asStateFlow()

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
            Screen.TraktLink -> startTraktLink()
            Screen.Ang -> loadAng()
            Screen.Watchlist -> refreshMine()
            else -> Unit
        }
    }

    fun back() {
        linkJob?.cancel()
        _screen.value = Screen.Home
    }

    /** Called every time the launcher comes to the front. */
    fun onResume() {
        refreshApps()
        refreshWatching()
        checkForUpdates()
        refreshTopTen()
        refreshMine()
        // Warm Ang's TV in the background so it opens instantly (everything is cached).
        if (angJob?.isActive != true) angJob = viewModelScope.launch {
            _allApps.first { it.isNotEmpty() } // need the installed apps to know which services to search
            val providers = installedProviders()
            angRepo.curatedShows()
            angRepo.candidates(providers)
            val ratings = taste.all()
            angTaste.recommendations(ratings, providers)
            angTaste.followed(ratings)
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (isActive) {
                val found = Teams.all.map { team -> async { team.key to espn.findLiveGame(team) } }
                    .mapNotNull { it.await().let { (k, g) -> g?.let { k to it } } }
                    .toMap()
                _liveGames.value = found
                delay(2 * 60_000L)
            }
        }
    }

    fun onPause() {
        liveJob?.cancel()
    }

    /** MyTV went to the background: a good moment to update without interrupting anything. */
    fun onStop() {
        inForeground = false
        installSilentlyIfReady()
    }

    private var inForeground = false

    private fun installSilentlyIfReady() {
        if (inForeground || !updater.canInstallSilently()) return
        updater.pendingApk()?.let(updater::install)
    }

    // ---- Self-update ----

    /**
     * Checks now (throttled to every 30 min inside [Updater]) and then every
     * few hours for as long as the process lives, even in the background.
     */
    private fun checkForUpdates() {
        inForeground = true
        updateJob?.cancel()
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
    }

    fun setHidden(app: TvApp, hidden: Boolean) {
        installed.setHidden(app.packageName, hidden)
        refreshApps()
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

    fun watchGame(team: Team, game: Game, option: WatchOption?) {
        val opponent = game.opponentOf(team.espnId).name
        if (option != null && installed.launch(option.app.packageName)) {
            toast("${team.name} vs $opponent · opening ${option.app.label}")
            return
        }
        // Nothing installed that we know carries it: let Google TV find it.
        toast("Searching for ${team.name} vs $opponent")
        if (!Search.open(context, "${team.name} vs $opponent live")) {
            navigate(Screen.TeamPage(team.key))
        }
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
            lists.values.flatten().mapNotNull { it.posterUrl }.forEach { url ->
                loader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }

    // ---- Your taste ----

    /** Rebuilds your lists; the network parts are cached (recommendations 6h, seasons 12h). */
    fun refreshMine() {
        myJob?.cancel()
        myJob = viewModelScope.launch {
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
            val guide = withContext(Dispatchers.Default) {
                com.mytv.launcher.mine.MyGuideBuilder.build(
                    java.time.LocalDate.now(), ratings, recs.items, recs.liked, recs.disliked, followed, upcoming, ::isAvailable,
                )
            }
            _my.value = MyState(guide, ratings.associate { it.key to it.rating }, loading = false)
        }
    }

    /** Hold select on a Popular / Shows for you / Watchlist title. */
    fun myRate(item: AngItem, rating: com.mytv.launcher.ang.Rating?) {
        myStore.set(item, rating)
        toast(rating?.let { "${item.title}: ${it.label.lowercase()}" } ?: "${item.title}: cleared")
        refreshMine()
    }

    /** Opens a title's streaming app if you have it, else asks Google TV where it plays. */
    fun openTitle(item: AngItem) = openAngShow(item)

    // ---- Ang's guide ----

    private var angInputs = com.mytv.launcher.ang.AngInputs()
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
                angInputs = angInputs.copy(shows = it.shows, movies = it.movies, upcomingShows = it.upcoming)
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
            likedRecs = recs.liked,
            dislikedRecs = recs.disliked,
            followed = followed,
        )
    }

    /** The TV's Continue Watching, narrowed to her kind of shows. */
    private suspend fun herContinueWatching(): List<WatchItem> {
        val g = _ang.value.guide
        val titles = (angInputs.curated.map { it.second.name } +
            (g.watchNow + g.newShows + g.missed + g.movies + g.upcoming).map { it.title }).toSet()
        return watchNext.load().filter { angRepo.isHers(it.title, titles) }
    }

    /** Hold select → Like / Already watched / Dislike / Won't watch (null clears it). */
    fun angRate(item: AngItem, rating: com.mytv.launcher.ang.Rating?) {
        taste.set(item, rating)
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

    /** Opens the show's streaming app if it's installed, else asks Google TV where it plays. */
    fun openAngShow(item: AngItem) {
        val apps = _allApps.value
        val app = WhereToWatch.servicesFor(item.service).firstNotNullOfOrNull { service ->
            apps.firstOrNull { service.matches(it.packageName, it.label) }
        }
        if (app != null && installed.launch(app.packageName)) return
        if (!Search.open(context, item.title)) toast("Couldn't find where ${item.title} is playing")
    }

    // ---- Continue watching ----

    private var watchingJob: Job? = null

    /**
     * Continue Watching = the system Watch Next list (what every app reports)
     * plus, optionally, Trakt. Same title from both shows once, newest first.
     */
    fun refreshWatching() {
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
            val fromApps = local.await()
            val seen = fromApps.map { it.title.lowercase() }.toSet()
            val merged = (fromApps + remote.await().filter { it.title.lowercase() !in seen })
                .sortedByDescending { it.lastWatchedMillis }
                .take(25)
            _watching.value = WatchState(
                items = merged,
                loading = false,
                needsPermission = !watchNext.hasPermission(),
                traktConfigured = trakt.isConfigured,
                traktLinked = trakt.isLinked,
            )
        }
    }

    fun playWatchItem(item: WatchItem) {
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
}
