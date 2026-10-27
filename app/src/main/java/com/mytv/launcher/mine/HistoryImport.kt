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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * One-time imports of what you've already watched into My Watchlist:
 * - assets/my_history.json: shows (your IMDb list with your ratings, plus your Serialized history);
 * - assets/my_movies.json: your Letterboxd films (ratings ×2 → 1-10) and watchlist (→ Watch Later).
 * 9s and 10s (and Serialized favourites) count as liked. Never overwrites
 * something already rated, here or on another TV.
 */
class HistoryImport(private val context: Context) {
    private val prefs = context.getSharedPreferences("history_import", Context.MODE_PRIVATE)

    private data class Source(val asset: String, val doneKey: String)

    private val sources = listOf(
        Source("my_history.json", "done_v1"),
        Source("my_movies.json", "done_movies_v1"),
    )

    val isDone: Boolean get() = sources.all { prefs.getBoolean(it.doneKey, false) }

    /** Returns how many titles were added (0 if already done or TMDB isn't set up). */
    suspend fun run(store: TasteStore): Int {
        if (!Tmdb.isConfigured) return 0
        var added = 0
        for (s in sources) if (!prefs.getBoolean(s.doneKey, false)) added += runOne(store, s)
        return added
    }

    private suspend fun runOne(store: TasteStore, source: Source): Int = withContext(Dispatchers.IO) {
        val entries = runCatching {
            JSONArray(context.assets.open(source.asset).bufferedReader().readText())
        }.getOrNull() ?: return@withContext 0
        val have = store.all()
        val haveTitles = have.map { AngGuideBuilder.normalize(it.title) }.toSet()
        val today = LocalDate.now()
        var failed = 0
        // Tmdb.get already limits how many requests run at once.
        val found = (0 until entries.length()).map { entries.getJSONObject(it) }.map { e ->
            async {
                if (AngGuideBuilder.normalize(e.getString("title")) in haveTitles) return@async null
                val hit = resolve(e) ?: run { synchronized(this@HistoryImport) { failed++ }; return@async null }
                if (store.has(hit.key)) return@async null
                val stars = e.optInt("stars", 0).takeIf { it in 1..10 }
                Rated(
                    key = hit.key,
                    title = hit.json.optString(if (hit.isMovie) "title" else "name").ifBlank { e.getString("title") },
                    rating = when {
                        e.optString("list") == "watchlist" -> Rating.WANT
                        (stars ?: 0) >= 9 || e.optBoolean("fav") -> Rating.LIKE
                        else -> Rating.WATCHED
                    },
                    date = e.optString("rated").takeIf { it.isNotBlank() }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today,
                    tmdbId = hit.json.getLong("id"),
                    isMovie = hit.isMovie,
                    imageUrl = hit.json.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { "https://image.tmdb.org/t/p/w342$it" },
                    stars = stars,
                )
            }
        }.awaitAll().filterNotNull().distinctBy { it.key }
        found.forEach(store::put)
        // Mostly offline (most lookups failed)? Try again next time; otherwise it's done.
        if (failed < entries.length() / 3) prefs.edit().putBoolean(source.doneKey, true).apply()
        found.size
    }

    private class Hit(val key: String, val json: JSONObject, val isMovie: Boolean)

    /**
     * IMDb id → TMDB (exact). Letterboxd titles are films unless only a show
     * by that name exists that year (e.g. a mini-series); otherwise shows,
     * searched by title and year.
     */
    private suspend fun resolve(e: JSONObject): Hit? {
        val imdb = e.optString("imdb")
        if (imdb.startsWith("tt")) {
            Tmdb.get("/find/$imdb?external_source=imdb_id&language=en-US") { json ->
                val o = JSONObject(json)
                o.optJSONArray("tv_results")?.optJSONObject(0)?.let { Hit(AngDiscovery.key(it.getLong("id"), false), it, false) }
                    ?: o.optJSONArray("movie_results")?.optJSONObject(0)?.let { Hit(AngDiscovery.key(it.getLong("id"), true), it, true) }
            }?.let { return it }
        }
        val title = e.getString("title")
        val year = e.optInt("year", 0)
        if (e.optString("kind") == "movie") {
            val movie = search(title, year, e.optString("country"), isMovie = true, exactOnly = true)
            val show = search(title, year, "", isMovie = false, exactOnly = true)
            return when {
                movie != null && show != null ->
                    if (show.optInt("vote_count") > movie.optInt("vote_count")) hit(show, false) else hit(movie, true)
                movie != null -> hit(movie, true)
                show != null -> hit(show, false)
                else -> search(title, year, "", isMovie = true, exactOnly = false)?.let { hit(it, true) }
            }
        }
        return search(title, year, e.optString("country"), isMovie = false, exactOnly = false)?.let { hit(it, false) }
    }

    private fun hit(o: JSONObject, isMovie: Boolean) = Hit(AngDiscovery.key(o.getLong("id"), isMovie), o, isMovie)

    /** Best match by name for [title] in [year] (falls back to any year); [want] = preferred origin country. */
    private suspend fun search(title: String, year: Int, want: String, isMovie: Boolean, exactOnly: Boolean): JSONObject? {
        val yearParam = if (isMovie) "primary_release_year" else "first_air_date_year"
        suspend fun once(withYear: Boolean) = Tmdb.get(
            AngDiscovery.searchPath(title, isMovie) + if (withYear && year > 0) "&$yearParam=$year" else "",
        ) { json ->
            val results = JSONObject(json).optJSONArray("results") ?: return@get null
            val all = (0 until results.length()).map { results.getJSONObject(it) }
            fun origin(o: JSONObject) = o.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val norm = AngGuideBuilder.normalize(title)
            val nameKeys = if (isMovie) listOf("title", "original_title") else listOf("name", "original_name")
            val named = all.filter { o -> nameKeys.any { AngGuideBuilder.normalize(o.optString(it)) == norm } }
            named.firstOrNull { want.isNotBlank() && want in origin(it) } ?: named.firstOrNull()
                ?: all.firstOrNull().takeIf { withYear && !exactOnly }
        }
        return once(true) ?: if (exactOnly) null else once(false)
    }
}
