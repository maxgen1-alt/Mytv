package com.mytv.launcher.ang

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Builds Ang's guide from TVmaze (free, no key): her curated shows' seasons,
 * plus a scan of streaming premieres for new shows of the same kind. The
 * premiere scan is ~70 small requests, so it's cached for a day.
 */
class AngRepository(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences("ang", Context.MODE_PRIVATE)
    private val premiereCache = File(context.cacheDir, "ang-premieres.json")
    private val api = "https://api.tvmaze.com"

    @Volatile private var cachedPremieres: List<Premiere>? = null

    suspend fun curatedShows(): List<Pair<CuratedShow, TvShow>> = coroutineScope {
        AngShows.curated.map { spec ->
            async {
                val id = showId(spec) ?: return@async null
                val body = get("$api/shows/$id?embed[]=seasons&embed[]=nextepisode") ?: return@async null
                runCatching { spec to TvMazeParser.parseShow(JSONObject(body)) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /** Season premieres on streaming services around today (cached for 24h). */
    suspend fun premieres(today: LocalDate = LocalDate.now()): List<Premiere> {
        cachedPremieres?.takeIf { fresh() }?.let { return it }
        readCache()?.takeIf { fresh() }?.let { cachedPremieres = it; return it }

        val days = (-AngShows.DISCOVER_PAST_DAYS..AngShows.DISCOVER_FUTURE_DAYS).map { today.plusDays(it) }
        val raw = mutableListOf<String>()
        // TVmaze allows ~20 calls / 10s: go in small batches.
        for (batch in days.chunked(4)) {
            coroutineScope {
                batch.map { d -> async { get("$api/schedule/web?date=$d") } }.awaitAll()
            }.filterNotNull().forEach(raw::add)
            delay(2_200)
        }
        val all = raw.flatMap { runCatching { TvMazeParser.parsePremieres(it) }.getOrDefault(emptyList()) }
        // Only keep what could ever be shown, so the cache stays small.
        val relevant = all.filter { AngShows.isHerKindOfShow(it.show) }
        if (raw.isNotEmpty()) writeCache(raw.size, relevant)
        cachedPremieres = relevant
        return relevant
    }

    private suspend fun showId(spec: CuratedShow): Long? {
        val key = "id:${spec.query}"
        prefs.getLong(key, -1).takeIf { it > 0 }?.let { return it }
        val url = "$api/search/shows".toHttpUrl().newBuilder().addQueryParameter("q", spec.query).build().toString()
        val body = get(url) ?: return null
        val show = runCatching { TvMazeParser.pickSearchResult(body, spec.hint) }.getOrNull() ?: return null
        // Guard against "Crew Girl" matching some unrelated show: names must match closely.
        if (!show.name.equals(spec.query, ignoreCase = true) &&
            !show.name.replace("The ", "").equals(spec.query.replace("The ", ""), ignoreCase = true)
        ) return null
        prefs.edit().putLong(key, show.id).apply()
        return show.id
    }

    private fun fresh() = System.currentTimeMillis() - prefs.getLong("premieres_at", 0) < 24 * 3600_000L

    private fun writeCache(days: Int, list: List<Premiere>) {
        // Store as the original schedule format so the same parser reads it back.
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("number", 1)
                    .put("season", p.season)
                    .put("airdate", p.date.toString())
                    .put(
                        "show",
                        JSONObject()
                            .put("id", p.show.id)
                            .put("name", p.show.name)
                            .put("type", p.show.type)
                            .put("language", p.show.language)
                            .put("genres", org.json.JSONArray(p.show.genres))
                            .put("webChannel", JSONObject().put("name", p.show.service))
                            .put("image", JSONObject().put("medium", p.show.imageUrl)),
                    ),
            )
        }
        runCatching { premiereCache.writeText(arr.toString()) }
        if (days > 0) prefs.edit().putLong("premieres_at", System.currentTimeMillis()).apply()
    }

    private fun readCache(): List<Premiere>? =
        runCatching { TvMazeParser.parsePremieres(premiereCache.readText()) }.getOrNull()

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }
}
