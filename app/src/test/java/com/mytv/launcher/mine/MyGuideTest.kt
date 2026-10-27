package com.mytv.launcher.mine

import com.mytv.launcher.ang.AngDiscovery
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.FollowedShow
import com.mytv.launcher.ang.Rated
import com.mytv.launcher.ang.Rating
import com.mytv.launcher.ang.WhereToWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MyGuideTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val available = { s: String? -> WhereToWatch.servicesFor(s).isNotEmpty() }

    private fun item(id: Long, title: String, genre: Int = 18, service: String = "Netflix", pop: Double = 10.0, date: LocalDate? = null, movie: Boolean = false) =
        AngItem(AngDiscovery.key(id, movie), title, "", null, service, date, null, pop, id, movie, 7.0, genres = listOf(genre))

    private fun build(
        ratings: List<Rated> = emptyList(),
        recs: List<AngItem> = emptyList(),
        liked: Map<String, Int> = emptyMap(),
        followed: List<FollowedShow> = emptyList(),
        upcoming: List<AngItem> = emptyList(),
        fresh: List<AngItem> = emptyList(),
    ) = MyGuideBuilder.build(today, ratings, recs, liked, emptyMap(), followed, upcoming, available, fresh)

    @Test
    fun newShowsAreFromTheLastYearAndMissedFrom2000On() {
        val g = build(
            ratings = listOf(Rated("tmdb:tv:50", "Seen", Rating.WATCHED, today)),
            recs = listOf(
                item(51, "Brand New", date = today.minusMonths(3)),
                item(52, "Last Decade", date = LocalDate.of(2019, 5, 1)),
                item(53, "Too Old", date = LocalDate.of(1995, 1, 1)),
                item(54, "Not Out Yet", date = today.plusMonths(2)),
                item(50, "Seen", date = today.minusMonths(1)),
            ),
            fresh = listOf(item(55, "Genre Pick", date = today.minusMonths(6)), item(56, "Not On Your Apps", service = "ITVX", date = today.minusMonths(2))),
        )
        assertEquals(setOf("Brand New", "Genre Pick"), g.newShows.map { it.title }.toSet())
        assertEquals(listOf("Last Decade"), g.missed.map { it.title })
    }

    @Test
    fun missedPrefersNewerShows() {
        val g = build(recs = listOf(item(61, "2003 Show", date = LocalDate.of(2003, 1, 1)), item(62, "2022 Show", date = LocalDate.of(2022, 1, 1))))
        assertEquals(listOf("2022 Show", "2003 Show"), g.missed.map { it.title })
    }

    @Test
    fun watchedTabListsWatchedAndLikedNewestFirst() {
        val g = build(
            ratings = listOf(
                Rated("tmdb:tv:1", "Old", Rating.WATCHED, today.minusDays(5)),
                Rated("tmdb:tv:2", "Fave", Rating.LIKE, today),
                Rated("tmdb:tv:3", "Nope", Rating.DISLIKE, today),
            ),
        )
        assertEquals(listOf("Fave", "Old"), g.watched.map { it.title })
        assertEquals("♥ Liked", g.watched.first().badge)
    }

    @Test
    fun watchingNowIsItsOwnListAndNotRecommended() {
        val g = build(
            ratings = listOf(
                Rated("tmdb:tv:7", "In Progress", Rating.WATCHING, today.minusDays(1), 7, false, null, "Hulu"),
                Rated("tmdb:tv:8", "Done", Rating.WATCHED, today),
            ),
            recs = listOf(item(7, "In Progress"), item(9, "Fresh")),
        )
        assertEquals(listOf("In Progress"), g.watching.map { it.title })
        assertEquals(today.minusDays(1), g.watching.single().date)
        assertEquals(listOf("Done"), g.watched.map { it.title })
        assertEquals(listOf("Fresh"), g.forYou.map { it.title })
    }

    @Test
    fun watchLaterIsItsOwnListAndLeavesSuggestions() {
        val g = build(
            ratings = listOf(Rated("tmdb:tv:11", "Someday", Rating.WANT, today, 11, false, null, "Netflix")),
            recs = listOf(item(11, "Someday"), item(12, "Other")),
        )
        assertEquals(listOf("Someday"), g.watchLater.map { it.title })
        assertEquals(listOf("Other"), g.forYou.map { it.title })
        assertTrue(g.watched.isEmpty())
    }

    @Test
    fun forYouSkipsWhatYouRatedAndAppsYouDontHave() {
        val g = build(
            ratings = listOf(Rated("tmdb:tv:1", "Seen", Rating.WATCHED, today)),
            recs = listOf(item(1, "Seen"), item(2, "New To You"), item(3, "ITVX Only", service = "ITVX")),
        )
        assertEquals(listOf("New To You"), g.forYou.map { it.title })
    }

    @Test
    fun forYouIsVariedAcrossGenres() {
        // 6 crime shows ranked first, then 2 comedies: no more than 4 of one genre per 20.
        val recs = (1..6).map { item(it.toLong(), "Crime $it", genre = 80, pop = 100.0 - it) } +
            (7..8).map { item(it.toLong(), "Comedy $it", genre = 35, pop = 10.0) }
        val g = build(recs = recs)
        val top8 = g.forYou.take(8).map { it.genres.first() }
        assertEquals(4, top8.take(6).count { it == 80 })
        assertTrue(g.forYou.take(6).any { it.genres.first() == 35 })
        assertEquals(8, g.forYou.size) // nothing dropped, just reordered
    }

    @Test
    fun newSeasonsAndComingSoon() {
        val watched = Rated("tmdb:tv:5", "Show", Rating.WATCHED, LocalDate.of(2026, 9, 1), 5)
        val base = AngItem("tmdb:tv:5", "Show", "", null, "Hulu", null, null, tmdbId = 5)
        val airing = FollowedShow(base, latestSeason = 3 to LocalDate.of(2026, 10, 10), nextSeason = null)
        val other = FollowedShow(base.copy(key = "tmdb:tv:6", title = "Other"), latestSeason = null, nextSeason = 2 to LocalDate.of(2027, 1, 5))
        val g = build(
            ratings = listOf(watched, watched.copy(key = "tmdb:tv:6", title = "Other")),
            followed = listOf(airing, other),
            upcoming = listOf(item(9, "Brand New", date = today.plusDays(20))),
        )
        assertEquals(listOf("Show"), g.newSeasons.map { it.title })
        assertEquals("Season 3 · Hulu", g.newSeasons.single().detail)
        assertEquals(listOf("Brand New", "Other"), g.comingSoon.map { it.title })
    }

    @Test
    fun paramountCountsAsAvailableThroughPrime() {
        assertTrue(available("Paramount+"))
        assertTrue(WhereToWatch.servicesFor("Paramount+").contains(com.mytv.launcher.sports.Service.PRIME_VIDEO))
    }

    @Test
    fun topGenresWeighted() {
        val recs = listOf(item(1, "a", genre = 80), item(2, "b", genre = 35), item(3, "c", genre = 35))
        assertEquals(listOf(80, 35), MyGuideBuilder.topGenres(recs, mapOf("tmdb:tv:1" to 5), n = 2))
    }
}
