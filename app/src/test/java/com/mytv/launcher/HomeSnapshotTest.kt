package com.mytv.launcher

import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.mine.MyGuide
import com.mytv.launcher.snapshot.HomeSnapshot
import com.mytv.launcher.topten.TopTenService
import com.mytv.launcher.topten.TopTitle
import com.mytv.launcher.watching.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class HomeSnapshotTest {
    @Test fun roundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "snap-" + System.nanoTime()).apply { mkdirs() }
        val item = AngItem("tmdb:tv:1", "The Pitt", "2025 · Max", "https://p/1.jpg", "Max", LocalDate.of(2025, 1, 9), "New show",
            12.0, 1, false, 8.7, "tt1", listOf(18), "ER drama", "https://b/1.jpg")
        val data = HomeSnapshot.Data(
            watching = listOf(WatchItem("wn:x", "Severance", "Apple TV · S2 · E3", "https://t.jpg", 0.4f, 99L, "Severance", "Apple TV", "com.apple", "intent:#x")),
            guide = MyGuide(newShows = listOf(item), tonight = listOf(item.copy(key = "tmdb:tv:2"))),
            topTen = mapOf(TopTenService.NETFLIX to listOf(TopTitle(5, "Wednesday", null, "https://b/5.jpg", false, 3.0, null, 1.0, 8.1))),
        )
        val snap = HomeSnapshot(dir)
        snap.save(data)
        val back = snap.load()!!
        assertEquals(data.watching, back.watching)
        assertEquals(listOf(item.copy(genres = emptyList(), popularity = 0.0)), back.guide.newShows)
        assertEquals(data.topTen, back.topTen)
        assertEquals(listOf("https://t.jpg", "https://b/1.jpg", "https://b/5.jpg"), snap.backdrops(back).map { it.first })
    }
}
