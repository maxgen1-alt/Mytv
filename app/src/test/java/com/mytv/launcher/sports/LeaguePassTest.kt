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

class NbcTest {
    @Test fun nbcGoesToYouTubeTv() {
        val pl = Teams.arsenal.leagues.first()
        val g = Game("2", pl, 0L, GameState.LIVE, "", Side("359", "Arsenal", "ARS", null, null), Side("1", "Chelsea", "CHE", null, null), listOf("NBC"))
        val apps = listOf(InstalledApp("com.peacocktv.peacockandroid", "Peacock"), InstalledApp("com.google.android.youtube.tvunplugged", "YouTube TV"))
        assertEquals("YouTube TV", Broadcasters.optionsFor(g, apps).first().app.label)
    }
}
