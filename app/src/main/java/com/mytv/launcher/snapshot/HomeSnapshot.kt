package com.mytv.launcher.snapshot

import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.mine.MyGuide
import com.mytv.launcher.topten.TopTenService
import com.mytv.launcher.topten.TopTitle
import com.mytv.launcher.watching.WatchItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * The Home screen as it last looked (Continue Watching, your rows, Popular),
 * saved to the TV so MyTV shows it instantly after a reboot or an update
 * while the fresh data loads. Also what the screensaver shows.
 */
class HomeSnapshot(dir: File) {
    private val file = File(dir, "home-snapshot.json")

    data class Data(
        val watching: List<WatchItem> = emptyList(),
        val guide: MyGuide = MyGuide(),
        val topTen: Map<TopTenService, List<TopTitle>> = emptyMap(),
    )

    fun load(): Data? = runCatching {
        val o = JSONObject(file.readText())
        val g = o.getJSONObject("guide")
        fun items(name: String) = g.optJSONArray(name)?.let { a -> (0 until a.length()).map { item(a.getJSONObject(it)) } }.orEmpty()
        Data(
            watching = o.optJSONArray("watching")?.let { a -> (0 until a.length()).map { watch(a.getJSONObject(it)) } }.orEmpty(),
            guide = MyGuide(
                watching = items("watching"), watchLater = items("watchLater"), tonight = items("tonight"),
                newShows = items("newShows"), missed = items("missed"), newMovies = items("newMovies"), missedMovies = items("missedMovies"),
            ),
            topTen = o.optJSONObject("topTen")?.let { t ->
                t.keys().asSequence().mapNotNull { k ->
                    val service = runCatching { TopTenService.valueOf(k) }.getOrNull() ?: return@mapNotNull null
                    val a = t.getJSONArray(k)
                    service to (0 until a.length()).map { top(a.getJSONObject(it)) }
                }.toMap()
            }.orEmpty(),
        )
    }.getOrNull()

    fun save(d: Data) {
        val g = JSONObject()
        fun put(name: String, list: List<AngItem>) = g.put(name, JSONArray().apply { list.take(30).forEach { put(item(it)) } })
        put("watching", d.guide.watching); put("watchLater", d.guide.watchLater); put("tonight", d.guide.tonight)
        put("newShows", d.guide.newShows); put("missed", d.guide.missed)
        put("newMovies", d.guide.newMovies); put("missedMovies", d.guide.missedMovies)
        val o = JSONObject()
            .put("watching", JSONArray().apply { d.watching.take(30).forEach { put(watch(it)) } })
            .put("guide", g)
            .put("topTen", JSONObject().apply { d.topTen.forEach { (s, l) -> put(s.name, JSONArray().apply { l.forEach { put(top(it)) } }) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    /** Wide pictures for the screensaver: backdrops of what you're watching and your picks. */
    fun backdrops(d: Data): List<Pair<String, String>> {
        val fromRows = (d.guide.tonight + d.guide.watching + d.guide.newShows + d.guide.newMovies + d.guide.watchLater + d.guide.missed)
            .mapNotNull { i -> i.backdropUrl?.let { it to i.title } }
        val fromWatching = d.watching.mapNotNull { w -> w.imageUrl?.takeIf { it.startsWith("http") }?.let { it to w.title } }
        val fromTop = d.topTen.values.flatten().mapNotNull { t -> t.backdropUrl?.let { it to t.title } }
        return (fromWatching + fromRows + fromTop).distinctBy { it.first }
    }

    // ---- JSON ----

    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }
    private fun date(s: String?) = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun item(i: AngItem) = JSONObject()
        .put("key", i.key).put("title", i.title).put("detail", i.detail).put("img", i.imageUrl ?: "")
        .put("service", i.service ?: "").put("date", i.date?.toString() ?: "").put("badge", i.badge ?: "")
        .put("tmdb", i.tmdbId ?: -1).put("movie", i.isMovie).put("vote", i.voteAverage).put("imdb", i.imdbId ?: "")
        .put("overview", i.overview.take(400)).put("backdrop", i.backdropUrl ?: "")

    private fun item(o: JSONObject) = AngItem(
        key = o.getString("key"), title = o.getString("title"), detail = o.optString("detail"), imageUrl = o.str("img"),
        service = o.str("service"), date = date(o.str("date")), badge = o.str("badge"),
        tmdbId = o.optLong("tmdb", -1).takeIf { it > 0 }, isMovie = o.optBoolean("movie"), voteAverage = o.optDouble("vote", 0.0),
        imdbId = o.str("imdb"), overview = o.optString("overview"), backdropUrl = o.str("backdrop"),
    )

    private fun watch(w: WatchItem) = JSONObject()
        .put("key", w.key).put("title", w.title).put("sub", w.subtitle).put("img", w.imageUrl ?: "")
        .put("progress", w.progress?.toDouble() ?: -1.0).put("at", w.lastWatchedMillis).put("q", w.searchQuery)
        .put("source", w.source).put("pkg", w.packageName ?: "").put("intent", w.intentUri ?: "")

    private fun watch(o: JSONObject) = WatchItem(
        key = o.getString("key"), title = o.getString("title"), subtitle = o.optString("sub"), imageUrl = o.str("img"),
        progress = o.optDouble("progress", -1.0).takeIf { it >= 0 }?.toFloat(), lastWatchedMillis = o.optLong("at"),
        searchQuery = o.optString("q"), source = o.optString("source"), packageName = o.str("pkg"), intentUri = o.str("intent"),
    )

    private fun top(t: TopTitle) = JSONObject()
        .put("id", t.id).put("title", t.title).put("poster", t.posterUrl ?: "").put("backdrop", t.backdropUrl ?: "")
        .put("movie", t.isMovie).put("pop", t.popularity).put("released", t.released?.toString() ?: "")
        .put("score", t.score).put("vote", t.voteAverage)

    private fun top(o: JSONObject) = TopTitle(
        id = o.getLong("id"), title = o.getString("title"), posterUrl = o.str("poster"), backdropUrl = o.str("backdrop"),
        isMovie = o.optBoolean("movie"), popularity = o.optDouble("pop", 0.0), released = date(o.str("released")),
        score = o.optDouble("score", 0.0), voteAverage = o.optDouble("vote", 0.0),
    )
}
