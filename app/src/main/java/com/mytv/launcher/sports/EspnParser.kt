package com.mytv.launcher.sports

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

enum class GameState { UPCOMING, LIVE, FINISHED }

data class Side(
    val teamId: String,
    val name: String,
    val abbreviation: String,
    val logoUrl: String?,
    val score: String?,
)

data class Game(
    val id: String,
    val league: League,
    val startMillis: Long,
    val state: GameState,
    val statusText: String,
    val home: Side,
    val away: Side,
    /** Network / service names exactly as ESPN lists them, e.g. "USA Net", "Peacock". */
    val broadcasts: List<String>,
) {
    fun opponentOf(teamId: String): Side = if (home.teamId == teamId) away else home
    fun isHome(teamId: String): Boolean = home.teamId == teamId
    fun involves(teamId: String): Boolean = home.teamId == teamId || away.teamId == teamId
}

data class StandingRow(
    val rank: Int,
    val teamId: String,
    val name: String,
    val logoUrl: String?,
    /** Values for [Standings.columns], same order. */
    val values: List<String>,
)

data class Standings(
    val title: String,
    val columns: List<String>,
    val rows: List<StandingRow>,
)

/**
 * Parses ESPN's public site API. The scoreboard and team-schedule endpoints
 * return the same "event" objects with small differences (score as string vs
 * object, logo vs logos[], broadcasts names[] vs media.shortName), so
 * everything here is deliberately tolerant.
 */
object EspnParser {

    fun parseEvents(json: String, league: League): List<Game> {
        val root = JSONObject(json)
        val events = root.optJSONArray("events") ?: return emptyList()
        return events.objects().mapNotNull { parseEvent(it, league) }
    }

    private fun parseEvent(event: JSONObject, league: League): Game? {
        val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors")?.objects() ?: return null
        val home = competitors.firstOrNull { it.optString("homeAway") == "home" } ?: competitors.getOrNull(0) ?: return null
        val away = competitors.firstOrNull { it.optString("homeAway") == "away" } ?: competitors.getOrNull(1) ?: return null

        val statusType = (comp.optJSONObject("status") ?: event.optJSONObject("status"))
            ?.optJSONObject("type")
        val state = when (statusType?.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINISHED
            else -> GameState.UPCOMING
        }
        val statusText = statusType?.optString("shortDetail").orEmpty()
            .ifBlank { statusType?.optString("detail").orEmpty() }

        val date = event.optString("date").ifBlank { comp.optString("date") }
        val start = parseDate(date) ?: return null

        return Game(
            id = event.optString("id"),
            league = league,
            startMillis = start,
            state = state,
            statusText = statusText,
            home = parseSide(home),
            away = parseSide(away),
            broadcasts = parseBroadcasts(comp),
        )
    }

    private fun parseSide(c: JSONObject): Side {
        val team = c.optJSONObject("team") ?: JSONObject()
        val logo = team.optString("logo").ifBlank {
            team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
        }
        val score = when (val s = c.opt("score")) {
            is JSONObject -> s.optString("displayValue").ifBlank { null }
            is String -> s.ifBlank { null }
            is Number -> s.toInt().toString()
            else -> null
        }
        return Side(
            teamId = team.optString("id").ifBlank { c.optString("id") },
            name = team.optString("shortDisplayName").ifBlank { team.optString("displayName") },
            abbreviation = team.optString("abbreviation"),
            logoUrl = logo.ifBlank { null },
            score = score,
        )
    }

    fun parseBroadcasts(comp: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.objects()?.forEach { b ->
            b.optJSONArray("names")?.let { names ->
                for (i in 0 until names.length()) names.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
            }
            b.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        comp.optJSONArray("geoBroadcasts")?.objects()?.forEach { b ->
            b.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out.toList()
    }

    fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            // ESPN sometimes omits seconds: "2025-08-17T15:30Z"
            ?: runCatching { OffsetDateTime.parse(s.replace("Z", ":00Z")).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Returns the standings group (league table or conference) that contains [teamId]. */
    fun parseStandings(json: String, league: League, teamId: String): Standings? {
        val root = JSONObject(json)
        val groups = mutableListOf<Pair<String, JSONArray>>()
        root.optJSONArray("children")?.objects()?.forEach { child ->
            child.optJSONObject("standings")?.optJSONArray("entries")?.let {
                groups += child.optString("name").ifBlank { league.displayName } to it
            }
        }
        root.optJSONObject("standings")?.optJSONArray("entries")?.let {
            groups += league.displayName to it
        }
        val (title, entries) = groups.firstOrNull { (_, entries) ->
            entries.objects().any { it.optJSONObject("team")?.optString("id") == teamId }
        } ?: groups.firstOrNull() ?: return null

        val soccer = league.sport == "soccer"
        val columns = if (soccer) {
            listOf("GP" to "gamesPlayed", "W" to "wins", "D" to "ties", "L" to "losses", "GD" to "pointDifferential", "PTS" to "points")
        } else {
            listOf("W" to "wins", "L" to "losses", "PCT" to "winPercent", "GB" to "gamesBehind", "STRK" to "streak")
        }
        val rankStat = if (soccer) "rank" else "playoffSeed"

        val rows = entries.objects().mapIndexed { index, entry ->
            val team = entry.optJSONObject("team") ?: JSONObject()
            val stats = entry.optJSONArray("stats")?.objects().orEmpty().associateBy { it.optString("name") }
            fun stat(name: String) = stats[name]?.let { s ->
                s.optString("displayValue").ifBlank { s.opt("value")?.toString().orEmpty() }
            }.orEmpty()
            StandingRow(
                rank = stats[rankStat]?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt() ?: (index + 1),
                teamId = team.optString("id"),
                name = team.optString("shortDisplayName").ifBlank { team.optString("displayName") },
                logoUrl = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href"),
                values = columns.map { stat(it.second) },
            )
        }.sortedBy { it.rank }

        return Standings(title = title, columns = columns.map { it.first }, rows = rows)
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }
}
