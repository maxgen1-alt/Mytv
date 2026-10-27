package com.mytv.launcher.sports

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The whole app's background on a game day. */
enum class GameDayTheme { NONE, ARSENAL, LAKERS_HOME, LAKERS_AWAY }

/** What the team buttons show: the game on now, and the next one. */
data class TeamInfo(val live: Game? = null, val next: Game? = null, val today: List<Game> = emptyList())

object GameDay {

    /**
     * Today's theme: the team whose game today is on now or next. Once it's
     * over the next game today takes over (Arsenal in the morning, Lakers at
     * night); after the last one, its colours stay for the rest of the day.
     */
    fun theme(info: Map<String, TeamInfo>, now: Long): GameDayTheme {
        val games = info.flatMap { (key, i) ->
            i.today.map { g -> Triple(key, g, i.live?.id == g.id) }
        }.sortedBy { it.second.startMillis }
        if (games.isEmpty()) return GameDayTheme.NONE
        // Not live and started 3h+ ago counts as over, even if the schedule hasn't caught up yet.
        fun over(g: Game, live: Boolean) = !live && (g.state == GameState.FINISHED || now - g.startMillis > 3 * 3600_000L)
        val (key, game) = games.firstOrNull { (_, g, live) -> !over(g, live) }
            ?.let { it.first to it.second } ?: games.last().let { it.first to it.second }
        return when (key) {
            Teams.arsenal.key -> GameDayTheme.ARSENAL
            Teams.lakers.key -> if (game.isHome(Teams.lakers.espnId)) GameDayTheme.LAKERS_HOME else GameDayTheme.LAKERS_AWAY
            else -> GameDayTheme.NONE
        }
    }

    /** The games of [all] that start today in [zone], or are still on from last night. */
    fun today(all: List<Game>, now: Long, zone: ZoneId): List<Game> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return all.filter { g ->
            Instant.ofEpochMilli(g.startMillis).atZone(zone).toLocalDate() == day ||
                g.state == GameState.LIVE && g.startMillis <= now
        }
    }

    /** The next game that isn't over (or the one on now). */
    fun next(all: List<Game>, now: Long): Game? =
        all.filter { it.state != GameState.FINISHED && it.startMillis > now - 4 * 3600_000L }.minByOrNull { it.startMillis }

    /** "Today 7:30 PM", "Tomorrow 12:30 PM", "Sat 3:00 PM", or "Nov 14" for later. */
    fun whenText(startMillis: Long, now: Long, zone: ZoneId, locale: Locale = Locale.US): String {
        val start: ZonedDateTime = Instant.ofEpochMilli(startMillis).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), start.toLocalDate())
        val time = start.format(DateTimeFormatter.ofPattern("h:mm a", locale))
        return when {
            days == 0L -> "Today $time"
            days == 1L -> "Tomorrow $time"
            days in 2..6 -> start.format(DateTimeFormatter.ofPattern("EEE", locale)) + " $time"
            else -> start.format(DateTimeFormatter.ofPattern("MMM d", locale))
        }
    }
}
