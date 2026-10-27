package com.mytv.launcher.ang

import android.content.Context
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Taste learning for one profile (Ang's, or yours): TMDB recommendations of
 * what was liked (boosted, and added to the rows if they stream on your apps),
 * of what was disliked (pushed down), and season dates of followed shows.
 *
 * [positive]: which ratings seed "more like this", and their weight.
 * [follow]: which ratings follow a show for new seasons.
 * [strict]: Ang's rules (romance-only movies, US/CA/UK, last 10 years); off = any genre.
 */
class TasteRepository(
    context: Context,
    private val strict: Boolean,
    private val positive: Map<Rating, Int>,
    private val follow: Set<Rating>,
    private val maxCandidates: Int = 30,
    /** How many of the best-rated titles seed "more like this". */
    private val maxSeeds: Int = 20,
) {
    /** How much a title counts towards "more like this": your 1-10 score if you gave one, else its rating's weight. */
    private fun weight(r: Rated): Int = r.stars?.let { s -> (s - 5).coerceAtLeast(0) } ?: (positive[r.rating] ?: 0)

    private fun isDisliked(r: Rated) = r.rating == Rating.DISLIKE || (r.stars ?: 10) <= 5

    // Shared with every profile: TMDB ids and where things stream don't depend on who's asking.
    private val prefs = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE)

    /** "More like this": candidates, plus how strongly liked / disliked titles recommend each key. */
    data class Recs(val items: List<AngItem>, val liked: Map<String, Int>, val disliked: Map<String, Int>)

    @Volatile private var recsMem: Pair<String, Recs>? = null
    @Volatile private var recsAt = 0L
    private val followedMem = HashMap<Long, Pair<Long, String>>() // tmdb id -> (fetched at, json)

    /** Memory-only: whatever we computed last, for an instant first paint. */
    fun cachedRecs(): Recs? = recsMem?.second

    /**
     * Titles TMDB recommends for what she liked (boosted, and added to her rows if
     * they're on your apps) and for what she disliked (pushed down).
     */
    suspend fun recommendations(ratings: List<Rated>, providers: List<WhereToWatch.Provider>): Recs = coroutineScope {
        // Best-rated first (your 10s and 9s), newest first among equals.
        val liked = ratings.filter { it.rating in positive && !isDisliked(it) && weight(it) > 0 }
            .sortedWith(compareByDescending<Rated> { weight(it) }.thenByDescending { it.date })
            .take(maxSeeds)
        val disliked = ratings.filter(::isDisliked).sortedByDescending { it.date }.take(10)
        val sig = (liked + disliked).joinToString { it.key + it.rating } + "|" + providers.joinToString { it.name }
        recsMem?.takeIf { it.first == sig && System.currentTimeMillis() - recsAt < 6 * 3600_000L }
            ?.let { return@coroutineScope it.second }
        if (!Tmdb.isConfigured || liked.isEmpty() && disliked.isEmpty()) return@coroutineScope Recs(emptyList(), emptyMap(), emptyMap())
        val today = LocalDate.now()

        suspend fun recsFor(r: Rated): List<AngItem> {
            val id = tmdbIdOf(r) ?: return emptyList()
            return Tmdb.get(AngDiscovery.recommendationsPath(id, r.isMovie)) {
                AngDiscovery.parseResults(it, r.isMovie, null, today, strict)
            }.orEmpty()
        }
        val likedLists = liked.map { r -> async { recsFor(r).map { it to weight(r) } } }.awaitAll()
        val dislikedLists = disliked.map { async { recsFor(it) } }.awaitAll()
        // Weighted: e.g. for you a like counts double an "already watched".
        val likedCounts = likedLists.flatten().groupBy({ it.first.key }, { it.second }).mapValues { it.value.sum() }
        val dislikedCounts = dislikedLists.flatten().groupingBy { it.key }.eachCount()
        val seen = ratings.map { it.key }.toSet()

        // The strongest "more like this" titles become candidates, if they stream on your apps.
        val top = likedLists.flatten().map { it.first }.distinctBy { it.key }
            .filter { it.key !in seen }
            .sortedWith(compareByDescending<AngItem> { likedCounts[it.key] ?: 0 }.thenByDescending { it.popularity })
            .take(maxCandidates)
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
        ratings.filter { it.rating in follow && !it.isMovie }.map { r ->
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

    /** Shows that premiered in the last year in [genres], streaming on your apps (several pages). */
    suspend fun newInGenres(providers: List<WhereToWatch.Provider>, genres: List<Int>): List<AngItem> = coroutineScope {
        if (!Tmdb.isConfigured || providers.isEmpty()) return@coroutineScope emptyList()
        val today = LocalDate.now()
        providers.map { p ->
            async {
                (1..2).flatMap { page ->
                    Tmdb.get(AngDiscovery.newInGenresPath(today, p.tmdbIds, genres, page)) {
                        AngDiscovery.parseResults(it, false, p.name, today, strict)
                    }.orEmpty()
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.popularity }.distinctBy { it.key }
    }

    /** Upcoming new shows in [genres] from the streaming services you have (next 6 months). */
    suspend fun upcomingInGenres(providers: List<WhereToWatch.Provider>, genres: List<Int>): List<AngItem> = coroutineScope {
        if (!Tmdb.isConfigured || genres.isEmpty()) return@coroutineScope emptyList()
        val today = LocalDate.now()
        providers.map { p ->
            async {
                Tmdb.get(AngDiscovery.upcomingByGenrePath(today, p.tmdbNetworks, genres)) {
                    AngDiscovery.parseResults(it, false, p.name, today, strict)
                }
            }
        }.awaitAll().filterNotNull().flatten().sortedByDescending { it.popularity }.distinctBy { it.key }
    }
}
