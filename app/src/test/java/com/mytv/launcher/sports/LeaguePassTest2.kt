package com.mytv.launcher.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Against a trimmed copy of the real League Pass channel page (Oct 30, 2026, 7:30 PM EDT). */
class LeaguePassPageTest {
    private val html = javaClass.classLoader!!.getResource("leaguepass_channel.html")!!.readText()
    private val cards = LeaguePass.parse(html)
    private val now = Instant.parse("2026-10-30T23:30:00Z").toEpochMilli()

    private fun game(away: String, home: String, start: String, state: GameState = GameState.UPCOMING) = Game(
        "g", Teams.nba, Instant.parse(start).toEpochMilli(), state, "",
        Side("h", home, "", null, null), Side("a", away, "", null, null), emptyList(),
    )

    @Test fun readsEveryGameCard() {
        assertEquals(6, cards.size)
        val hornets = cards.first { it.title == "Hornets vs. Wizards" }
        assertTrue(hornets.live)
        assertEquals("amzn1.dv.gti.87e39997-203f-402a-b831-b19a4712cf28", hornets.gti)
        assertEquals("Live at 7:30 PM EDT", cards.first { it.title == "Pistons vs. Nets" }.time)
    }

    @Test fun findsTonightsGameNotTheLastMeeting() {
        val tonight = LeaguePass.find(cards, game("Magic", "Raptors", "2026-10-30T23:30:00Z"), now)
        assertEquals("amzn1.dv.gti.db6fcef8-389a-4fa2-9c90-763c6f3b1fc8", tonight?.gti)
        val earlier = LeaguePass.find(cards, game("Magic", "Raptors", "2026-10-28T23:30:00Z", GameState.FINISHED), now)
        assertEquals("amzn1.dv.gti.d7407765-9e98-4154-be74-c5596a284194", earlier?.gti)
    }

    @Test fun liveGameMatchesTheLiveCard() {
        val g = LeaguePass.find(cards, game("Hornets", "Wizards", "2026-10-30T23:00:00Z", GameState.LIVE), now)
        assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.87e39997-203f-402a-b831-b19a4712cf28", g?.let(LeaguePass::link))
    }

    @Test fun datedCards() {
        val today = LocalDate.of(2026, 10, 30)
        assertEquals(LocalDate.of(2026, 11, 2), LeaguePass.cardDay(cards.first { it.title == "Suns vs. Warriors" }, today))
        assertEquals(LocalDate.of(2026, 10, 28), LeaguePass.cardDay(cards.first { it.title == "Lakers vs. Clippers" }, today))
    }

    @Test fun notOnLeaguePass() = assertNull(LeaguePass.find(cards, game("Lakers", "Warriors", "2026-10-31T02:00:00Z"), now))
}
