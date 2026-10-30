package com.mytv.launcher.fantasy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Your fantasy league on Fantrax. */
object FantasyLeague {
    const val LEAGUE_ID = "mkj6sceamtvr8qje"
    const val MY_TEAM = "Midland Monarchs"
}

enum class FState { NONE, UPCOMING, LIVE, FINAL }

data class FPlayer(
    val id: String,
    val name: String,
    /** NBA team abbreviation as Fantrax writes it ("LAL", "GSW"...). */
    val nbaTeam: String,
    val position: String,
    val points: Double,
    /** Fantrax's game line ("@BOS 88-90 Q3 5:21"), or the tip-off time. */
    val status: String,
    val state: FState,
)

data class FTeam(val id: String, val name: String, val points: Double, val players: List<FPlayer>)

data class FMatchup(val mine: FTeam, val them: FTeam)

sealed interface FantasyResult {
    data class Ok(val matchup: FMatchup) : FantasyResult
    data class Error(val message: String) : FantasyResult
}

/**
 * Fantrax has no official live-scoring API; this uses what its own web app
 * calls (fxpa/req getLiveScoringStats) plus the public fxea endpoints for
 * team and player names.
 */
class FantraxClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val leagueId = FantasyLeague.LEAGUE_ID
    // Team and player names barely change: fetched at most every 12 hours.
    private var leagueInfo: String? = null
    private var players: String? = null
    private var namesAt = 0L

    suspend fun matchup(myTeam: String = FantasyLeague.MY_TEAM): FantasyResult = coroutineScope {
        val stale = System.currentTimeMillis() - namesAt > 12 * 3600_000L
        val info = async { if (stale || leagueInfo == null) get("https://www.fantrax.com/fxea/general/getLeagueInfo?leagueId=$leagueId") else leagueInfo }
        val ids = async { if (stale || players == null) get("https://www.fantrax.com/fxea/general/getPlayerIds?sport=NBA") else players }
        val live = post(
            "https://www.fantrax.com/fxpa/req?leagueId=$leagueId",
            JSONObject().put(
                "msgs",
                JSONArray().put(JSONObject().put("method", "getLiveScoringStats").put("data", JSONObject().put("leagueId", leagueId).put("newView", true))),
            ).toString(),
        ) ?: return@coroutineScope FantasyResult.Error("Couldn't reach Fantrax")
        val infoBody = info.await()
        val idsBody = ids.await()
        if (infoBody != null && idsBody != null) {
            leagueInfo = infoBody
            players = idsBody
            namesAt = System.currentTimeMillis()
        }
        withContext(Dispatchers.Default) {
            runCatching { FantraxParser.parse(live, infoBody ?: leagueInfo, idsBody ?: players, myTeam) }
                .getOrElse { FantasyResult.Error("Couldn't read Fantrax's scores") }
        }
    }

    private suspend fun get(url: String): String? = call(Request.Builder().url(url))

    private suspend fun post(url: String, json: String): String? =
        call(Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType())))

    private suspend fun call(builder: Request.Builder): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = builder.header("Accept", "application/json").build()
            http.newCall(request).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        }.getOrNull()
    }
}

/** Turns Fantrax's responses into the matchup. Tolerant: the format is undocumented. */
object FantraxParser {

    fun parse(liveJson: String, leagueInfoJson: String?, playersJson: String?, myTeam: String): FantasyResult {
        val root = JSONObject(liveJson)
        root.optJSONObject("pageError")?.let { return error(it) }
        val first = root.optJSONArray("responses")?.optJSONObject(0)
        first?.optJSONObject("pageError")?.let { return error(it) }
        val data = first?.optJSONObject("data") ?: return FantasyResult.Error("Fantrax sent no scores")
        data.optJSONObject("pageError")?.let { return error(it) }

        // Team names: the league info (public), plus anything named in the live data.
        val teamNames = HashMap<String, String>()
        leagueInfoJson?.let { runCatching { collectTeams(JSONObject(it), teamNames) } }
        collectTeams(data, teamNames)

        val pairs = matchups(data)
        val mine = teamNames.entries.firstOrNull { it.value.equals(myTeam, ignoreCase = true) }?.key
            ?: teamNames.entries.firstOrNull { it.value.contains(myTeam, ignoreCase = true) }?.key
            ?: return FantasyResult.Error("Couldn't find $myTeam in the league")
        val them = pairs.firstNotNullOfOrNull { (a, b) -> if (a == mine) b else if (b == mine) a else null }
            ?: return FantasyResult.Error("$myTeam has no matchup right now")

        // Players: names and NBA teams from the live data's scorers, else Fantrax's player list.
        val scorers = HashMap<String, Triple<String, String, String>>()
        collectScorers(data, scorers)
        val directory = playersJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val statuses = HashMap<String, String>()
        collectStatuses(data, statuses)

        val allStats = data.optJSONObject("statsPerTeam")?.optJSONObject("allTeamsStats")
        fun team(id: String): FTeam {
            val slots = allStats?.optJSONObject(id)
            val slot = slots?.optJSONObject("ACTIVE") ?: slots?.let { s -> s.keys().asSequence().mapNotNull { s.optJSONObject(it) }.firstOrNull() }
            val stats = slot?.optJSONObject("statsMap")
            val players = stats?.keys()?.asSequence().orEmpty()
                .filter { !it.startsWith("_") }
                .map { pid ->
                    val known = scorers[pid]
                    val listed = directory?.optJSONObject(pid)
                    val (status, state) = gameStatus(statuses[pid])
                    FPlayer(
                        id = pid,
                        name = known?.first?.ifBlank { null } ?: listed?.optString("name")?.takeIf { it.isNotBlank() }?.let(::firstLast) ?: pid,
                        nbaTeam = known?.second?.ifBlank { null } ?: listed?.optString("team").orEmpty(),
                        position = known?.third?.ifBlank { null } ?: listed?.optString("position").orEmpty(),
                        points = number(stats?.opt(pid)),
                        status = status,
                        state = state,
                    )
                }
                .sortedWith(compareBy<FPlayer>({ order(it.state) }, { -it.points }))
                .toList()
            val total = slot?.takeIf { it.has("totalFpts") }?.let { number(it.opt("totalFpts")) } ?: players.sumOf { it.points }
            return FTeam(id, teamNames[id] ?: "Opponent", total, players)
        }
        return FantasyResult.Ok(FMatchup(team(mine), team(them)))
    }

