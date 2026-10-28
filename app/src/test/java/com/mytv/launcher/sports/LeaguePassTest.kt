package com.mytv.launcher.sports

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaguePassTest {
    private val nba = Teams.lakers.leagues.first()
    private fun game(vararg networks: String) = Game(
        "1", nba, 0L, GameState.LIVE, "", Side("13", "Lakers", "LAL", null, null), Side("2", "Celtics", "BOS", null, null), networks.toList(),
    )
    private val apps = listOf(
        InstalledApp("com.amazon.amazonvideo.livingroom", "Prime Video"),
        InstalledApp("com.peacocktv.peacockandroid", "Peacock"),
        InstalledApp("com.espn.gtv", "ESPN"),
    )

    @Test fun localGamesGoToLeaguePassOnPrime() =
        assertEquals("Prime Video", Broadcasters.optionsFor(game("Spectrum SN"), apps).first().app.label)

    @Test fun nationalGamesStayWithTheirNetwork() {
        assertEquals("Peacock", Broadcasters.optionsFor(game("Peacock"), apps).first().app.label)
        assertEquals("ESPN", Broadcasters.optionsFor(game("ESPN"), apps).first().app.label)
    }

    @Test fun unknownBroadcasterFallsBackToLeaguePass() =
        assertEquals("Prime Video", Broadcasters.optionsFor(game(), apps).first().app.label)
}
