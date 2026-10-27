package com.mytv.launcher.alerts

import android.content.Context
import android.content.Intent
import com.mytv.launcher.MainActivity
import com.mytv.launcher.sports.EspnClient
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.Team
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.system.MyTvSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Game pop-ups over whatever you're watching: "starts in 10 minutes" (OK
 * opens it), goals, and the final score (unless spoiler-free). Runs in the
 * always-on watch-tracking service, so it works while you're in other apps.
 */
class GameAlerts(private val context: Context, private val scope: CoroutineScope) {
    private val espn = EspnClient()
    private val settings = MyTvSettings(context)
    private val announced = HashSet<String>()
    private val lastScore = HashMap<String, String>()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var schedules = emptyMap<Team, List<Game>>()
            var fetchedAt = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - fetchedAt > 30 * 60_000L) {
                    schedules = Teams.all.associateWith { runCatching { espn.schedule(it) }.getOrDefault(emptyList()) }
                    fetchedAt = now
                }
                // Only poll while a game is close or on: otherwise check back in a while.
                val active = Teams.all.filter { t ->
                    schedules[t].orEmpty().any { g -> g.startMillis - now in -4 * 3600_000L..20 * 60_000L }
                }
                if (active.isEmpty() || !Overlay.canShow(context)) {
                    delay(10 * 60_000L)
                    continue
                }
                for (team in active) runCatching { check(team) }
                delay(60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun check(team: Team) {
        val game = espn.todaysGame(team) ?: return
        val them = game.opponentOf(team.espnId)
        val us = if (game.isHome(team.espnId)) game.home else game.away
        val accent = team.color.toInt()
        val vs = (if (game.isHome(team.espnId)) "vs " else "at ") + them.name
        val minutes = (game.startMillis - System.currentTimeMillis()) / 60_000L
        when (game.state) {
            GameState.UPCOMING -> if (minutes in 0..11 && settings.kickoffAlerts && announced.add("kick:${game.id}")) {
                Overlay.show(
                    context,
                    "${team.name} $vs starts in ${minutes.coerceAtLeast(1)} min",
                    "Press OK to watch · any other button to close",
                    accent,
                    seconds = 15,
                ) { watch(team) }
            }
            GameState.LIVE -> {
                val score = "${us.score ?: "0"}–${them.score ?: "0"}"
                val before = lastScore.put(game.id, score)
                if (!announced.add("live:${game.id}") && before != null && before != score && settings.scoreAlerts && team.standingsLeague.sport == "soccer") {
                    val weScored = (us.score?.toIntOrNull() ?: 0) > (before.substringBefore('–').toIntOrNull() ?: 0)
                    Overlay.show(
                        context,
                        if (weScored) "GOAL! ${team.name} $score ${them.name}" else "${them.name} score · ${team.name} $score",
                        game.league.displayName,
                        accent,
                    )
                }
            }
            GameState.FINISHED -> if (lastScore.containsKey(game.id) && settings.scoreAlerts && announced.add("final:${game.id}")) {
                Overlay.show(context, "Final: ${team.name} ${us.score ?: "0"}–${them.score ?: "0"} ${them.name}", game.league.displayName, accent)
            }
        }
    }

    /** Opens the game the same way the team button does. */
    private fun watch(team: Team) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_WATCH_TEAM, team.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }
}
