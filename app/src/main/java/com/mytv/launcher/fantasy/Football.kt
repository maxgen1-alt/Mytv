package com.mytv.launcher.fantasy

import com.mytv.launcher.sports.EspnClient
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Your fantasy football league on ESPN (public: no sign-in needed). */
object FootballLeague {
    const val LEAGUE_ID = "31304429"
    const val MY_TEAM = "Skattebros"

    /** The NFL season a date belongs to (it starts in September). */
    fun season(today: LocalDate = LocalDate.now()) = if (today.monthValue >= 3) today.year else today.year - 1
}

/** A starter: their lineup slot, fantasy points so far, projection, and their NFL game this week. */
data class FootballPlayer(
    val name: String,
    val slot: String,
    /** ESPN's NFL team id (also the id on ESPN's NFL scoreboard). */
    val nflTeamId: String,
    val points: Double,
    val projected: Double?,
    val game: Game?,
) {
    val live: Boolean get() = game?.state == GameState.LIVE
}

data class FootballSide(
    val name: String,
    val logo: String?,
    val points: Double,
    val projected: Double?,
    /** ESPN's chance of winning, 0..1. */
    val winChance: Double?,
    val starters: List<FootballPlayer>,
)

data class FootballMatchup(val week: Int, val mine: FootballSide, val theirs: FootballSide) {
    val live: List<Pair<FootballPlayer, Boolean>> get() =
        mine.starters.filter { it.live }.map { it to true } + theirs.starters.filter { it.live }.map { it to false }
}

/** Reads ESPN Fantasy's league data (views mTeam, mBoxscore, mMatchupScore). */
object FootballData {

    /** Lineup slots, in the order ESPN shows them. */
    private val slots = linkedMapOf(
        0 to "QB", 2 to "RB", 3 to "RB/WR", 4 to "WR", 5 to "WR/TE", 6 to "TE", 23 to "FLEX", 7 to "OP", 16 to "D/ST", 17 to "K",
    )
    private val bench = setOf(20, 21, 24, 25)

    fun parse(json: String, myTeam: String, games: List<Game>): FootballMatchup? {
        val root = JSONObject(json)
        val teams = root.optJSONArray("teams").objects()
        val mine = teams.firstOrNull { it.optString("name").contains(myTeam, ignoreCase = true) } ?: return null
        val myId = mine.optInt("id")
        val period = root.optJSONObject("status")?.optInt("currentMatchupPeriod") ?: return null
        val week = root.optInt("scoringPeriodId", period)
        val matchup = root.optJSONArray("schedule").objects().firstOrNull { m ->
            m.optInt("matchupPeriodId") == period &&
                (m.optJSONObject("home")?.optInt("teamId") == myId || m.optJSONObject("away")?.optInt("teamId") == myId)
        } ?: return null
        val (me, them) = if (matchup.optJSONObject("home")?.optInt("teamId") == myId) "home" to "away" else "away" to "home"

        fun side(key: String): FootballSide? {
            val s = matchup.optJSONObject(key) ?: return null
            val team = teams.firstOrNull { it.optInt("id") == s.optInt("teamId") }
            val starters = s.optJSONObject("rosterForCurrentScoringPeriod")?.optJSONArray("entries").objects()
                .filter { it.optInt("lineupSlotId") !in bench }
                .sortedBy { e -> slots.keys.indexOf(e.optInt("lineupSlotId")).let { if (it < 0) 99 else it } }
                .mapNotNull { e ->
                    val pool = e.optJSONObject("playerPoolEntry") ?: return@mapNotNull null
                    val p = pool.optJSONObject("player") ?: return@mapNotNull null
                    val teamId = p.optInt("proTeamId").toString()
                    val projected = p.optJSONArray("stats").objects()
                        .firstOrNull { it.optInt("statSourceId") == 1 && it.optInt("scoringPeriodId") == week }
                        ?.optDouble("appliedTotal")?.takeIf { !it.isNaN() }
                    FootballPlayer(
                        name = p.optString("fullName"),
                        slot = slots[e.optInt("lineupSlotId")] ?: "",
                        nflTeamId = teamId,
                        points = pool.optDouble("appliedStatTotal", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                        projected = projected,
                        game = games.firstOrNull { it.involves(teamId) },
                    )
                }
            return FootballSide(
                name = team?.optString("name")?.trim() ?: "Opponent",
                logo = team?.optString("logo")?.takeIf { it.isNotBlank() },
                points = s.optDouble("totalPointsLive").takeIf { !it.isNaN() } ?: s.optDouble("totalPoints", 0.0),
                projected = s.optDouble("totalProjectedPointsLive").takeIf { !it.isNaN() } ?: s.optDouble("totalProjectedPoints").takeIf { !it.isNaN() },
                winChance = s.optDouble("winProbability").takeIf { !it.isNaN() },
                starters = starters,
            )
        }
        return FootballMatchup(week, side(me) ?: return null, side(them) ?: return null)
    }

    /** ESPN's week number, so the right NFL scoreboard can be fetched first. */
    fun week(json: String): Int? = runCatching { JSONObject(json).optInt("scoringPeriodId").takeIf { it > 0 } }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

/** Fetches the matchup live: ESPN Fantasy's box score plus this week's NFL scoreboard. */
class FootballLive(
    private val espn: EspnClient = EspnClient(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun matchup(): FootballMatchup? = coroutineScope {
        val url = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/${FootballLeague.season()}/segments/0/leagues/" +
            "${FootballLeague.LEAGUE_ID}?view=mTeam&view=mBoxscore&view=mMatchupScore"
        val json = get(url) ?: return@coroutineScope null
        val week = FootballData.week(json) ?: return@coroutineScope null
        val games = async { espn.nflWeek(week) }.await()
        withContext(Dispatchers.Default) { runCatching { FootballData.parse(json, FootballLeague.MY_TEAM, games) }.getOrNull() }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }
}
