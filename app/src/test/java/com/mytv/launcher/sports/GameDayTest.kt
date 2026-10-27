package com.mytv.launcher.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GameDayTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val nba = League("basketball", "nba", "NBA", emptyList())
    private val pl = League("soccer", "eng.1", "Premier League", emptyList())
    private fun at(h: Int, m: Int = 0, day: Int = 27) = LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun arsenal(start: Long, state: GameState = GameState.UPCOMING) = Game(
        "a$start", pl, start, state, "", Side("359", "Arsenal", "ARS", null, null), Side("1", "Chelsea", "CHE", null, null), emptyList(),
    )
    private fun lakers(start: Long, home: Boolean, state: GameState = GameState.UPCOMING): Game {
        val lal = Side("13", "Lakers", "LAL", null, null)
        val bos = Side("2", "Celtics", "BOS", null, null)
        return Game("l$start", nba, start, state, "", if (home) lal else bos, if (home) bos else lal, emptyList())
    }

    @Test fun noGamesNoTheme() = assertEquals(GameDayTheme.NONE, GameDay.theme(emptyMap(), at(9)))

    @Test fun lakersHomeIsYellowAwayIsPurple() {
        val home = mapOf("lakers" to TeamInfo(today = listOf(lakers(at(19), home = true))))
        val away = mapOf("lakers" to TeamInfo(today = listOf(lakers(at(19), home = false))))
        assertEquals(GameDayTheme.LAKERS_HOME, GameDay.theme(home, at(9)))
        assertEquals(GameDayTheme.LAKERS_AWAY, GameDay.theme(away, at(9)))
    }

    @Test fun bothPlayingArsenalFirstThenLakers() {
        val ars = arsenal(at(7, 30))
        val lal = lakers(at(19, 30), home = true)
        fun info(arsState: GameState, live: Game? = null) = mapOf(
            "arsenal" to TeamInfo(live = live, today = listOf(ars.copy(state = arsState))),
            "lakers" to TeamInfo(today = listOf(lal)),
        )
        assertEquals(GameDayTheme.ARSENAL, GameDay.theme(info(GameState.UPCOMING), at(6)))
        assertEquals(GameDayTheme.ARSENAL, GameDay.theme(info(GameState.LIVE, ars), at(8)))
        assertEquals(GameDayTheme.LAKERS_HOME, GameDay.theme(info(GameState.FINISHED), at(10)))
        // Schedule still says live long after the game, and the live check no longer finds it: it's over.
        assertEquals(GameDayTheme.LAKERS_HOME, GameDay.theme(info(GameState.LIVE), at(12)))
    }

    @Test fun lastGameColoursStayAfterItEnds() {
        val info = mapOf("arsenal" to TeamInfo(today = listOf(arsenal(at(7), GameState.FINISHED))))
        assertEquals(GameDayTheme.ARSENAL, GameDay.theme(info, at(21)))
    }

    @Test fun todayAndNext() {
        val games = listOf(arsenal(at(7, day = 26), GameState.FINISHED), arsenal(at(12)), arsenal(at(12, day = 30)))
        assertEquals(listOf(games[1]), GameDay.today(games, at(9), zone))
        assertEquals(games[1], GameDay.next(games, at(9)))
    }

    @Test fun whenText() {
        assertEquals("Today 7:30 PM", GameDay.whenText(at(19, 30), at(9), zone))
        assertEquals("Tomorrow 12:30 PM", GameDay.whenText(at(12, 30, day = 28), at(9), zone))
        assertEquals("Sat 3:00 PM", GameDay.whenText(at(15, day = 31), at(9), zone))
        assertEquals("Nov 14", GameDay.whenText(at(15, day = 14).let { LocalDateTime.of(2026, 11, 14, 15, 0).atZone(zone).toInstant().toEpochMilli() }, at(9), zone))
    }
}
