package com.mytv.launcher.fantasy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScoringTest {
    private val cats = listOf(Category.PTS, Category.TO, Category.FG, Category.DD)

    @Test fun categoriesLikeFantrax() {
        val mine = StatLine.game(30, 12, 4, 1, 0, 5, 12, 20, 4, 5, 2, 6) // double-double
        val theirs = StatLine.game(25, 5, 11, 0, 1, 2, 10, 25, 3, 4, 2, 5) // 25 & 11: double-double
        val results = Scoring.compare(mine, theirs, cats)
        assertEquals(Outcome.WIN, results[0].outcome) // more points
        assertEquals(Outcome.LOSS, results[1].outcome) // more turnovers loses
        assertEquals(Outcome.WIN, results[2].outcome) // .600 beats .400
        assertEquals(".600", results[2].mine)
        assertEquals(Outcome.TIE, results[3].outcome) // one double-double each
        assertEquals("2-1-1", Scoring.record(results).toString())
    }

    @Test fun noShotsYetIsATie() {
        val r = Scoring.compare(StatLine(), StatLine(), listOf(Category.FT))
        assertEquals(Outcome.TIE, r.single().outcome)
        assertEquals("–", r.single().mine)
    }

    @Test fun namesMatchAcrossFantraxAndEspn() {
        assertEquals(BoxScore.nameKey("Luka Dončić"), BoxScore.nameKey("Doncic, Luka"))
        assertEquals(BoxScore.nameKey("Jaren Jackson Jr."), BoxScore.nameKey("Jackson, Jaren"))
        assertEquals(BoxScore.nameKey("Collin Murray-Boyles"), BoxScore.nameKey("Murray-Boyles, Collin"))
    }

    @Test fun readsEspnBoxScore() {
        val json = """{"boxscore":{"players":[{"team":{"abbreviation":"LAL"},"statistics":[{
            "labels":["MIN","PTS","FG","3PT","FT","REB","AST","TO","STL","BLK","OREB","DREB","PF","+/-"],
            "athletes":[{"athlete":{"displayName":"Luka Dončić"},"stats":["36","31","11-22","4-10","5-6","9","10","4","2","0","1","8","2","+7"]},
                        {"athlete":{"displayName":"Bench Guy"},"stats":[]}]}]}]}}"""
        val p = BoxScore.parse(json).single()
        assertEquals("LAL", p.team)
        assertEquals(31, p.line.pts)
        assertEquals(11, p.line.fgm); assertEquals(22, p.line.fga)
        assertEquals(4, p.line.tpm); assertEquals(10, p.line.tpa)
        assertEquals(1, p.line.doubles) // 31 points, 10 assists
        assertEquals(2, p.line.stl)
    }
}

/** Against a trimmed copy of the league's real public data (Oct 30, 2026). */
class FantraxDataTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val now = Instant.parse("2026-10-30T23:30:00Z").toEpochMilli()

    @Test fun thisWeek() {
        val w = FantraxData.week(res("fantrax_league_info.json"), "Midland Monarchs", now)!!
        assertEquals(2, w.period)
        assertEquals("Brighton Drunkers", w.opponentName)
        // The league's 11 categories.
        assertEquals(11, w.categories.size)
        assertTrue(Category.DD in w.categories && Category.TP in w.categories)
        // Week 2 started Oct 26 evening: league days 7..11 so far, today is 11.
        assertEquals(11, w.days.last().number)
        assertEquals(7, w.days.first().number)
    }

    @Test fun startersForADay() {
        val starters = FantraxData.starters(res("fantrax_rosters.json"), "jnfym0rnmtvr8qoq", res("fantrax_players.json"))
        assertEquals(10, starters.size)
        assertTrue(starters.any { it.name == "Luka Doncic" && it.nbaTeam == "LAL" })
    }
}
