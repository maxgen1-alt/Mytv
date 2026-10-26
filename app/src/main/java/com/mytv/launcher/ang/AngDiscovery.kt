package com.mytv.launcher.ang

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A show she liked, with its season dates from TMDB (for Watch now / Coming soon). */
data class FollowedShow(
    val item: AngItem,
    /** Latest season that has started: number + premiere date. */
    val latestSeason: Pair<Int, LocalDate>?,
    /** Next season premiere, once announced. */
    val nextSeason: Pair<Int, LocalDate>?,
)

/**
 * TMDB queries and parsers behind Ang's TV. Everything is English, from the
 * US / Canada / UK, on streaming apps you have, and never adult / erotic.
 */
object AngDiscovery {

    /** TMDB keywords that make a show hers (any of them). Looked up by name at runtime. */
    val wantedKeywords = listOf(
        "romance", "romantic comedy", "teen romance", "coming of age", "love triangle",
        "dating show", "dating", "reality dating show",
    )

    /** Never show these. */
    val blockedKeywords = listOf("erotic", "erotica", "erotic drama", "softcore", "sex comedy", "erotic thriller")

    /** TV: animation, documentary, kids, news, soap, talk. */
    private val skipTvGenres = setOf(16, 99, 10762, 10763, 10766, 10767)
    /** Movies: horror, thriller, animation, documentary, TV movie. */
    private val skipMovieGenres = setOf(27, 53, 16, 99, 10770)
    private val countries = setOf("US", "GB", "CA")
    const val ROMANCE_MOVIE_GENRE = 10749

