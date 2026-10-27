package com.mytv.launcher.ang

import android.content.Context
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 *  - suggestions (new shows, ones she missed, movies): TMDB, on the apps you
 *    have, every 6h
 *  (her likes / dislikes are handled by a [TasteRepository])
 */
class AngRepository(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences("ang", Context.MODE_PRIVATE)
    private val curatedFile = File(context.filesDir, "ang-curated.json")
    // v5: 3 pages per row (deeper pool).
    private val candidatesFile = File(context.cacheDir, "ang-candidates-v5.json")
    private val api = "https://api.tvmaze.com"

    @Volatile private var curatedMem: List<Pair<CuratedShow, TvShow>>? = null
    @Volatile private var candidatesMem: Candidates? = null

    /** Suggestion pools from discover, for one set of installed providers. */
    data class Candidates(
        val providers: Set<String>,
        val shows: List<AngItem>,
        val movies: List<AngItem>,
        /** New shows of her kind that haven't premiered yet. */
        val upcoming: List<AngItem>,
        /** Easy Watch: sitcoms. */
        val sitcoms: List<AngItem>,
        val at: Long,
    )

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

    // ---- Suggestions (TMDB discover) ----

    private companion object {
        val PAGES = 1..3
    }

    private data class Pools(val shows: List<AngItem>, val movies: List<AngItem>, val upcoming: List<AngItem>, val sitcoms: List<AngItem>)

    /** Whatever we have saved for these providers, however old: for an instant first paint. */
    suspend fun cachedCandidates(providers: List<WhereToWatch.Provider>): Candidates? {
        val names = providers.map { it.name }.toSet()
        candidatesMem?.takeIf { it.providers == names }?.let { return it }
        return withContext(Dispatchers.IO) { readCandidates() }?.takeIf { it.providers == names }?.also { candidatesMem = it }
    }

    /** New shows, older shows she might have missed, and romance movies, on [providers]. Refreshed every 6h. */
    suspend fun candidates(providers: List<WhereToWatch.Provider>): Candidates? {
        val names = providers.map { it.name }.toSet()
        cachedCandidates(providers)?.takeIf { System.currentTimeMillis() - it.at < 6 * 3600_000L }?.let { return it }
        if (!Tmdb.isConfigured || providers.isEmpty()) return null
        val wanted = AngDiscovery.wantedKeywords.mapNotNull { keywordId(it) }
        if (wanted.isEmpty()) return candidatesMem
        val blocked = AngDiscovery.blockedKeywords.mapNotNull { keywordId(it) }
        val today = LocalDate.now()

        // A title on two of your apps is listed once, under the app where it's most popular.
        fun merge(lists: List<List<AngItem>?>) =
            lists.filterNotNull().flatten().sortedByDescending { it.popularity }.distinctBy { it.key }

        val sitcomKeyword = keywordId("sitcom")
        val (shows, movies, upcoming, sitcoms) = coroutineScope {
            val shows = providers.flatMap { p ->
                // 3 pages per app and row: a deep bench, so when she likes or hides a show
                // the next one slides in instead of the row shrinking.
                val paths = PAGES.map { page -> AngDiscovery.newShowsPath(today, p.tmdbIds, wanted, blocked, page) } +
                    PAGES.map { page -> AngDiscovery.missedShowsPath(today, p.tmdbIds, wanted, blocked, page) }
                paths.map { path -> async { Tmdb.get(path) { AngDiscovery.parseResults(it, false, p.name, today) } } }
            }
            val movies = providers.flatMap { p ->
                PAGES.map { page ->
                    async {
                        Tmdb.get(AngDiscovery.moviesPath(today, p.tmdbIds, blocked, page)) {
                            AngDiscovery.parseResults(it, true, p.name, today)
                        }
                    }
                }
            }
            val upcoming = providers.map { p ->
                async {
                    Tmdb.get(AngDiscovery.upcomingShowsPath(today, p.tmdbNetworks, wanted, blocked)) {
                        AngDiscovery.parseResults(it, false, p.name, today)
                    }
                }
            }
            val sitcoms = if (sitcomKeyword == null) emptyList() else providers.flatMap { p ->
                PAGES.map { page ->
                    async {
                        Tmdb.get(AngDiscovery.sitcomsPath(p.tmdbIds, sitcomKeyword, blocked, page)) {
                            AngDiscovery.parseResults(it, false, p.name, today, maxAgeYears = null)
                        }
                    }
                }
            }
            // Upcoming shows picked by name / description (new shows rarely have keywords yet); the
            // guide keeps only the ones on your apps, using the network looked up below.
            val anywhere = listOf("10764", "18|35").flatMap { genres ->
                (1..2).map { page ->
                    async {
                        Tmdb.get(AngDiscovery.upcomingAnywherePath(today, genres, page)) {
                            AngDiscovery.parseResults(it, false, null, today).filter(AngDiscovery::soundsRomantic)
                        }
                    }
                }
            }.awaitAll().filterNotNull().flatten().distinctBy { it.key }.sortedByDescending { it.popularity }.take(20)
            // Where each one will stream (e.g. "Fox Nation"), from its TMDB page.
            val anywhereLabelled = anywhere.map { item ->
                async {
                    val network = item.tmdbId?.let { id -> Tmdb.get(AngDiscovery.detailsPath(id), AngDiscovery::parseNetwork) }
                    item.copy(service = network)
                }
            }.awaitAll()
            // Shows on your apps first, so a show found both ways keeps its app label.
            val allUpcoming = (upcoming.awaitAll().filterNotNull().flatten() + anywhereLabelled).distinctBy { it.key }
            Pools(merge(shows.awaitAll()), merge(movies.awaitAll()), allUpcoming, merge(sitcoms.awaitAll()))
        }
        if (shows.isEmpty() && movies.isEmpty()) return candidatesMem
        val c = Candidates(names, shows, movies, upcoming, sitcoms, System.currentTimeMillis())
        candidatesMem = c
        withContext(Dispatchers.IO) { writeCandidates(c) }
        return c
    }

    private fun writeCandidates(c: Candidates) = runCatching {
        candidatesFile.writeText(
            JSONObject()
                .put("providers", JSONArray(c.providers.toList()))
                .put("shows", JSONArray(c.shows.map(::encode)))
                .put("movies", JSONArray(c.movies.map(::encode)))
                .put("upcoming", JSONArray(c.upcoming.map(::encode)))
                .put("sitcoms", JSONArray(c.sitcoms.map(::encode)))
                .put("at", c.at)
                .toString(),
        )
    }

    private fun readCandidates(): Candidates? = runCatching {
        val o = JSONObject(candidatesFile.readText())
        fun list(name: String) = o.getJSONArray(name).let { a -> (0 until a.length()).map { decode(a.getJSONObject(it)) } }
        val p = o.getJSONArray("providers").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        Candidates(p, list("shows"), list("movies"), list("upcoming"), list("sitcoms"), o.getLong("at"))
    }.getOrNull()

    private fun encode(i: AngItem) = JSONObject()
        .put("key", i.key).put("title", i.title).put("detail", i.detail).put("image", i.imageUrl ?: "")
        .put("service", i.service ?: "").put("date", i.date?.toString() ?: "").put("badge", i.badge ?: "")
        .put("pop", i.popularity).put("tmdb", i.tmdbId ?: -1).put("movie", i.isMovie).put("vote", i.voteAverage)

    private fun decode(o: JSONObject) = AngItem(
        key = o.getString("key"),
        title = o.getString("title"),
        detail = o.optString("detail"),
        imageUrl = o.optString("image").ifBlank { null },
        service = o.optString("service").ifBlank { null },
        date = o.optString("date").ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        badge = o.optString("badge").ifBlank { null },
        popularity = o.optDouble("pop", 0.0),
        tmdbId = o.optLong("tmdb", -1).takeIf { it > 0 },
        isMovie = o.optBoolean("movie"),
        voteAverage = o.optDouble("vote", 0.0),
    )

    private suspend fun keywordId(name: String): Int? {
        val key = "kw:$name"
        prefs.getInt(key, 0).takeIf { it > 0 }?.let { return it }
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val id = Tmdb.get("/search/keyword?query=$q") { AngDiscovery.parseKeywordId(it, name) } ?: return null
        prefs.edit().putInt(key, id).apply()
        return id
    }

    // ---- Her Continue Watching ----

    /**
     * Is this Continue Watching title one of hers? Yes if it's in her guide,
     * otherwise we look it up on TVmaze once and remember the answer.
     */
    /** [guideTitles]: already normalized ([AngGuideBuilder.normalize]). */
    suspend fun isHers(title: String, guideTitles: Set<String>): Boolean {
        val norm = AngGuideBuilder.normalize(title)
        if (norm in guideTitles) return true
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
