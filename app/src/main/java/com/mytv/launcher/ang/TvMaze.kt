package com.mytv.launcher.ang

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class TvSeason(val number: Int, val premiere: LocalDate?, val end: LocalDate?)

data class TvEpisode(val season: Int, val number: Int, val airdate: LocalDate?)

data class TvShow(
    val id: Long,
    val name: String,
    val type: String?,
    val language: String?,
    val genres: List<String>,
    /** Network or streaming service, e.g. "Netflix", "Bravo", "Prime Video". */
    val service: String?,
    /** ISO country of the network / streaming service; null for global streamers. */
    val country: String? = null,
    val imageUrl: String?,
    val seasons: List<TvSeason> = emptyList(),
    val nextEpisode: TvEpisode? = null,
)

/** A season premiere seen in TVmaze's streaming schedule. */
data class Premiere(val show: TvShow, val season: Int, val date: LocalDate)

/** Parses TVmaze's free public API (api.tvmaze.com). */
object TvMazeParser {

    fun parseShow(o: JSONObject): TvShow {
        val embedded = o.optJSONObject("_embedded")
        val channel = o.optJSONObject("webChannel")?.takeIf { it.optString("name").isNotBlank() }
            ?: o.optJSONObject("network")?.takeIf { it.optString("name").isNotBlank() }
        val service = channel?.optString("name")
        val country = channel?.optJSONObject("country")?.optStringOrNull("code")
        val image = o.optJSONObject("image")
        return TvShow(
            id = o.optLong("id"),
            name = o.optString("name"),
            type = o.optStringOrNull("type"),
            language = o.optStringOrNull("language"),
            genres = o.optJSONArray("genres")?.strings().orEmpty(),
            service = service,
            country = country,
            imageUrl = image?.optStringOrNull("medium")?.https() ?: image?.optStringOrNull("original")?.https(),
            seasons = embedded?.optJSONArray("seasons")?.objects()?.map {
                TvSeason(it.optInt("number"), date(it.optStringOrNull("premiereDate")), date(it.optStringOrNull("endDate")))
            }.orEmpty(),
            nextEpisode = embedded?.optJSONObject("nextepisode")?.let(::parseEpisode),
        )
    }

    fun parseEpisode(o: JSONObject) = TvEpisode(
        season = o.optInt("season"),
        number = o.optInt("number"),
        airdate = date(o.optStringOrNull("airdate")),
    )

    /** /search/shows → best match, preferring one whose network/service matches [hint]. */
    fun pickSearchResult(json: String, hint: String?): TvShow? {
        val shows = JSONArray(json).objects().mapNotNull { it.optJSONObject("show")?.let(::parseShow) }
        if (hint == null) return shows.firstOrNull()
        return shows.firstOrNull { it.service?.contains(hint, ignoreCase = true) == true } ?: shows.firstOrNull()
    }

    /** /schedule/web (episodes with _embedded.show) or /schedule (episodes with show) → season premieres. */
    fun parsePremieres(json: String): List<Premiere> =
        JSONArray(json).objects().mapNotNull { ep ->
            if (ep.optInt("number") != 1) return@mapNotNull null
            val showJson = ep.optJSONObject("_embedded")?.optJSONObject("show") ?: ep.optJSONObject("show") ?: return@mapNotNull null
            val date = date(ep.optStringOrNull("airdate")) ?: return@mapNotNull null
            Premiere(parseShow(showJson), ep.optInt("season"), date)
        }

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun String.https() = replaceFirst("http://", "https://")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
