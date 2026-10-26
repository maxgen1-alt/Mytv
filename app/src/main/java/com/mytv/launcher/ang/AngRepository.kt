package com.mytv.launcher.ang

import android.content.Context
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Everything behind Ang's TV. Built to open instantly: every source is cached
 * (in memory and on disk) and refreshed in the background.
 *  - her curated shows: TVmaze, refreshed every 6h
 *  - "New shows you might like": TMDB, on the apps you have, every 6h
 *  - "Coming soon" premieres: TVmaze's streaming schedule, daily
 */
class AngRepository(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences("ang", Context.MODE_PRIVATE)
    private val curatedFile = File(context.filesDir, "ang-curated.json")
    // v3: future-only scan with availability filtering.
    private val premiereFile = File(context.cacheDir, "ang-premieres-v3.json")
    private val api = "https://api.tvmaze.com"

    @Volatile private var curatedMem: List<Pair<CuratedShow, TvShow>>? = null
    @Volatile private var premieresMem: List<Premiere>? = null
    @Volatile private var discoveredMem: Pair<Set<String>, List<AngItem>>? = null
    @Volatile private var discoveredAt = 0L

    // ---- Her curated shows ----

    /** Whatever we have saved, however old: for an instant first paint. */
    suspend fun cachedCurated(): List<Pair<CuratedShow, TvShow>>? =
        curatedMem ?: withContext(Dispatchers.IO) { readCurated() }?.also { curatedMem = it }

    /** Fresh-enough curated shows (refetched when older than 6h). */
    suspend fun curatedShows(): List<Pair<CuratedShow, TvShow>> {
        val age = System.currentTimeMillis() - prefs.getLong("curated_at", 0)
        cachedCurated()?.takeIf { age < 6 * 3600_000L }?.let { return it }

        val fetched = coroutineScope {
            AngShows.curated.map { spec ->
                async {
                    val id = showId(spec) ?: return@async null
                    val body = get("$api/shows/$id?embed[]=seasons&embed[]=nextepisode") ?: return@async null
                    runCatching { Triple(spec, TvMazeParser.parseShow(JSONObject(body)), body) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (fetched.isEmpty()) return curatedMem.orEmpty()
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            fetched.forEach { (spec, _, body) -> arr.put(JSONObject().put("query", spec.query).put("show", JSONObject(body))) }
            runCatching { curatedFile.writeText(arr.toString()) }
            prefs.edit().putLong("curated_at", System.currentTimeMillis()).apply()
        }
        return fetched.map { it.first to it.second }.also { curatedMem = it }
    }

    private fun readCurated(): List<Pair<CuratedShow, TvShow>>? = runCatching {
        val arr = JSONArray(curatedFile.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val spec = AngShows.curated.firstOrNull { it.query == o.optString("query") } ?: return@mapNotNull null
            spec to TvMazeParser.parseShow(o.getJSONObject("show"))
        }
    }.getOrNull()

    private suspend fun showId(spec: CuratedShow): Long? {
        val key = "id:${spec.query}"
        prefs.getLong(key, -1).takeIf { it > 0 }?.let { return it }
        val url = "$api/search/shows".toHttpUrl().newBuilder().addQueryParameter("q", spec.query).build().toString()
        val body = get(url) ?: return null
        val show = runCatching { TvMazeParser.pickSearchResult(body, spec.hint) }.getOrNull() ?: return null
        // Guard against a search matching some unrelated show: names must match closely.
        if (!show.name.equals(spec.query, ignoreCase = true) &&
            !show.name.replace("The ", "").equals(spec.query.replace("The ", ""), ignoreCase = true)
        ) return null
        prefs.edit().putLong(key, show.id).apply()
        return show.id
    }

    // ---- New shows you might like (TMDB) ----

    /** Cached result for these providers, if any. */
    fun cachedDiscovered(providerNames: Set<String>): List<AngItem>? =
        discoveredMem?.takeIf { it.first == providerNames }?.second

    /** Romance / rom-com / coming-of-age / dating shows on [providers] (the apps you have). */
    suspend fun discover(providers: List<WhereToWatch.Provider>): List<AngItem> {
        val names = providers.map { it.name }.toSet()
        cachedDiscovered(names)?.takeIf { System.currentTimeMillis() - discoveredAt < 6 * 3600_000L }?.let { return it }
        if (!Tmdb.isConfigured || providers.isEmpty()) return emptyList()

        val wanted = AngDiscovery.wantedKeywords.mapNotNull { keywordId(it) }
        if (wanted.isEmpty()) return cachedDiscovered(names).orEmpty()
        val blocked = AngDiscovery.blockedKeywords.mapNotNull { keywordId(it) }
        val today = LocalDate.now()

        val found = coroutineScope {
            providers.map { p ->
                async {
                    Tmdb.get(AngDiscovery.discoverPath(today, p.tmdbIds, wanted, blocked)) {
                        AngDiscovery.parseDiscover(it, p.name, today)
                    }
                }
            }.awaitAll().filterNotNull().flatten()
        }
        // A show on two of your apps appears once, under the app where it's most popular.
        val merged = found.sortedByDescending { it.popularity }.distinctBy { it.key }.take(30)
        discoveredMem = names to merged
        discoveredAt = System.currentTimeMillis()
        return merged
    }

    private suspend fun keywordId(name: String): Int? {
        val key = "kw:$name"
        prefs.getInt(key, 0).takeIf { it > 0 }?.let { return it }
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val id = Tmdb.get("/search/keyword?query=$q") { AngDiscovery.parseKeywordId(it, name) } ?: return null
        prefs.edit().putInt(key, id).apply()
        return id
    }

    // ---- Coming soon (TVmaze streaming schedule) ----

    /** Upcoming season premieres on streaming services (cached for 24h). */
    suspend fun premieres(today: LocalDate = LocalDate.now()): List<Premiere> {
        val fresh = System.currentTimeMillis() - prefs.getLong("premieres_v3_at", 0) < 24 * 3600_000L
        premieresMem?.takeIf { fresh }?.let { return it }
        if (fresh) withContext(Dispatchers.IO) { readPremieres() }?.let { premieresMem = it; return it }

        val all = mutableListOf<Premiere>()
        var ok = 0
        // TVmaze allows ~20 calls / 10s: go in small batches.
        for (batch in (0..AngShows.DISCOVER_FUTURE_DAYS).map { today.plusDays(it) }.chunked(4)) {
            coroutineScope {
                batch.map { d ->
                    async {
                        withContext(Dispatchers.IO) {
                            get("$api/schedule/web?date=$d")?.let { body ->
                                runCatching { TvMazeParser.parsePremieres(body) }.getOrNull()
                            }
                        }
                    }
                }.awaitAll()
            }.filterNotNull().forEach { ok++; all += it }
            delay(2_200)
        }
        // Only keep what could ever be shown, so the cache stays small.
        val relevant = all.filter { AngShows.isHerKindOfShow(it.show) }
        if (ok > 0) withContext(Dispatchers.IO) { writePremieres(relevant) }
        premieresMem = relevant
        return relevant
    }

    private fun writePremieres(list: List<Premiere>) {
        // Same shape as TVmaze's schedule, so the same parser reads it back.
        val arr = JSONArray()
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
                            .put("genres", JSONArray(p.show.genres))
                            .put(
                                "webChannel",
                                JSONObject()
                                    .put("name", p.show.service)
                                    .put("country", p.show.country?.let { JSONObject().put("code", it) }),
                            )
                            .put("image", JSONObject().put("medium", p.show.imageUrl)),
                    ),
            )
        }
        runCatching { premiereFile.writeText(arr.toString()) }
        prefs.edit().putLong("premieres_v3_at", System.currentTimeMillis()).apply()
    }

    private fun readPremieres(): List<Premiere>? =
        runCatching { TvMazeParser.parsePremieres(premiereFile.readText()) }.getOrNull()

    // ---- "Mark as watched" (long-press in Ang's TV) ----

    fun markWatched(key: String, date: LocalDate = LocalDate.now()) {
        prefs.edit().putLong("watched:$key", date.toEpochDay()).apply()
    }

    fun watched(): Map<String, LocalDate> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith("watched:") || v !is Long) return@mapNotNull null
        val key = k.removePrefix("watched:")
        // Marks saved by older versions used the bare TVmaze id.
        (if (key.toLongOrNull() != null) "tvmaze:$key" else key) to LocalDate.ofEpochDay(v)
    }.toMap()

    // ---- Her Continue Watching ----

    /**
     * Is this Continue Watching title one of hers? Yes if it's in her guide,
     * otherwise we look it up on TVmaze once and remember the answer.
     */
    suspend fun isHers(title: String, guideTitles: Set<String>): Boolean {
        val norm = AngGuideBuilder.normalize(title)
        if (norm in guideTitles.map(AngGuideBuilder::normalize)) return true
        val key = "hers2:$norm" // v2: answers from before the US/CA/UK-only rule are ignored
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        val url = "$api/singlesearch/shows".toHttpUrl().newBuilder().addQueryParameter("q", title).build().toString()
        val body = get(url) ?: return false // unknown for now; ask again next time
        val show = runCatching { TvMazeParser.parseShow(JSONObject(body)) }.getOrNull()
        val hers = show != null && AngGuideBuilder.normalize(show.name) == norm && AngShows.isHerKindOfShow(show)
        prefs.edit().putBoolean(key, hers).apply()
        return hers
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }
}
