package com.mytv.launcher.ratings

import android.content.Context
import android.content.SharedPreferences
import com.mytv.launcher.BuildConfig
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * IMDb ratings via OMDb (free key: OMDB_API_KEY), looked up only when a
 * title is focused and cached for a week. Without a key, shows TMDB's score.
 */
object Ratings {
    private const val WEEK = 7 * 24 * 3600_000L
    private val omdbKey = BuildConfig.OMDB_API_KEY
    private val memory = HashMap<String, ShownRating?>()
    private lateinit var prefs: SharedPreferences
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("ratings", Context.MODE_PRIVATE)
    }

    /** Instant answer if we already know it (memory only; safe on the main thread). */
    fun cached(ref: RatingRef): ShownRating? = synchronized(memory) { memory[ref.cacheKey] }

    suspend fun lookup(ref: RatingRef): ShownRating? {
        synchronized(memory) { if (memory.containsKey(ref.cacheKey)) return memory[ref.cacheKey] }
        val result = withContext(Dispatchers.IO) { imdb(ref) } ?: tmdbFallback(ref)
        synchronized(memory) { memory[ref.cacheKey] = result }
        return result
    }

    private fun tmdbFallback(ref: RatingRef) =
        ref.tmdbVote.takeIf { it > 0 }?.let { ShownRating("TMDB", "%.1f".format(it)) }

    private suspend fun imdb(ref: RatingRef): ShownRating? {
        if (omdbKey.isBlank() || !::prefs.isInitialized) return null
        val now = System.currentTimeMillis()
        // Saved as "<rating>|<saved at>"; an empty rating means IMDb has none.
        prefs.getString("r:${ref.cacheKey}", null)?.split("|")?.takeIf { it.size == 2 }?.let { (value, at) ->
            if (now - (at.toLongOrNull() ?: 0) < WEEK) return value.takeIf { it.isNotBlank() }?.let { ShownRating("IMDb", it) }
        }
        val imdbId = ref.imdbId ?: imdbIdFromTmdb(ref) ?: return null
        val body = runCatching {
            http.newCall(Request.Builder().url("https://www.omdbapi.com/?i=$imdbId&apikey=$omdbKey").build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        val value = runCatching { JSONObject(body).optString("imdbRating") }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "N/A" }
        prefs.edit().putString("r:${ref.cacheKey}", "${value.orEmpty()}|$now").apply()
        return value?.let { ShownRating("IMDb", it) }
    }

    /** TMDB knows each title's IMDb id (cached forever: it never changes). */
    private suspend fun imdbIdFromTmdb(ref: RatingRef): String? {
        val id = ref.tmdbId ?: return null
        val key = "id:${ref.cacheKey}"
        prefs.getString(key, null)?.let { return it.ifBlank { null } }
        val path = "/${if (ref.isMovie) "movie" else "tv"}/$id/external_ids"
        val imdb = Tmdb.get(path) { JSONObject(it).optString("imdb_id") } ?: return null
        prefs.edit().putString(key, imdb).apply()
        return imdb.takeIf { it.startsWith("tt") }
    }
}
