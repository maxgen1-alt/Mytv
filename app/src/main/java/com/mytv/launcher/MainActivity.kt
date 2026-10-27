package com.mytv.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.ui.AngListScreen
import com.mytv.launcher.ui.AngScreen
import com.mytv.launcher.ui.EditAppsScreen
import com.mytv.launcher.ui.HomeScreen
import com.mytv.launcher.ui.MyTvTheme
import com.mytv.launcher.ui.TeamScreen
import com.mytv.launcher.ui.TraktLinkScreen
import com.mytv.launcher.ui.WatchlistScreen
import com.mytv.launcher.watching.WatchNext

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private val watchNextPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // No prompt on some firmware: the request comes straight back denied.
            if (result.values.none { it }) vm.showPermissionHelp()
            vm.refreshWatching()
        }

    private fun requestWatchNext() = watchNextPermission.launch(WatchNext.PERMISSIONS.toTypedArray())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask once on first launch so Continue Watching just works.
        val prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        if (!prefs.getBoolean("asked_watch_next", false) && !WatchNext(this).hasPermission()) {
            prefs.edit().putBoolean("asked_watch_next", true).apply()
            requestWatchNext()
        }

        setContent {
            val gameDay by vm.gameDay.collectAsStateWithLifecycle()
            MyTvTheme(gameDay) {
                val screen by vm.screen.collectAsStateWithLifecycle()

                // We're the home screen: Back from Home does nothing, Back elsewhere returns Home.
                BackHandler(enabled = true) { if (screen != Screen.Home) vm.back() }

                when (val s = screen) {
                    Screen.Home -> {
                        val apps by vm.apps.collectAsStateWithLifecycle()
                        val teams by vm.teams.collectAsStateWithLifecycle()
                        val watching by vm.watching.collectAsStateWithLifecycle()
                        val busy by vm.busy.collectAsStateWithLifecycle()
                        val updateReady by vm.updateReady.collectAsStateWithLifecycle()
                        val popular by vm.popular.collectAsStateWithLifecycle()
                        val my by vm.my.collectAsStateWithLifecycle()
                        // Derived lists are remembered: rebuilding them on every update would
                        // hand every row new lists and redraw the whole Home screen.
                        val homeWatching = remember(watching, my.guide.watching) {
                            watching.copy(items = vm.withWatchingNow(watching.items, my.guide.watching))
                        }
                        val watchingNow = remember(my.guide.watching) { my.guide.watching.associateBy { MANUAL_PREFIX + it.key } }
                        val popularItems = remember(popular, my.ratings) {
                            popular.map { (service, t) ->
                                val item = vm.popularItem(service, t)
                                if (my.ratings[item.key] == com.mytv.launcher.ang.Rating.LIKE) item.copy(badge = "♥ Liked") else item
                            }
                        }
                        val newForYou = remember(my) { if (my.showForYouRow) my.guide.newShows.take(20) else emptyList() }
                        val missedForYou = remember(my) { if (my.showForYouRow) my.guide.missed.take(20) else emptyList() }
                        HomeScreen(
                            apps = apps,
                            teams = teams,
                            watching = homeWatching,
                            watchingNow = watchingNow,
                            busy = busy,
                            updateReady = updateReady,
                            popular = popularItems,
                            newForYou = newForYou,
                            missedForYou = missedForYou,
                            watchLater = my.guide.watchLater,
                            myRatings = my.ratings,
                            onOpenTitle = vm::openTitle,
                            onRateTitle = vm::myRate,
                            onWatchlist = { vm.navigate(Screen.Watchlist) },
                            onUpdate = vm::installUpdate,
                            onApp = vm::launchApp,
                            onMoveApp = vm::moveApp,
                            onHideApp = { vm.setHidden(it, true) },
                            onEditApps = { vm.navigate(Screen.EditApps) },
                            onTeam = vm::onTeamPressed,
                            onTeamPage = { vm.navigate(Screen.TeamPage(it.key)) },
                            onAng = { vm.navigate(Screen.Ang) },
                            onWatchItem = vm::playWatchItem,
                            onAllowWatchNext = ::requestWatchNext,
                            onTurnOnTracking = vm::allowTracking,
                            onLinkTrakt = { vm.navigate(Screen.TraktLink) },
                        )
                    }
                    is Screen.TeamPage -> {
                        val team = Teams.byKey(s.teamKey) ?: return@MyTvTheme
                        val detail by vm.teamDetail.collectAsStateWithLifecycle()
                        TeamScreen(team, detail, onWatch = { game, option -> vm.watchGame(team, game, option) })
                    }
                    Screen.EditApps -> {
                        val all by vm.allApps.collectAsStateWithLifecycle()
                        val hidden by vm.hidden.collectAsStateWithLifecycle()
                        val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
                        val watchState by vm.watching.collectAsStateWithLifecycle()
                        val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
                        EditAppsScreen(
                            all,
                            hidden,
                            version = vm.versionName,
                            updateStatus = updateStatus,
                            onCheckUpdates = vm::checkForUpdatesNow,
                            watchStatus = watchState.status,
                            needsWatchAccess = watchState.needsPermission,
                            onAllowWatchAccess = ::requestWatchNext,
                            trackStatus = watchState.trackStatus,
                            tracking = watchState.tracking,
                            onTurnOnTracking = vm::allowTracking,
                            syncStatus = syncStatus,
                            onToggle = vm::setHidden,
                        )
                    }
                    Screen.Ang -> {
                        val ang by vm.ang.collectAsStateWithLifecycle()
                        AngScreen(
                            ang,
                            onOpenList = vm::openAngList,
                            continueWatching = vm.withWatchingNow(ang.continueWatching, ang.watchingNow),
                            onShow = vm::openAngShow,
                            onRate = vm::angRate,
                            onResume = vm::playWatchItem,
                        )
                    }
                    Screen.AngList -> {
                        val ang by vm.ang.collectAsStateWithLifecycle()
                        AngListScreen(ang, onOpen = vm::openAngShow, onRate = vm::angRate)
                    }
                    Screen.Watchlist -> {
                        val my by vm.my.collectAsStateWithLifecycle()
                        WatchlistScreen(my, onOpen = vm::openTitle, onRate = vm::myRate)
                    }
                    Screen.TraktLink -> {
                        val link by vm.link.collectAsStateWithLifecycle()
                        TraktLinkScreen(link, linked = link == LinkState.Linked && vm.isTraktLinked, onUnlink = vm::unlinkTrakt)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }

    override fun onStop() {
        super.onStop()
        vm.onStop()
    }

    /** Pressing Home while we're already in front: jump back to the main screen. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        vm.goHome()
    }
}
