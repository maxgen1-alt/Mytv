package com.mytv.launcher.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * NBA League Pass on Prime Video: finds a game's own page so Play opens that
 * exact game in Prime Video (not Prime's home screen). The League Pass
 * channel page on primevideo.com lists the games around today as cards,
 * "Away vs. Home", each with its Prime Video title id (GTI).
 */
object LeaguePass {
    const val CHANNEL_URL = "https://www.primevideo.com/-/en/channel/7a36cb2b-40e6-40c7-809f-a6cf9b9f0859"

    /** Prime Video's dates are US Eastern ("Live at 7:30 PM EDT", "Tomorrow 3 PM EDT"). */
    private val eastern: ZoneId = ZoneId.of("America/New_York")

    data class Card(val title: String, val gti: String, val time: String, val live: Boolean)

    /** Prime Video's own link for a title: opens that game's page in the Prime Video app. */
    fun link(card: Card) = "https://app.primevideo.com/detail?gti=${card.gti}"

    fun parse(html: String): List<Card> {
        val out = mutableListOf<Card>()
        var i = html.indexOf("data-card-title=\"")
        while (i >= 0) {
            val titleStart = i + "data-card-title=\"".length
            val title = unescape(html.substring(titleStart, html.indexOf('"', titleStart).coerceAtLeast(titleStart)))
            val next = html.indexOf("data-card-title=\"", titleStart)
            val end = html.indexOf("</article>", titleStart).let { if (it < 0) html.length else it }
            val body = html.substring(titleStart, if (next in 0 until end) next else end)
            val gti = gtiPattern.find(body)?.value
            if (gti != null && title.contains(" vs. ")) {
                // The last line under the card: "Live at 7:30 PM EDT", "Tomorrow 8 PM EDT", "Thu, Oct 29"...
                val lines = paragraph.findAll(body).map { tags.replace(it.groupValues[1], "").trim() }.filter { it.isNotEmpty() }.toList()
                val time = lines.lastOrNull()?.takeIf { it != title }.orEmpty()
                out += Card(title, gti, unescape(time), live = body.contains("aria-label=\"LIVE\""))
            }
            i = next
        }
        return out.distinctBy { it.gti }
    }

    /**
     * The card for [game]: both teams, and the same day (US Eastern). A game
     * that's on now matches the card with the LIVE badge.
     */
    fun find(cards: List<Card>, game: Game, now: Long = System.currentTimeMillis()): Card? {
        val teams = setOf(game.away.name.lowercase().trim(), game.home.name.lowercase().trim())
        val matching = cards.filter { c ->
            val sides = c.title.split(" vs. ").map { it.lowercase().trim() }
            sides.size == 2 && sides.toSet() == teams
        }
        if (matching.isEmpty()) return null
        val day = Instant.ofEpochMilli(game.startMillis).atZone(eastern).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(eastern).toLocalDate()
        return matching.firstOrNull { game.state == GameState.LIVE && it.live }
            ?: matching.firstOrNull { cardDay(it, today) == day }
    }

    /** The day a card is for, from its time line; null if it can't tell. */
    fun cardDay(card: Card, today: LocalDate): LocalDate? {
        val t = card.time
        return when {
            card.live || t.startsWith("Live", ignoreCase = true) || t.startsWith("Today", ignoreCase = true) -> today
            t.startsWith("Tomorrow", ignoreCase = true) -> today.plusDays(1)
            else -> dated.find(t)?.let { m ->
                val month = months.indexOf(m.groupValues[1].lowercase().take(3)) + 1
                val dayOfMonth = m.groupValues[2].toInt()
                if (month == 0) return null
                // No year on the card: the one nearest today.
                listOf(today.year - 1, today.year, today.year + 1)
                    .mapNotNull { y -> runCatching { LocalDate.of(y, month, dayOfMonth) }.getOrNull() }
                    .minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }
            }
        }
    }

    private val gtiPattern = Regex("amzn1\\.dv\\.gti\\.[0-9a-f-]{36}")
    private val paragraph = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val tags = Regex("<[^>]+>")
    private val dated = Regex("(?:[A-Za-z]{3}, )?([A-Za-z]{3}) (\\d{1,2})\\b")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"")
}

/** Fetches the League Pass channel page (at most every 5 minutes). */
class LeaguePassClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private var cards: List<LeaguePass.Card> = emptyList()
    private var fetchedAt = 0L

    suspend fun cards(): List<LeaguePass.Card> = withContext(Dispatchers.IO) {
        if (cards.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 5 * 60_000L) return@withContext cards
        val html = runCatching {
            http.newCall(Request.Builder().url(LeaguePass.CHANNEL_URL).header("Accept-Language", "en-US").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull() ?: return@withContext cards
        val parsed = runCatching { LeaguePass.parse(html) }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) {
            cards = parsed
            fetchedAt = System.currentTimeMillis()
        }
        cards
    }
}