    /** /search/keyword → the id of the keyword named exactly [name], if any. */
    fun parseKeywordId(json: String, name: String): Int? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        return results.objects().firstOrNull { it.optString("name").equals(name, ignoreCase = true) }?.optInt("id")
    }

    private fun common(providerIds: List<Int>, blocked: List<Int>) =
        "watch_region=US&with_watch_monetization_types=flatrate" +
            "&with_watch_providers=${providerIds.joinToString("|")}" +
            (if (blocked.isNotEmpty()) "&without_keywords=${blocked.joinToString("|")}" else "") +
            "&with_original_language=en&include_adult=false&language=en-US&sort_by=popularity.desc"

    /** Shows that started in the last year. */
    fun newShowsPath(today: LocalDate, providerIds: List<Int>, wanted: List<Int>, blocked: List<Int>) =
        "/discover/tv?${common(providerIds, blocked)}&with_keywords=${wanted.joinToString("|")}" +
            "&first_air_date.gte=${today.minusYears(1)}&first_air_date.lte=$today&page=1"

    /** Well-rated shows from the last 10 years, older than a year. */
    fun missedShowsPath(today: LocalDate, providerIds: List<Int>, wanted: List<Int>, blocked: List<Int>, page: Int) =
        "/discover/tv?${common(providerIds, blocked)}&with_keywords=${wanted.joinToString("|")}" +
            "&first_air_date.gte=${today.minusYears(10)}&first_air_date.lte=${today.minusYears(1)}" +
            "&vote_count.gte=50&vote_average.gte=6.5&page=$page"

    /** Romance movies (rom-coms, romance, romantic dramas) from the last 10 years, new ones included. */
    fun moviesPath(today: LocalDate, providerIds: List<Int>, blocked: List<Int>, page: Int) =
        "/discover/movie?${common(providerIds, blocked)}&with_genres=$ROMANCE_MOVIE_GENRE" +
            "&without_genres=${skipMovieGenres.joinToString("|")}" +
            "&primary_release_date.gte=${today.minusYears(10)}&primary_release_date.lte=$today" +
            "&vote_count.gte=40&page=$page"

    fun recommendationsPath(tmdbId: Long, isMovie: Boolean) =
        "/${if (isMovie) "movie" else "tv"}/$tmdbId/recommendations?language=en-US&page=1"

    fun providersPath(tmdbId: Long, isMovie: Boolean) = "/${if (isMovie) "movie" else "tv"}/$tmdbId/watch/providers"

    fun detailsPath(tmdbId: Long) = "/tv/$tmdbId?language=en-US"

    fun searchPath(title: String, isMovie: Boolean) =
        "/search/${if (isMovie) "movie" else "tv"}?language=en-US&query=" + java.net.URLEncoder.encode(title, "UTF-8")

    fun key(tmdbId: Long, isMovie: Boolean) = "tmdb:${if (isMovie) "movie" else "tv"}:$tmdbId"

    /**
     * A page of TMDB results (discover / recommendations / search) → items.
     * [serviceName] labels where it streams, when known.
     */
    fun parseResults(json: String, isMovie: Boolean, serviceName: String?, today: LocalDate): List<AngItem> =
        (JSONObject(json).optJSONArray("results") ?: JSONArray()).objects().mapNotNull { o ->
            if (o.optBoolean("adult")) return@mapNotNull null
            if (o.optString("original_language") != "en") return@mapNotNull null
            // Recommendations mix shows and movies; keep the kind we asked for.
            o.optString("media_type").takeIf { it.isNotBlank() }?.let { if (it != (if (isMovie) "movie" else "tv")) return@mapNotNull null }
            val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            if (genres.any { it in (if (isMovie) skipMovieGenres else skipTvGenres) }) return@mapNotNull null
            if (isMovie && ROMANCE_MOVIE_GENRE !in genres) return@mapNotNull null
            val origin = o.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (origin.isNotEmpty() && origin.none { it in countries }) return@mapNotNull null
            val name = (if (isMovie) o.optString("title") else o.optString("name")).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val released = (if (isMovie) o.optString("release_date") else o.optString("first_air_date"))
                .takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (released != null && released.isBefore(today.minusYears(10))) return@mapNotNull null
            val id = o.optLong("id")
            AngItem(
                key = key(id, isMovie),
                title = name,
                detail = listOfNotNull(released?.year?.toString(), serviceName).joinToString(" · "),
                imageUrl = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { "https://image.tmdb.org/t/p/w342$it" },
                service = serviceName,
                date = released,
                badge = if (released != null && ChronoUnit.DAYS.between(released, today) in 0..120) {
                    if (isMovie) "New" else "New show"
                } else null,
                popularity = o.optDouble("popularity", 0.0),
                tmdbId = id,
                isMovie = isMovie,
                voteAverage = o.optDouble("vote_average", 0.0),
            )
        }

    /** /…/watch/providers → US subscription provider ids. */
    fun parseProviders(json: String): Set<Int> {
        val flatrate = JSONObject(json).optJSONObject("results")?.optJSONObject("US")?.optJSONArray("flatrate") ?: return emptySet()
        return flatrate.objects().map { it.optInt("provider_id") }.toSet()
    }

    /** /search/… → id of the result whose name matches [title]. */
    fun parseSearchId(json: String, title: String, isMovie: Boolean): Long? {
        val norm = AngGuideBuilder.normalize(title)
        return (JSONObject(json).optJSONArray("results") ?: return null).objects().firstOrNull {
            AngGuideBuilder.normalize(if (isMovie) it.optString("title") else it.optString("name")) == norm
        }?.optLong("id")
    }

    /** /tv/{id} → her liked show with its latest and next season dates. */
    fun parseFollowed(json: String, rated: Rated, today: LocalDate): FollowedShow {
        val o = JSONObject(json)
        val seasons = (o.optJSONArray("seasons") ?: JSONArray()).objects().mapNotNull { s ->
            val n = s.optInt("season_number")
            if (n < 1) return@mapNotNull null // specials
            s.optString("air_date").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { n to it }
        }
        val latest = seasons.filter { !it.second.isAfter(today) }.maxByOrNull { it.second }
        val nextFromSeasons = seasons.filter { it.second.isAfter(today) }.minByOrNull { it.second }
        val nextEp = o.optJSONObject("next_episode_to_air")
        val nextFromEpisode = nextEp?.takeIf { it.optInt("episode_number") == 1 }?.let { ep ->
            runCatching { LocalDate.parse(ep.optString("air_date")) }.getOrNull()?.takeIf { it.isAfter(today) }
                ?.let { ep.optInt("season_number") to it }
        }
        val service = (o.optJSONArray("networks") ?: JSONArray()).objects().firstOrNull()?.optString("name")
        val item = AngItem(
            key = rated.key,
            title = o.optString("name").ifBlank { rated.title },
            detail = "",
            imageUrl = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { "https://image.tmdb.org/t/p/w342$it" },
            service = service,
            date = null,
            badge = null,
            tmdbId = o.optLong("id"),
        )
        return FollowedShow(item, latest, nextFromSeasons ?: nextFromEpisode)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
