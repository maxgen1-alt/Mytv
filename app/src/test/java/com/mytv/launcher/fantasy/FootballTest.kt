package com.mytv.launcher.fantasy

import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.Side
import com.mytv.launcher.sports.Teams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against a trimmed copy of the league's real ESPN data (week 8, 2026). */
class FootballTest {
    private val json = javaClass.classLoader!!.getResource("espn_fantasy_football.json")!!.readText()
    private fun game(home: String, away: String, state: GameState) =
        Game(home + away, Teams.nfl, 0L, state, "Q2 5:21", Side(home, "H", "H", null, "7"), Side(away, "A", "A", null, "3"), listOf("CBS"))

    @Test fun myMatchup() {
        val m = FootballData.parse(json, "Skattebros", emptyList())!!
        assertEquals(8, m.week)
        assertEquals("The Skattebros", m.mine.name)
        assertEquals("BrockBuster", m.theirs.name)
        assertEquals(17.4, m.theirs.points, 0.01)
        assertEquals(0.44, m.mine.winChance!!, 0.001)
        assertTrue(m.mine.logo!!.endsWith(".svg"))
    }

    @Test fun startersInLineupOrderBenchLeftOut() {
        val m = FootballData.parse(json, "Skattebros", emptyList())!!
        val starters = m.mine.starters
        assertEquals("QB", starters.first().slot)
        assertEquals("Lamar Jackson", starters.first().name)
        assertTrue(starters.none { it.name == "Geno Smith" }) // on the bench
        assertTrue(starters.none { it.name == "DeVonta Smith" }) // IR
        assertEquals(listOf("QB", "RB", "RB", "WR", "WR", "WR", "TE", "FLEX", "D/ST", "K"), starters.map { it.slot })
        assertEquals(22.2, starters.first().projected!!, 0.05)
    }

    @Test fun livePlayersFindTheirGame() {
        // Lamar Jackson: Baltimore (ESPN id 33) at Buffalo (2), on now.
        val m = FootballData.parse(json, "Skattebros", listOf(game("2", "33", GameState.LIVE)))!!
        val lamar = m.mine.starters.first()
        assertTrue(lamar.live)
        assertEquals(listOf("Lamar Jackson"), m.live.filter { it.second }.map { it.first.name })
    }
}
