package com.mytv.launcher.ang

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "New shows you might like", from TMDB: romance / rom-com / coming-of-age /
 * dating shows with new episodes lately, on the streaming apps you have.
 */
object AngDiscovery {

    /** TMDB keywords that make a show hers (any of them). Looked up by name at runtime. */
    val wantedKeywords = listOf(
        "romance", "romantic comedy", "teen romance", "coming of age", "love triangle",
        "dating show", "dating", "reality dating show",
    )

    /** Never show these. */
    val blockedKeywords = listOf("erotic", "erotica", "erotic drama", "softcore", "sex comedy", "erotic thriller")

    /** Animation, documentary, kids, news, soap, talk. */
    private val skipGenres = setOf(16, 99, 10762, 10763, 10766, 10767)
    private val countries = setOf("US", "GB", "CA")

    /** /search/keyword → the id of the keyword named exactly [name], if any. */
    fun parseKeywordId(json: String, name: String): Int? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        return results.objects().firstOrNull { it.optString("name").equals(name, ignoreCase = true) }?.optInt("id")
    }

    fun discoverPath(today: LocalDate, providerIds: List<Int>, wanted: List<Int>, blocked: List<Int>): String =
        "/discover/tv?watch_region=US&with_watch_monetization_types=flatrate" +
            "&with_watch_providers=${providerIds.joinToString("|")}" +
            "&with_keywords=${wanted.joinToString("|")}" +
            (if (blocked.isNotEmpty()) "&without_keywords=${blocked.joinToString("|")}" else "") +
            "&with_original_language=en&include_adult=false" +
            "&air_date.gte=${today.minusDays(120)}&air_date.lte=$today" +
            "&sort_by=popularity.desc&language=en-US&page=1"

    /** One /discover/tv page → items labelled with [serviceName]. */
    fun parseDiscover(json: String, serviceName: String, today: LocalDate): List<AngItem> =
        (JSONObject(json).optJSONArray("results") ?: JSONArray()).objects().mapNotNull { o ->
            if (o.optBoolean("adult")) return@mapNotNull null
            if (o.optString("original_language") != "en") return@mapNotNull null
            val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            if (genres.any { it in skipGenres }) return@mapNotNull null
            val origin = o.optJSONArray("origin_country")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (origin.isNotEmpty() && origin.none { it in countries }) return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val firstAired = o.optString("first_air_date").takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val isNewShow = firstAired != null && ChronoUnit.DAYS.between(firstAired, today) in 0..120
            AngItem(
                key = "tmdb:" + o.optLong("id"),
                title = name,
                detail = listOfNotNull(firstAired?.year?.toString(), serviceName).joinToString(" · "),
                imageUrl = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { "https://image.tmdb.org/t/p/w342$it" },
                service = serviceName,
                date = firstAired,
                badge = if (isNewShow) "New show" else null,
                popularity = o.optDouble("popularity", 0.0),
            )
        }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
