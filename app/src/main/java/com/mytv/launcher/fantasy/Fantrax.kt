package com.mytv.launcher.fantasy

import android.webkit.CookieManager
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.NbaGames
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
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** Your fantasy league on Fantrax. */
object FantasyLeague {
    const val LEAGUE_ID = "mkj6sceamtvr8qje"
    const val MY_TEAM = "Midland Monarchs"
    /** Fantrax's own live matchup screen (mobile layout: fits half the TV). */
    const val MATCHUP_URL = "https://www.fantrax.com/fantasy/league/$LEAGUE_ID/livescoring?mobileMatchupView=true"
    const val LOGIN_URL = "https://www.fantrax.com/login"
}

data class FantasyPlayer(
    val id: String,
    val name: String,
    /** NBA team as Fantrax writes it ("LAL", "PHO"...). */
    val nbaTeam: String,
    val position: String,
)

/** This week's matchup: both teams and who's in their starting lineups today. */
data class FantasyWeek(
    val myTeam: String,
    val opponent: String,
    val mine: List<FantasyPlayer>,
    val theirs: List<FantasyPlayer>,
)

/** A starter whose NBA game is on right now. */
data class LivePlayer(val player: FantasyPlayer, val mine: Boolean, val game: Game)

/**
 * Fantrax: the league's public data (matchups, rosters, player names), and
 * whether you're signed in. The live scores themselves are only shown to
 * signed-in members, so the panel shows Fantrax's own matchup screen.
 */
class FantraxClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val base = "https://www.fantrax.com/fxea/general"
    private val leagueId = FantasyLeague.LEAGUE_ID
    // League info and the player list barely change: fetched at most every 6 hours.
    private var info: String? = null
    private var players: String? = null
    private var namesAt = 0L

    /** This week's matchup; null if Fantrax couldn't be reached. */
    suspend fun week(now: Long = System.currentTimeMillis()): FantasyWeek? = coroutineScope {
        val stale = now - namesAt > 6 * 3600_000L || info == null || players == null
        val infoBody = async { if (stale) get("$base/getLeagueInfo?leagueId=$leagueId") else info }
        val playersBody = async { if (stale) get("$base/getPlayerIds?sport=NBA") else players }
        val rosters = get("$base/getTeamRosters?leagueId=$leagueId") ?: return@coroutineScope null
        val i = infoBody.await() ?: info ?: return@coroutineScope null
        val p = playersBody.await() ?: players ?: return@coroutineScope null
        if (stale) { info = i; players = p; namesAt = now }
        withContext(Dispatchers.Default) {
            runCatching { FantraxData.week(i, rosters, p, FantasyLeague.MY_TEAM, now) }.getOrNull()
        }
    }

    /** True once Fantrax shows the league's live scoring to this TV (signed in through MyTV). */
    suspend fun signedIn(): Boolean {
        val cookies = runCatching { CookieManager.getInstance().getCookie("https://www.fantrax.com") }.getOrNull()
        if (cookies.isNullOrBlank()) return false
        val body = JSONObject().put(
            "msgs",
            JSONArray().put(JSONObject().put("method", "getLiveScoringStats").put("data", JSONObject().put("leagueId", leagueId))),
        ).toString()
        val reply = call(
            Request.Builder().url("https://www.fantrax.com/fxpa/req?leagueId=$leagueId")
                .header("Cookie", cookies)
                .post(body.toRequestBody("application/json".toMediaType())),
        ) ?: return false
        return !reply.contains("WARNING_NOT_LOGGED_IN") && !reply.contains("NOT_MEMBER_OF_LEAGUE") && reply.contains("\"responses\"")
    }

    private suspend fun get(url: String): String? = call(Request.Builder().url(url))

    private suspend fun call(builder: Request.Builder): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(builder.header("Accept", "application/json").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }
}

/** Reads Fantrax's public league data. */
object FantraxData {

    fun week(infoJson: String, rostersJson: String, playersJson: String, myTeam: String, now: Long): FantasyWeek? {
        val info = JSONObject(infoJson)
        val teams = info.optJSONObject("teamInfo") ?: return null
        val mine = teams.keys().asSequence().firstOrNull { teams.optJSONObject(it)?.optString("name").equals(myTeam, ignoreCase = true) }
            ?: return null

        // The scoring period (matchup week) we're in, else the next one, and who we play in it.
        val periods = info.optJSONArray("scoringPeriods")?.objects().orEmpty()
        val period = periods.firstOrNull { p ->
            val start = time(p.optString("startDate"))
            val end = time(p.optString("endDate"))
            start != null && end != null && now in start..end
        }?.optInt("number")
            ?: periods.firstOrNull { (time(it.optString("startDate")) ?: 0L) > now }?.optInt("number")
            ?: return null
        val pair = info.optJSONArray("matchups")?.objects()?.firstOrNull { it.optInt("period") == period }
            ?.optJSONArray("matchupList")?.objects()
            ?.firstOrNull { m -> m.optJSONObject("home")?.optString("id") == mine || m.optJSONObject("away")?.optString("id") == mine }
            ?: return null
        val them = pair.optJSONObject("home")?.optString("id")?.takeIf { it != mine } ?: pair.optJSONObject("away")?.optString("id").orEmpty()

        val directory = JSONObject(playersJson)
        val rosters = JSONObject(rostersJson).optJSONObject("rosters")
        fun starters(teamId: String): List<FantasyPlayer> =
            rosters?.optJSONObject(teamId)?.optJSONArray("rosterItems")?.objects().orEmpty()
                .filter { it.optString("status") == "ACTIVE" }
                .map { item ->
                    val id = item.optString("id")
                    val p = directory.optJSONObject(id)
                    FantasyPlayer(
                        id = id,
                        name = p?.optString("name")?.takeIf { it.isNotBlank() }?.let(::firstLast) ?: id,
                        nbaTeam = p?.optString("team").orEmpty().takeIf { it != "(N/A)" }.orEmpty(),
                        position = item.optString("position").ifBlank { p?.optString("position").orEmpty() },
                    )
                }

        return FantasyWeek(
            myTeam = teams.optJSONObject(mine)?.optString("name") ?: myTeam,
            opponent = teams.optJSONObject(them)?.optString("name") ?: "Opponent",
            mine = starters(mine),
            theirs = starters(them),
        )
    }

    /** Starters (both teams) whose NBA team is playing right now. */
    fun liveNow(week: FantasyWeek, games: List<Game>): List<LivePlayer> {
        val live = games.filter { it.state == GameState.LIVE }
        fun on(p: FantasyPlayer) = p.nbaTeam.takeIf { it.isNotBlank() }?.let { NbaGames.gameFor(live, it) }
        return week.mine.mapNotNull { p -> on(p)?.let { LivePlayer(p, true, it) } } +
            week.theirs.mapNotNull { p -> on(p)?.let { LivePlayer(p, false, it) } }
    }

    /** "2026-10-26T19:00:00.0-0400" -> millis. */
    fun time(s: String): Long? {
        val m = stamp.find(s) ?: return null
        return runCatching { OffsetDateTime.parse("${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}").toInstant().toEpochMilli() }.getOrNull()
    }

    private val stamp = Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?([+-]\\d{2})(\\d{2})")

    /** Fantrax lists people as "Doncic, Luka". */
    private fun firstLast(name: String): String {
        val parts = name.split(',').map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) "${parts[1]} ${parts[0]}" else name.trim()
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
