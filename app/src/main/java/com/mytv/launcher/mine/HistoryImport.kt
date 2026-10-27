package com.mytv.launcher.mine

import android.content.Context
import com.mytv.launcher.ang.AngDiscovery
import com.mytv.launcher.ang.AngGuideBuilder
import com.mytv.launcher.ang.Rated
import com.mytv.launcher.ang.Rating
import com.mytv.launcher.ang.TasteStore
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * One-time import of the shows you've already watched (assets/my_history.json:
 * your IMDb list with your ratings, plus your Serialized history) into your
 * Watched list. 9s and 10s (and Serialized favourites) count as liked.
 * Never overwrites something already rated, here or on another TV.
 */
class HistoryImport(private val context: Context) {
    private val prefs = context.getSharedPreferences("history_import", Context.MODE_PRIVATE)

    val isDone: Boolean get() = prefs.getBoolean(DONE, false)

    /** Returns how many shows were added (0 if already done or TMDB isn't set up). */
    suspend fun run(store: TasteStore): Int = withContext(Dispatchers.IO) {
        if (isDone || !Tmdb.isConfigured) return@withContext 0
        val entries = runCatching {
            JSONArray(context.assets.open("my_history.json").bufferedReader().readText())
        }.getOrNull() ?: return@withContext 0
        val have = store.all()
        val haveTitles = have.map { AngGuideBuilder.normalize(it.title) }.toSet()
        val today = LocalDate.now()
        val gate = Semaphore(6)
        var failed = 0
        val found = (0 until entries.length()).map { entries.getJSONObject(it) }.map { e ->
            async {
                if (AngGuideBuilder.normalize(e.getString("title")) in haveTitles) return@async null
                val (key, show) = gate.withPermit { resolve(e) } ?: run { synchronized(this@HistoryImport) { failed++ }; return@async null }
                if (store.has(key)) return@async null
                val stars = e.optInt("stars", 0).takeIf { it in 1..10 }
                Rated(
                    key = key,
                    title = show.optString("name").ifBlank { e.getString("title") },
                    rating = if ((stars ?: 0) >= 9 || e.optBoolean("fav")) Rating.LIKE else Rating.WATCHED,
                    date = e.optString("rated").takeIf { it.isNotBlank() }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today,
                    tmdbId = show.getLong("id"),
                    imageUrl = show.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { "https://image.tmdb.org/t/p/w342$it" },
                    stars = stars,
                )
            }
        }.awaitAll().filterNotNull().distinctBy { it.key }
        found.forEach(store::put)
        // Mostly offline (most lookups failed)? Try again next time; otherwise it's done.
        if (failed < entries.length() / 3) prefs.edit().putBoolean(DONE, true).apply()
        found.size
    }

    /** IMDb id → TMDB show (exact); else search by title and year. */
    private suspend fun resolve(e: JSONObject): Pair<String, JSONObject>? {
        val imdb = e.optString("imdb")
        if (imdb.startsWith("tt")) {
            Tmdb.get("/find/$imdb?external_source=imdb_id&language=en-US") { json ->
                JSONObject(json).optJSONArray("tv_results")?.optJSONObject(0)
            }?.let { return AngDiscovery.key(it.getLong("id"), false) to it }
        }
        val title = e.getString("title")
        val year = e.optInt("year", 0)
        val want = e.optString("country")
        suspend fun search(withYear: Boolean) = Tmdb.get(
            AngDiscovery.searchPath(title, false) + if (withYear && year > 0) "&first_air_date_year=$year" else "",
        ) { json ->
            val results = JSONObject(json).optJSONArray("results") ?: return@get null
            val all = (0 until results.length()).map { results.getJSONObject(it) }
            fun origin(o: JSONObject) = o.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val norm = AngGuideBuilder.normalize(title)
            val named = all.filter { AngGuideBuilder.normalize(it.optString("name")) == norm || AngGuideBuilder.normalize(it.optString("original_name")) == norm }
            (named.firstOrNull { want.isNotBlank() && want in origin(it) } ?: named.firstOrNull() ?: all.firstOrNull().takeIf { withYear })
        }
        val show = search(true) ?: search(false) ?: return null
        return AngDiscovery.key(show.getLong("id"), false) to show
    }

    private companion object {
        const val DONE = "done_v1"
    }
}
