package com.mytv.launcher

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.mytv.launcher.watching.TraktClient
import com.mytv.launcher.watching.WatchItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class TeamPage(val teamKey: String) : Screen
    data object EditApps : Screen
    data object TraktLink : Screen
}

sealed interface WatchState {
    data object NotConfigured : WatchState
    data object NotLinked : WatchState
    data object Loading : WatchState
    data class Ready(val items: List<WatchItem>) : WatchState
}

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

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen = _screen.asStateFlow()

    private val _allApps = MutableStateFlow<List<TvApp>>(emptyList())
    val allApps = _allApps.asStateFlow()

    private val _apps = MutableStateFlow<List<TvApp>>(emptyList())
    val apps = _apps.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden = _hidden.asStateFlow()

    private val _watching = MutableStateFlow<WatchState>(WatchState.Loading)
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

    // ---- Apps ----

    fun refreshApps() {
        val all = installed.load()
        _allApps.value = all
        _hidden.value = installed.hiddenPackages()
        _apps.value = installed.visible(all)
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

    // ---- Continue watching ----

    fun refreshWatching() {
        when {
            !trakt.isConfigured -> _watching.value = WatchState.NotConfigured
            !trakt.isLinked -> _watching.value = WatchState.NotLinked
            else -> viewModelScope.launch {
                if (_watching.value !is WatchState.Ready) _watching.value = WatchState.Loading
                val items = trakt.continueWatching()
                _watching.value = if (trakt.isLinked) WatchState.Ready(items) else WatchState.NotLinked
            }
        }
    }

    fun playWatchItem(item: WatchItem) {
        if (!Search.open(context, item.searchQuery)) toast("Couldn't open search for ${item.title}")
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
