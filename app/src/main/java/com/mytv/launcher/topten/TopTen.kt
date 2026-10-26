package com.mytv.launcher.topten

import com.mytv.launcher.sports.Service
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A streaming service whose Top 10 feeds "Popular on Your Apps". */
enum class TopTenService(
    val displayName: String,
    /** TMDB watch-provider ids for the US (several = any of them). */
    val tmdbProviders: List<Int>,
    /** Which installed app this belongs to. */
    val app: Service,
) {
    NETFLIX("Netflix", listOf(8), Service.NETFLIX),
    HULU("Hulu", listOf(15), Service.HULU),
    PRIME("Prime Video", listOf(9, 119), Service.PRIME_VIDEO),
    DISNEY("Disney+", listOf(337), Service.DISNEY),
    APPLE("Apple TV", listOf(350), Service.APPLE_TV),
    PEACOCK("Peacock", listOf(386, 387), Service.PEACOCK),
    // Comes with your Prime Video account (Prime Video Channels), so it opens in Prime Video.
    PARAMOUNT("Paramount+", listOf(2303, 531, 582), Service.PRIME_VIDEO),
    ;

    companion object {
        fun forApp(packageName: String, label: String): TopTenService? =
            entries.firstOrNull { it.app.matches(packageName, label) }
    }
}

data class TopTitle(
    val id: Long,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val isMovie: Boolean,
    val popularity: Double,
    /** First air date (shows) or release date (movies). */
    val released: LocalDate? = null,
    /** Ranking score (popularity x trending x freshness), set by [TmdbParser.topTen]. */
    val score: Double = 0.0,
    /** TMDB average rating, 0-10. */
    val voteAverage: Double = 0.0,
) {
    val key: String get() = (if (isMovie) "movie:" else "tv:") + id
    val ratingRef: com.mytv.launcher.ratings.RatingRef
        get() = com.mytv.launcher.ratings.RatingRef(null, id, isMovie, voteAverage)
}

object TmdbParser {
    private const val IMG = "https://image.tmdb.org/t/p/"

    /** TV genres that flood "popular" without being anyone's Top 10: news, soaps, talk shows, kids. */
    private val skipTvGenres = setOf(10762, 10763, 10766, 10767)

    /** One page of /discover/tv or /discover/movie. */
    fun parseDiscover(json: String, isMovie: Boolean): List<TopTitle> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            if (!isMovie && o.optJSONArray("genre_ids").ints().any { it in skipTvGenres }) return@mapNotNull null
            val poster = o.optStringOrNull("poster_path") ?: return@mapNotNull null
            TopTitle(
                id = o.optLong("id"),
                title = (if (isMovie) o.optStringOrNull("title") else o.optStringOrNull("name")) ?: return@mapNotNull null,
                posterUrl = IMG + "w342" + poster,
                backdropUrl = o.optStringOrNull("backdrop_path")?.let { IMG + "w1280" + it },
                isMovie = isMovie,
                popularity = o.optDouble("popularity", 0.0),
                released = (if (isMovie) o.optStringOrNull("release_date") else o.optStringOrNull("first_air_date"))
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                voteAverage = o.optDouble("vote_average", 0.0),
            )
        }
    }

    /** /trending/all/week → "tv:123" / "movie:456" keys in trending order. */
    fun parseTrending(json: String): List<String> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            when (o.optString("media_type")) {
                "movie" -> "movie:" + o.optLong("id")
                "tv" -> "tv:" + o.optLong("id")
                else -> null
            }
        }
    }

    /**
     * Real Top 10s are mostly brand-new, buzzy releases, so rank by
     * popularity boosted by (a) being in TMDB's trending-this-week list,
     * higher = bigger boost, and (b) being released in the last few weeks.
     */
    fun topTen(lists: List<List<TopTitle>>, trending: List<String> = emptyList(), today: LocalDate = LocalDate.now()): List<TopTitle> {
        val trendRank = trending.withIndex().associate { (i, k) -> k to i }
        fun score(t: TopTitle): Double {
            val trend = trendRank[t.key]?.let { 4.0 - 2.5 * it / trending.size.coerceAtLeast(1) } ?: 1.0
            val age = t.released?.let { ChronoUnit.DAYS.between(it, today) }
            val fresh = when {
                age == null || age < 0 -> 1.0
                age <= 14 -> 2.0
                age <= 45 -> 1.5
                else -> 1.0
            }
            return t.popularity * trend * fresh
        }
        return lists.flatten()
            .distinctBy { it.key }
            .map { it.copy(score = score(it)) }
            .sortedByDescending { it.score }
            .take(10)
    }

    /**
     * "Popular across your apps": every service's Top 10 merged into one
     * ranking. A title on several services is listed once, under the
     * service where it ranks best.
     */
    fun acrossServices(lists: Map<TopTenService, List<TopTitle>>, limit: Int = 20): List<Pair<TopTenService, TopTitle>> =
        lists.flatMap { (service, titles) -> titles.map { service to it } }
            .sortedByDescending { it.second.score }
            .distinctBy { it.second.key }
            .take(limit)

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
