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
    fun mapsAppsToServices() {
        assertEquals(TopTenService.NETFLIX, TopTenService.forApp("com.netflix.ninja", "Netflix"))
        assertEquals(TopTenService.DISNEY, TopTenService.forApp("com.disney.disneyplus", "Disney+"))
        assertEquals(TopTenService.APPLE, TopTenService.forApp("com.apple.atve.androidtv.appletv", "Apple TV"))
        assertNull(TopTenService.forApp("com.google.android.youtube.tvunplugged", "YouTube TV"))
        assertNull(TopTenService.forApp("com.google.android.youtube.tv", "YouTube"))
    }
}
