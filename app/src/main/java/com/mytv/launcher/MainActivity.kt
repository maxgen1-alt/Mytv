package com.mytv.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.ui.EditAppsScreen
import com.mytv.launcher.ui.HomeScreen
import com.mytv.launcher.ui.MyTvTheme
import com.mytv.launcher.ui.TeamScreen
import com.mytv.launcher.ui.TraktLinkScreen
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
            MyTvTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()

                // We're the home screen: Back from Home does nothing, Back elsewhere returns Home.
                BackHandler(enabled = true) { if (screen != Screen.Home) vm.back() }

                when (val s = screen) {
                    Screen.Home -> {
                        val apps by vm.apps.collectAsStateWithLifecycle()
                        val live by vm.liveGames.collectAsStateWithLifecycle()
                        val watching by vm.watching.collectAsStateWithLifecycle()
                        val busy by vm.busy.collectAsStateWithLifecycle()
                        val updateReady by vm.updateReady.collectAsStateWithLifecycle()
                        HomeScreen(
                            apps = apps,
                            liveGames = live,
                            watching = watching,
                            busy = busy,
                            updateReady = updateReady,
                            onUpdate = vm::installUpdate,
                            onApp = vm::launchApp,
                            onMoveApp = vm::moveApp,
                            onHideApp = { vm.setHidden(it, true) },
                            onEditApps = { vm.navigate(Screen.EditApps) },
                            onTeam = vm::onTeamPressed,
                            onTeamPage = { vm.navigate(Screen.TeamPage(it.key)) },
                            onWatchItem = vm::playWatchItem,
                            onAllowWatchNext = ::requestWatchNext,
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
                        EditAppsScreen(all, hidden, version = vm.versionName, onToggle = vm::setHidden)
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
        vm.back()
    }
}