    private fun error(e: JSONObject): FantasyResult.Error = FantasyResult.Error(
        when (e.optString("code")) {
            "WARNING_NOT_LOGGED_IN", "NOT_MEMBER_OF_LEAGUE" -> "Fantrax only shows this league to signed-in members"
            else -> e.optString("title").ifBlank { "Fantrax error" }
        },
    )

    /** "a_b" matchup ids, as strings or as home/away objects. */
    private fun matchups(data: JSONObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val list = data.optJSONArray("matchups") ?: return out
        for (i in 0 until list.length()) {
            when (val m = list.opt(i)) {
                is String -> m.split('_').takeIf { it.size == 2 }?.let { out += it[0] to it[1] }
                is JSONObject -> {
                    val a = m.optJSONObject("home")?.optString("id") ?: m.optString("team1")
                    val b = m.optJSONObject("away")?.optString("id") ?: m.optString("team2")
                    if (!a.isNullOrBlank() && !b.isNullOrBlank()) out += a to b
                }
            }
        }
        return out
    }

    /** Every {id|teamId, name|teamName} object anywhere in [json]. */
    private fun collectTeams(json: Any?, out: MutableMap<String, String>) {
        when (json) {
            is JSONObject -> {
                val id = json.optString("teamId").ifBlank { json.optString("id") }
                val name = json.optString("teamName").ifBlank { json.optString("name") }
                if (id.isNotBlank() && name.isNotBlank() && !json.has("scorerId")) out.putIfAbsent(id, name)
                json.keys().forEach { collectTeams(json.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until json.length()) collectTeams(json.opt(i), out)
        }
    }

    /** Every scorer object: id -> (name, NBA team, position). */
    private fun collectScorers(json: Any?, out: MutableMap<String, Triple<String, String, String>>) {
        when (json) {
            is JSONObject -> {
                val id = json.optString("scorerId")
                if (id.isNotBlank()) {
                    out.putIfAbsent(
                        id,
                        Triple(
                            json.optString("name").let(::firstLast),
                            json.optString("teamShortName"),
                            json.optString("posShortNames").ifBlank { json.optString("posShortName") },
                        ),
                    )
                }
                json.keys().forEach { collectScorers(json.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until json.length()) collectScorers(json.opt(i), out)
        }
    }

    /** Every gameStatusMap, merged: player id -> "caption|eventId|code". */
    private fun collectStatuses(json: Any?, out: MutableMap<String, String>) {
        when (json) {
            is JSONObject -> json.keys().forEach { key ->
                val v = json.opt(key)
                if (key == "gameStatusMap" && v is JSONObject) v.keys().forEach { pid -> v.optString(pid).takeIf { it.isNotBlank() }?.let { out[pid] = it } }
                else collectStatuses(v, out)
            }
            is JSONArray -> for (i in 0 until json.length()) collectStatuses(json.opt(i), out)
        }
    }

    /**
     * "caption|eventId|code": code 1 = not started, 2 = live, 3 = final. Before
     * tip-off the caption is "@BOS~<tip-off millis>".
     */
    fun gameStatus(raw: String?): Pair<String, FState> {
        if (raw.isNullOrBlank()) return "" to FState.NONE
        val parts = raw.split('|')
        val state = when (parts.getOrNull(2)?.trim()) {
            "1" -> FState.UPCOMING
            "2" -> FState.LIVE
            "3" -> FState.FINAL
            else -> FState.NONE
        }
        var caption = parts[0]
        if (caption.contains('~')) {
            val (text, millis) = caption.split('~', limit = 2)
            val tip = millis.trim().toLongOrNull()?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            }
            caption = listOfNotNull(text.trim().ifBlank { null }, tip).joinToString(" ")
        }
        return caption.trim() to state
    }

    private fun order(state: FState) = when (state) {
        FState.LIVE -> 0
        FState.UPCOMING -> 1
        FState.FINAL -> 2
        FState.NONE -> 3
    }

    /** Points come as a number, a string, or {object1: points}. */
    private fun number(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> v.replace(",", "").toDoubleOrNull() ?: 0.0
        is JSONObject -> number(v.opt("object1"))
        else -> 0.0
    }

    /** Fantrax lists people as "James, LeBron". */
    private fun firstLast(name: String): String {
        val parts = name.split(',').map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) "${parts[1]} ${parts[0]}" else name.trim()
    }
}
