package com.mytv.launcher.fantasy

import com.mytv.launcher.sports.NbaGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FantraxParserTest {
    private val live = """
        {"responses":[{"data":{
          "matchups":["tm1_tm2","tm3_tm4"],
          "statsPerTeam":{"allTeamsStats":{
            "tm1":{"ACTIVE":{"totalFpts":101.5,"statsMap":{"_total":{"object1":101.5},"p1":{"object1":40.5},"p2":{"object1":61}}}},
            "tm2":{"ACTIVE":{"statsMap":{"p3":{"object1":"55.5"},"p4":{"object1":30}}}}
          }},
          "gameStatusMap":{"p1":"@BOS 88-90 Q3 5:21|401|2","p2":"LAL 110 @ DEN 104 F|402|3","p3":"@MIA~1700000000000|403|1"},
          "scorerMap":{"a":{"b":{"c":[{"scorer":{"scorerId":"p1","name":"James, LeBron","teamShortName":"LAL","posShortNames":"SF"}}]}}}
        }}]}
    """.trimIndent()
    private val info = """{"matchups":[{"period":1,"matchupList":[
        {"home":{"id":"tm1","name":"Midland Monarchs"},"away":{"id":"tm2","name":"Odessa Oilers"}}]}]}"""
    private val players = """{"p2":{"name":"Jokic, Nikola","team":"DEN","position":"C"},"p3":{"name":"Butler, Jimmy","team":"GSW","position":"SF"}}"""

    @Test fun readsMyMatchup() {
        val r = FantraxParser.parse(live, info, players, "Midland Monarchs") as FantasyResult.Ok
        val m = r.matchup
        assertEquals("Midland Monarchs", m.mine.name)
        assertEquals("Odessa Oilers", m.them.name)
        assertEquals(101.5, m.mine.points, 0.01)
        // No total listed: the players add up.
        assertEquals(85.5, m.them.points, 0.01)
        val lebron = m.mine.players.first()
        assertEquals("LeBron James", lebron.name)
        assertEquals("LAL", lebron.nbaTeam)
        assertEquals(FState.LIVE, lebron.state)
        assertEquals("@BOS 88-90 Q3 5:21", lebron.status)
        assertEquals(FState.FINAL, m.mine.players[1].state)
        assertEquals("Nikola Jokic", m.mine.players[1].name)
        val butler = m.them.players.first { it.id == "p3" }
        assertEquals(FState.UPCOMING, butler.state)
        assertTrue(butler.status.startsWith("@MIA "))
    }

    @Test fun notSignedInSaysSo() {
        val r = FantraxParser.parse("""{"pageError":{"code":"WARNING_NOT_LOGGED_IN"}}""", null, null, "Midland Monarchs")
        assertTrue((r as FantasyResult.Error).message.contains("signed-in"))
    }

    @Test fun fantraxAbbreviationsFindEspnGames() {
        assertEquals("GS", NbaGames.espnAbbr("GSW"))
        assertEquals("PHX", NbaGames.espnAbbr("pho"))
        assertEquals("LAL", NbaGames.espnAbbr("LAL"))
    }
}
