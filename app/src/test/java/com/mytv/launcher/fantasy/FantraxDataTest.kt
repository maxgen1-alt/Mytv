package com.mytv.launcher.fantasy

import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.Side
import com.mytv.launcher.sports.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Against a trimmed copy of the league's real public data (Oct 30, 2026). */
class FantraxDataTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val now = Instant.parse("2026-10-30T23:30:00Z").toEpochMilli()
    private val week = FantraxData.week(res("fantrax_league_info.json"), res("fantrax_rosters.json"), res("fantrax_players.json"), "Midland Monarchs", now)!!

    @Test fun thisWeeksOpponent() {
        assertEquals("Midland Monarchs", week.myTeam)
        // Week 2 (Oct 26 - Nov 2): at Brighton Drunkers.
        assertEquals("Brighton Drunkers", week.opponent)
    }

    @Test fun startersOnly() {
        assertEquals(10, week.mine.size)
        assertEquals(10, week.theirs.size)
        val luka = week.mine.first { it.name == "Luka Doncic" }
        assertEquals("LAL", luka.nbaTeam)
        assertTrue(week.mine.none { it.name == "Zion Williamson" }) // on the bench
    }

    @Test fun whoIsPlayingNow() {
        fun g(away: String, home: String, state: GameState) =
            Game(away + home, Teams.nba, now, state, "", Side("h", home, home, null, null), Side("a", away, away, null, null), emptyList())
        // Fantrax's PHO is ESPN's PHX.
        val live = FantraxData.liveNow(week, listOf(g("PHX", "LAL", GameState.LIVE), g("DAL", "MEM", GameState.UPCOMING)))
        assertTrue(live.any { it.player.name == "Luka Doncic" && it.mine })
        assertTrue(live.any { it.player.name == "Jalen Green" && it.mine })
        assertTrue(live.none { it.player.nbaTeam == "DAL" || it.player.nbaTeam == "MEM" })
    }

    @Test fun fantraxTimes() =
        assertEquals(Instant.parse("2026-10-26T23:00:00Z").toEpochMilli(), FantraxData.time("2026-10-26T19:00:00.0-0400"))
}
