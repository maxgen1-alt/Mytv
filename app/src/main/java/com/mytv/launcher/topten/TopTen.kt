package com.mytv.launcher.topten

import com.mytv.launcher.sports.Service
import org.json.JSONArray
import org.json.JSONObject

/** A streaming service that gets a Top 10 above its app tile. */
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
)

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
            )
        }
    }

    /** Shows and movies together, most popular first. */
    fun topTen(lists: List<List<TopTitle>>): List<TopTitle> =
        lists.flatten()
            .distinctBy { (if (it.isMovie) "m" else "t") + it.id }
            .sortedByDescending { it.popularity }
            .take(10)

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
