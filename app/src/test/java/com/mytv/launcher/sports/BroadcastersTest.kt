package com.mytv.launcher.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastersTest {

    private val peacock = InstalledApp("com.peacocktv.peacockandroid", "Peacock")
    private val yttv = InstalledApp("com.google.android.youtube.tvunplugged", "YouTube TV")
    private val espn = InstalledApp("com.espn.gtv", "ESPN")
    private val paramount = InstalledApp("com.cbs.ott", "Paramount+")
    private val netflix = InstalledApp("com.netflix.ninja", "Netflix")
    private val max = InstalledApp("com.wbd.stream", "Max")
    private val all = listOf(netflix, peacock, yttv, espn, paramount, max)

    private fun game(league: League, vararg networks: String) = Game(
        id = "1", league = league, startMillis = 0, state = GameState.LIVE, statusText = "",
        home = Side("359", "Arsenal", "ARS", null, null),
        away = Side("1", "Other", "OTH", null, null),
        broadcasts = networks.toList(),
    )

    private val pl = Teams.arsenal.leagues[0]
    private val ucl = Teams.arsenal.leagues[1]

    @Test
    fun peacockGameOpensPeacock() {
        val opts = Broadcasters.optionsFor(game(pl, "Peacock"), all)
        assertEquals(peacock, opts.first().app)
    }

    @Test
    fun cableChannelFallsBackToLiveTv() {
        val opts = Broadcasters.optionsFor(game(pl, "USA Net"), all)
        assertEquals(yttv, opts.first().app)
    }

    @Test
    fun specificNamesBeatGeneralOnes() {
        assertEquals(listOf(Service.ESPN), Broadcasters.servicesFor("ESPN+"))
        assertTrue(Service.PARAMOUNT in Broadcasters.servicesFor("CBSSN"))
    }

    @Test
    fun noBroadcastListedUsesCompetitionDefaults() {
        val opts = Broadcasters.optionsFor(game(ucl), all)
        assertEquals(paramount, opts.first().app)
    }

    @Test
    fun maxMatchesOnlyExactLabel() {
        assertTrue(Service.MAX.matches("com.wbd.stream", "Max"))
        assertTrue(!Service.MAX.matches("com.example.maxfit", "MaxFit"))
    }

    @Test
    fun nothingInstalledMeansNoOptions() {
        assertTrue(Broadcasters.optionsFor(game(pl, "Peacock"), listOf(netflix)).isEmpty())
    }
}
