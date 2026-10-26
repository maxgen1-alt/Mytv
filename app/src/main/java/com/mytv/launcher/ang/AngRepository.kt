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
 *  - "more like what she liked": TMDB recommendations of her liked titles
 *  - liked shows' season dates (Watch now / Coming soon): TMDB, every 12h
 */
class AngRepository(context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences("ang", Context.MODE_PRIVATE)
    private val curatedFile = File(context.filesDir, "ang-curated.json")
    private val candidatesFile = File(context.cacheDir, "ang-candidates-v1.json")
    private val api = "https://api.tvmaze.com"

    @Volatile private var curatedMem: List<Pair<CuratedShow, TvShow>>? = null
    @Volatile private var candidatesMem: Candidates? = null
    @Volatile private var recsMem: Pair<String, Recs>? = null
    @Volatile private var recsAt = 0L
    private val followedMem = HashMap<Long, Pair<Long, String>>() // tmdb id -> (fetched at, json)

    /** Suggestion pools from discover, for one set of installed providers. */
    data class Candidates(val providers: Set<String>, val shows: List<AngItem>, val movies: List<AngItem>, val at: Long)

    /** "More like this": candidates recommended by her liked titles, plus counts for ranking. */
    data class Recs(val items: List<AngItem>, val liked: Map<String, Int>, val disliked: Map<String, Int>)

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

        val (shows, movies) = coroutineScope {
            val shows = providers.flatMap { p ->
                val paths = listOf(AngDiscovery.newShowsPath(today, p.tmdbIds, wanted, blocked)) +
                    (1..2).map { page -> AngDiscovery.missedShowsPath(today, p.tmdbIds, wanted, blocked, page) }
                paths.map { path -> async { Tmdb.get(path) { AngDiscovery.parseResults(it, false, p.name, today) } } }
            }
            val movies = providers.flatMap { p ->
                (1..2).map { page ->
                    async {
                        Tmdb.get(AngDiscovery.moviesPath(today, p.tmdbIds, blocked, page)) {
                            AngDiscovery.parseResults(it, true, p.name, today)
                        }
                    }
                }
            }
            merge(shows.awaitAll()) to merge(movies.awaitAll())
        }
        if (shows.isEmpty() && movies.isEmpty()) return candidatesMem
        val c = Candidates(names, shows, movies, System.currentTimeMillis())
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
                .put("at", c.at)
                .toString(),
        )
    }

    private fun readCandidates(): Candidates? = runCatching {
        val o = JSONObject(candidatesFile.readText())
        fun list(name: String) = o.getJSONArray(name).let { a -> (0 until a.length()).map { decode(a.getJSONObject(it)) } }
        val p = o.getJSONArray("providers").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        Candidates(p, list("shows"), list("movies"), o.getLong("at"))
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

    // ---- Taste: "more like this" and following liked shows ----

    /** Memory-only: whatever we computed last, for an instant first paint. */
    fun cachedRecs(): Recs? = recsMem?.second

    /**
     * Titles TMDB recommends for what she liked (boosted, and added to her rows if
     * they're on your apps) and for what she disliked (pushed down).
     */
    suspend fun recommendations(ratings: List<Rated>, providers: List<WhereToWatch.Provider>): Recs = coroutineScope {
        val liked = ratings.filter { it.rating == Rating.LIKE }.sortedByDescending { it.date }.take(15)
        val disliked = ratings.filter { it.rating == Rating.DISLIKE }.sortedByDescending { it.date }.take(10)
        val sig = (liked + disliked).joinToString { it.key + it.rating } + "|" + providers.joinToString { it.name }
        recsMem?.takeIf { it.first == sig && System.currentTimeMillis() - recsAt < 6 * 3600_000L }
            ?.let { return@coroutineScope it.second }
        if (!Tmdb.isConfigured || liked.isEmpty() && disliked.isEmpty()) return@coroutineScope Recs(emptyList(), emptyMap(), emptyMap())
        val today = LocalDate.now()

        suspend fun recsFor(r: Rated): List<AngItem> {
            val id = tmdbIdOf(r) ?: return emptyList()
            return Tmdb.get(AngDiscovery.recommendationsPath(id, r.isMovie)) {
                AngDiscovery.parseResults(it, r.isMovie, null, today)
            }.orEmpty()
        }
        val likedLists = liked.map { async { recsFor(it) } }.awaitAll()
        val dislikedLists = disliked.map { async { recsFor(it) } }.awaitAll()
        val likedCounts = likedLists.flatten().groupingBy { it.key }.eachCount()
        val dislikedCounts = dislikedLists.flatten().groupingBy { it.key }.eachCount()

        // The strongest "more like this" titles become candidates, if they stream on your apps.
        val top = likedLists.flatten().distinctBy { it.key }
            .sortedWith(compareByDescending<AngItem> { likedCounts[it.key] ?: 0 }.thenByDescending { it.popularity })
            .take(30)
        val onYourApps = top.map { item ->
            async {
                whereItStreams(item, providers)?.let { name ->
                    item.copy(service = name, detail = listOfNotNull(item.date?.year?.toString(), name).joinToString(" · "))
                }
            }
        }.awaitAll().filterNotNull()

        Recs(onYourApps, likedCounts, dislikedCounts).also {
            recsMem = sig to it
            recsAt = System.currentTimeMillis()
        }
    }

    /** Which of your apps a TMDB title streams on (provider list cached for 3 days). */
    private suspend fun whereItStreams(item: AngItem, providers: List<WhereToWatch.Provider>): String? {
        val id = item.tmdbId ?: return null
        val cacheKey = "prov:${item.key}"
        val today = LocalDate.now().toEpochDay()
        val cached = prefs.getString(cacheKey, null)?.split("|")
            ?.takeIf { parts -> parts.firstOrNull()?.toLongOrNull()?.let { today - it < 3 } == true }
            ?.drop(1)?.mapNotNull { it.toIntOrNull() }?.toSet()
        val ids = cached ?: (Tmdb.get(AngDiscovery.providersPath(id, item.isMovie), AngDiscovery::parseProviders) ?: return null)
            .also { prefs.edit().putString(cacheKey, (listOf(today.toString()) + it.map(Int::toString)).joinToString("|")).apply() }
        return providers.firstOrNull { p -> p.tmdbIds.any { it in ids } }?.name
    }

    /** TMDB id for a rated title: stored when she rated a TMDB item, else looked up by name once. */
    private suspend fun tmdbIdOf(r: Rated): Long? {
        r.tmdbId?.let { return it }
        if (r.title.isBlank()) return null
        val key = "tmdbid:${if (r.isMovie) "m" else "t"}:${AngGuideBuilder.normalize(r.title)}"
        prefs.getLong(key, -1).takeIf { it > 0 }?.let { return it }
        val id = Tmdb.get(AngDiscovery.searchPath(r.title, r.isMovie)) { AngDiscovery.parseSearchId(it, r.title, r.isMovie) }
            ?: return null
        prefs.edit().putLong(key, id).apply()
        return id
    }

    /** Liked shows with their season dates (refreshed every 12h). */
    suspend fun followed(ratings: List<Rated>): List<FollowedShow> = coroutineScope {
        val today = LocalDate.now()
        ratings.filter { it.rating == Rating.LIKE && !it.isMovie }.map { r ->
            async {
                val id = tmdbIdOf(r) ?: return@async null
                val cached = synchronized(followedMem) { followedMem[id] }
                val json = cached?.takeIf { System.currentTimeMillis() - it.first < 12 * 3600_000L }?.second
                    ?: Tmdb.get(AngDiscovery.detailsPath(id)) { it }
                        ?.also { synchronized(followedMem) { followedMem[id] = System.currentTimeMillis() to it } }
                    ?: cached?.second
                    ?: return@async null
                withContext(Dispatchers.Default) { runCatching { AngDiscovery.parseFollowed(json, r, today) }.getOrNull() }
            }
        }.awaitAll().filterNotNull()
    }

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
