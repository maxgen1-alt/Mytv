package com.mytv.launcher.watching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {

    @Test fun episodeInSubtitle() {
        val p = NowPlaying.parse("Love Island", "S12:E5 Episode 5", "Peacock")!!
        assertEquals("Love Island", p.show)
        assertEquals(12, p.season)
        assertEquals(5, p.episode)
        assertEquals("Episode 5", p.episodeTitle)
    }

    @Test fun everythingInTitle() {
        val p = NowPlaying.parse("The Office: S3 E10: A Benihana Christmas", null, "Peacock")!!
        assertEquals("The Office", p.show)
        assertEquals(3, p.season)
        assertEquals(10, p.episode)
        assertEquals("A Benihana Christmas", p.episodeTitle)
    }

    @Test fun seasonEpisodeWords() {
        val p = NowPlaying.parse("Summer House", "Season 9, Episode 4 - Hamptons Hangover", "Bravo")!!
        assertEquals("Summer House", p.show)
        assertEquals(9, p.season)
        assertEquals(4, p.episode)
    }

    @Test fun titleIsEpisodeSubtitleIsShow() {
        val p = NowPlaying.parse("The One Where Ross Got High", "Friends", "Max")!!
        assertEquals("Friends", p.show)
        assertEquals("The One Where Ross Got High", p.episodeTitle)
    }

    @Test fun movieAndAppNameSubtitle() {
        val p = NowPlaying.parse("Anyone But You", "Netflix", "Netflix")!!
        assertEquals("Anyone But You", p.show)
        assertEquals(null, p.season)
        assertEquals(null, p.episodeTitle)
    }

    @Test fun filters() {
        assertFalse(NowPlaying.shouldTrack("com.google.android.youtube.tv"))
        assertFalse(NowPlaying.shouldTrack("com.spotify.tv.android"))
        assertTrue(NowPlaying.shouldTrack("com.netflix.ninja"))
        assertTrue(NowPlaying.tooShort(90_000))
        assertFalse(NowPlaying.tooShort(0))
        assertFalse(NowPlaying.tooShort(22 * 60_000L))
    }
}
