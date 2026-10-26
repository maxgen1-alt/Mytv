package com.mytv.launcher.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EspnParserTest {

    private val pl = Teams.arsenal.leagues.first()

    // Scoreboard shape: score is a string, logo is a string, broadcasts use names[].
    private val scoreboard = """
        {"events":[{"id":"1","date":"2026-10-26T15:30Z","competitions":[{
          "status":{"type":{"state":"in","shortDetail":"63'"}},
          "competitors":[
            {"homeAway":"home","score":"1","team":{"id":"359","shortDisplayName":"Arsenal","abbreviation":"ARS","logo":"https://x/359.png"}},
            {"homeAway":"away","score":"0","team":{"id":"363","shortDisplayName":"Chelsea","abbreviation":"CHE","logo":"https://x/363.png"}}],
          "broadcasts":[{"market":"national","names":["USA Net","Peacock"]}],
          "geoBroadcasts":[{"media":{"shortName":"Telemundo"}}]
        }]}]}
    """.trimIndent()

    // Team schedule shape: score is an object, logos[], broadcasts use media.shortName.
    private val schedule = """
        {"events":[{"id":"2","date":"2026-11-01T12:00:00Z","competitions":[{
          "status":{"type":{"state":"pre","shortDetail":"11/1 - 7:00 AM EST"}},
          "competitors":[
            {"homeAway":"home","score":{"displayValue":"0"},"team":{"id":"382","displayName":"Manchester City","logos":[{"href":"https://x/382.png"}]}},
            {"homeAway":"away","team":{"id":"359","displayName":"Arsenal","logos":[{"href":"https://x/359.png"}]}}],
          "broadcasts":[{"type":{"shortName":"TV"},"media":{"shortName":"NBC"}}]
        }]}]}
    """.trimIndent()

    @Test
    fun parsesScoreboardEvent() {
        val g = EspnParser.parseEvents(scoreboard, pl).single()
        assertEquals(GameState.LIVE, g.state)
        assertEquals("Chelsea", g.opponentOf("359").name)
        assertEquals("1", g.home.score)
        assertEquals(listOf("USA Net", "Peacock", "Telemundo"), g.broadcasts)
        assertEquals("63'", g.statusText)
    }

    @Test
    fun parsesScheduleEvent() {
        val g = EspnParser.parseEvents(schedule, pl).single()
        assertEquals(GameState.UPCOMING, g.state)
        assertEquals(false, g.isHome("359"))
        assertEquals("Manchester City", g.opponentOf("359").name)
        assertEquals("https://x/382.png", g.home.logoUrl)
        assertNull(g.away.score)
        assertEquals(listOf("NBC"), g.broadcasts)
    }

    @Test
    fun parsesDatesWithAndWithoutSeconds() {
        assertEquals(EspnParser.parseDate("2026-10-26T15:30:00Z"), EspnParser.parseDate("2026-10-26T15:30Z"))
        assertNotNull(EspnParser.parseDate("2026-10-26T15:30Z"))
        assertNull(EspnParser.parseDate(""))
    }

    @Test
    fun picksStandingsGroupContainingTeam() {
        val json = """
            {"children":[
              {"name":"Eastern Conference","standings":{"entries":[
                {"team":{"id":"2","displayName":"Celtics"},"stats":[{"name":"playoffSeed","value":1},{"name":"wins","displayValue":"5"}]}]}},
              {"name":"Western Conference","standings":{"entries":[
                {"team":{"id":"9","displayName":"Warriors"},"stats":[{"name":"playoffSeed","value":2},{"name":"wins","displayValue":"4"}]},
                {"team":{"id":"13","displayName":"Lakers"},"stats":[{"name":"playoffSeed","value":1},{"name":"wins","displayValue":"6"},{"name":"losses","displayValue":"1"}]}]}}
            ]}
        """.trimIndent()
        val s = EspnParser.parseStandings(json, Teams.lakers.standingsLeague, "13")!!
        assertEquals("Western Conference", s.title)
        assertEquals(listOf("Lakers", "Warriors"), s.rows.map { it.name })
        assertEquals("6", s.rows.first().values[s.columns.indexOf("W")])
    }
}
