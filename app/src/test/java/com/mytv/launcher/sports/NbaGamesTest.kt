package com.mytv.launcher.sports

import org.junit.Assert.assertEquals
import org.junit.Test

class NbaGamesTest {
    private fun game(id: String, state: GameState, start: Long, away: String = "Celtics", home: String = "Lakers") = Game(
        id, Teams.nba, start, state, "", Side("h$id", home, "", null, null), Side("a$id", away, "", null, null), emptyList(),
    )

    @Test fun liveFirstThenUpcomingThenFinals() {
        val games = listOf(
            game("1", GameState.FINISHED, 100),
            game("2", GameState.UPCOMING, 300),
            game("3", GameState.LIVE, 200),
            game("4", GameState.UPCOMING, 250),
        )
        assertEquals(listOf("3", "4", "2", "1"), NbaGames.forDay(games).map { it.id })
    }

    @Test fun lastNightsGameStillOnShowsToday() {
        val today = listOf(game("1", GameState.UPCOMING, 500))
        val yesterday = listOf(game("9", GameState.LIVE, 100), game("8", GameState.FINISHED, 50))
        assertEquals(listOf("9", "1"), NbaGames.forDay(today, yesterday).map { it.id })
    }

    @Test fun matchesBothNicknames() {
        val g = game("1", GameState.LIVE, 0, away = "Trail Blazers", home = "Lakers")
        assertEquals(listOf("Blazers", "Lakers"), NbaGames.matchWords(g))
        assertEquals("Trail Blazers @ Lakers", NbaGames.title(g))
    }
}
