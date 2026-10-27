package com.mytv.launcher.mine

import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.Episode
import com.mytv.launcher.ang.FollowedShow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TonightTest {
    private val today = LocalDate.of(2026, 10, 27) // a Tuesday
    private fun show(title: String, last: LocalDate?, next: LocalDate?) = FollowedShow(
        AngItem("tmdb:tv:${title.hashCode()}", title, "", null, "Peacock", null, null),
        null, null,
        last?.let { Episode(2, 4, it, "The Recoupling") },
        next?.let { Episode(2, 5, it, "Episode 5") },
    )

    @Test fun tonightRow() {
        val row = Tonight.row(
            listOf(
                show("Love Island", today.minusDays(1), today),
                show("Old", today.minusDays(9), null),
                show("Dropped Last Night", today.minusDays(1), today.plusDays(7)),
            ),
            today,
        )
        assertEquals(listOf("Love Island", "Dropped Last Night"), row.map { it.title })
        assertEquals("New tonight", row[0].badge)
        assertEquals("S2 E5 · Peacock", row[0].detail)
        assertEquals("S2 E4 · The Recoupling · Peacock", row[1].detail)
    }

    @Test fun labels() {
        assertEquals("New episode tonight", Tonight.label(show("a", null, today), today))
        assertEquals("New episode Thursday", Tonight.label(show("b", null, today.plusDays(2)), today))
        assertEquals(null, Tonight.label(show("c", null, today.plusDays(9)), today))
    }
}
