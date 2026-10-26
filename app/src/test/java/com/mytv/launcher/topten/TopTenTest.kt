package com.mytv.launcher.topten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopTenTest {

    @Test
    fun parsesAndMergesShowsAndMovies() {
        val tv = """{"results":[
            {"id":1,"name":"Big Show","poster_path":"/a.jpg","backdrop_path":"/b.jpg","popularity":90.5,"genre_ids":[18]},
            {"id":2,"name":"Daily Talk","poster_path":"/c.jpg","popularity":500,"genre_ids":[10767]},
            {"id":3,"name":"No Poster","poster_path":null,"popularity":80}]}"""
        val movies = """{"results":[{"id":1,"title":"Hit Movie","poster_path":"/m.jpg","backdrop_path":null,"popularity":120}]}"""

        val shows = TmdbParser.parseDiscover(tv, isMovie = false)
        assertEquals(listOf("Big Show"), shows.map { it.title })
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", shows[0].posterUrl)

        val top = TmdbParser.topTen(listOf(shows, TmdbParser.parseDiscover(movies, isMovie = true)))
        // Same id, different type: both kept, ranked by popularity.
        assertEquals(listOf("Hit Movie", "Big Show"), top.map { it.title })
        assertNull(top[0].backdropUrl)
    }

    @Test
    fun newAndTrendingBeatsOldPopular() {
        val today = java.time.LocalDate.of(2026, 10, 26)
        val old = TopTitle(1, "Old Hit", null, null, isMovie = false, popularity = 300.0, released = today.minusYears(8))
        val fresh = TopTitle(2, "New Drop", null, null, isMovie = false, popularity = 120.0, released = today.minusDays(5))
        val trendy = TopTitle(3, "Buzzy Movie", null, null, isMovie = true, popularity = 90.0, released = today.minusDays(60))
        val trending = TmdbParser.parseTrending(
            """{"results":[{"id":3,"media_type":"movie"},{"id":2,"media_type":"tv"},{"id":9,"media_type":"person"}]}""",
        )
        assertEquals(listOf("movie:3", "tv:2"), trending)
        val top = TmdbParser.topTen(listOf(listOf(old, fresh, trendy)), trending, today)
        assertEquals(listOf("New Drop", "Buzzy Movie", "Old Hit"), top.map { it.title })
    }

    @Test
    fun mergesAcrossServices() {
        fun t(id: Long, score: Double) = TopTitle(id, "T$id", null, null, isMovie = false, popularity = 0.0, score = score)
        val merged = TmdbParser.acrossServices(
            mapOf(
                TopTenService.NETFLIX to listOf(t(1, 50.0), t(2, 10.0)),
                TopTenService.HULU to listOf(t(3, 30.0), t(2, 40.0)),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.second.id })
        // Title 2 is on both; it's listed under Hulu, where it scores higher.
        assertEquals(TopTenService.HULU, merged[1].first)
    }

    @Test
    fun mapsAppsToServices() {
        assertEquals(TopTenService.NETFLIX, TopTenService.forApp("com.netflix.ninja", "Netflix"))
        assertEquals(TopTenService.DISNEY, TopTenService.forApp("com.disney.disneyplus", "Disney+"))
        assertEquals(TopTenService.APPLE, TopTenService.forApp("com.apple.atve.androidtv.appletv", "Apple TV"))
        assertNull(TopTenService.forApp("com.google.android.youtube.tvunplugged", "YouTube TV"))
        assertNull(TopTenService.forApp("com.google.android.youtube.tv", "YouTube"))
    }
}
