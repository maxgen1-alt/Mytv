package com.mytv.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.ui.EditAppsScreen
import com.mytv.launcher.ui.HomeScreen
import com.mytv.launcher.ui.MyTvTheme
import com.mytv.launcher.ui.TeamScreen
import com.mytv.launcher.ui.TraktLinkScreen

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        HomeScreen(
                            apps = apps,
                            liveGames = live,
                            watching = watching,
                            busy = busy,
                            onApp = vm::launchApp,
                            onMoveApp = vm::moveApp,
                            onHideApp = { vm.setHidden(it, true) },
                            onEditApps = { vm.navigate(Screen.EditApps) },
                            onTeam = vm::onTeamPressed,
                            onTeamPage = { vm.navigate(Screen.TeamPage(it.key)) },
                            onWatchItem = vm::playWatchItem,
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
                        EditAppsScreen(all, hidden, onToggle = vm::setHidden)
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

    /** Pressing Home while we're already in front: jump back to the main screen. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        vm.back()
    }
}
