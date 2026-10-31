package com.mytv.launcher.fantasy

import com.mytv.launcher.sports.EspnClient
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.NbaGames
import com.mytv.launcher.sports.Teams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Your fantasy league on Fantrax. */
object FantasyLeague {
    const val LEAGUE_ID = "mkj6sceamtvr8qje"
    const val MY_TEAM = "Midland Monarchs"
}

data class FantasyPlayer(
    val id: String,
    val name: String,
    /** NBA team as Fantrax writes it ("LAL", "PHO"...). */
    val nbaTeam: String,
    val position: String,
)

/** A starter today: their game (if they play today) and their line in it so far. */
data class LineupPlayer(val player: FantasyPlayer, val mine: Boolean, val game: Game?, val line: StatLine?) {
    val live: Boolean get() = game?.state == GameState.LIVE
}

/** The whole matchup, worked out from the league's lineups and the NBA box scores. */
data class FantasyMatchup(
    val myTeam: String,
    val opponent: String,
    val period: Int,
    val week: Record,
    val weekCategories: List<CategoryResult>,
    val today: Record,
    val todayCategories: List<CategoryResult>,
    /** Today's starters, yours first, playing now first. */
    val players: List<LineupPlayer>,
) {
    val live: List<LineupPlayer> get() = players.filter { it.live }
}

/** A day of the league (Fantrax sets lineups per day): its number and when it starts and ends. */
data class LeagueDay(val number: Int, val start: Long, val end: Long)

/** What the league info says about this week's matchup. */
data class WeekSetup(
    val myId: String,
    val myName: String,
    val opponentId: String,
    val opponentName: String,
    val period: Int,
    val categories: List<Category>,
    /** The league days of this week that have started. */
    val days: List<LeagueDay>,
)

/** Reads Fantrax's public league data (no sign-in needed). */
object FantraxData {

    fun week(infoJson: String, myTeam: String, now: Long): WeekSetup? {
        val info = JSONObject(infoJson)
        val teams = info.optJSONObject("teamInfo") ?: return null
        val mine = teams.keys().asSequence().firstOrNull { teams.optJSONObject(it)?.optString("name").equals(myTeam, ignoreCase = true) }
            ?: return null

        // The scoring period (matchup week) we're in, else the next one.
        val periods = info.optJSONArray("scoringPeriods").objects()
        val current = periods.firstOrNull { p ->
            val start = time(p.optString("startDate"))
            val end = time(p.optString("endDate"))
            start != null && end != null && now in start..end
        } ?: periods.firstOrNull { (time(it.optString("startDate")) ?: 0L) > now } ?: return null
        val period = current.optInt("number")
        val weekStart = time(current.optString("startDate")) ?: return null
        val weekEnd = time(current.optString("endDate")) ?: return null

        val pair = info.optJSONArray("matchups").objects().firstOrNull { it.optInt("period") == period }
            ?.optJSONArray("matchupList").objects()
            .firstOrNull { m -> m.optJSONObject("home")?.optString("id") == mine || m.optJSONObject("away")?.optString("id") == mine }
            ?: return null
        val them = pair.optJSONObject("home")?.optString("id")?.takeIf { it != mine } ?: pair.optJSONObject("away")?.optString("id").orEmpty()

        val days = info.optJSONArray("rosterPeriods").objects().mapNotNull { d ->
            val start = time(d.optString("startDate")) ?: return@mapNotNull null
            val end = time(d.optString("endDate")) ?: return@mapNotNull null
            LeagueDay(d.optInt("number"), start, end).takeIf { start >= weekStart - 60_000L && start <= weekEnd && start <= now }
        }.sortedBy { it.number }

        // The league's own categories, in the order Fantrax lists them.
        val categories = info.optJSONObject("scoringSystem")?.optJSONArray("scoringCategorySettings").objects()
            .flatMap { it.optJSONArray("configs").objects() }
            .mapNotNull { Category.byCode(it.optJSONObject("scoringCategory")?.optString("shortName").orEmpty()) }
            .distinct()
            .ifEmpty { Category.entries }

        return WeekSetup(
            myId = mine,
            myName = teams.optJSONObject(mine)?.optString("name") ?: myTeam,
            opponentId = them,
            opponentName = teams.optJSONObject(them)?.optString("name") ?: "Opponent",
            period = period,
            categories = categories,
            days = days,
        )
    }

    /** A team's starters for one league day (its getTeamRosters reply). */
    fun starters(rostersJson: String, teamId: String, playersJson: String): List<FantasyPlayer> {
        val directory = JSONObject(playersJson)
        return JSONObject(rostersJson).optJSONObject("rosters")?.optJSONObject(teamId)?.optJSONArray("rosterItems").objects()
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
    }

    /** "2026-10-26T19:00:00.0-0400" -> millis. */
    fun time(s: String): Long? {
        val m = stamp.find(s) ?: return null
        return runCatching { OffsetDateTime.parse("${m.groupValues[1]}${m.groupValues[2]}:${m.groupValues[3]}").toInstant().toEpochMilli() }.getOrNull()
    }

    private val stamp = Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?([+-]\\d{2})(\\d{2})")

    /** Fantrax lists people as "Doncic, Luka". */
    fun firstLast(name: String): String {
        val parts = name.split(',').map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) "${parts[1]} ${parts[0]}" else name.trim()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

/**
 * Builds the matchup live: Fantrax's public lineups for each day of the week,
 * plus every starter's line from ESPN's box scores, added up category by
 * category the way Fantrax scores it. Days that are over are kept.
 */
class FantasyLive(
    private val espn: EspnClient = EspnClient(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val base = "https://www.fantrax.com/fxea/general"
    private val leagueId = FantasyLeague.LEAGUE_ID
    private val eastern = ZoneId.of("America/New_York")

    // League info and the player list barely change: at most every 6 hours.
    private var info: String? = null
    private var players: String? = null
    private var namesAt = 0L

    private data class Day(val mine: List<LineupPlayer>, val theirs: List<LineupPlayer>) {
        fun total(list: List<LineupPlayer>) = list.mapNotNull { it.line }.fold(StatLine()) { a, b -> a + b }
    }

    /** Finished days, by league day number. */
    private val done = ConcurrentHashMap<Int, Day>()

    suspend fun matchup(now: Long = System.currentTimeMillis()): FantasyMatchup? = coroutineScope {
        val stale = now - namesAt > 6 * 3600_000L || info == null || players == null
        if (stale) {
            val i = async { get("$base/getLeagueInfo?leagueId=$leagueId") }
            val p = async { get("$base/getPlayerIds?sport=NBA") }
            i.await()?.let { info = it }
            p.await()?.let { players = it }
            if (info != null && players != null) namesAt = now
        }
        val infoJson = info ?: return@coroutineScope null
        val playersJson = players ?: return@coroutineScope null
        val setup = withContext(Dispatchers.Default) { runCatching { FantraxData.week(infoJson, FantasyLeague.MY_TEAM, now) }.getOrNull() }
            ?: return@coroutineScope null
        if (setup.days.isEmpty()) {
            return@coroutineScope FantasyMatchup(setup.myName, setup.opponentName, setup.period, Record(0, 0, 0), emptyList(), Record(0, 0, 0), emptyList(), emptyList())
        }

        val days = setup.days.map { d -> async { done[d.number] ?: day(d, setup, playersJson, now) } }.awaitAll()
        if (days.any { it == null }) return@coroutineScope null
        val all = days.filterNotNull()
        val today = all.last()

        fun sum(pick: (Day) -> StatLine) = all.fold(StatLine()) { a, d -> a + pick(d) }
        val weekCats = Scoring.compare(sum { it.total(it.mine) }, sum { it.total(it.theirs) }, setup.categories)
        val todayCats = Scoring.compare(today.total(today.mine), today.total(today.theirs), setup.categories)
        val order = compareBy<LineupPlayer>({ !it.mine }, { if (it.live) 0 else if (it.game?.state == GameState.UPCOMING) 1 else if (it.game != null) 2 else 3 }, { -(it.line?.pts ?: 0) })
        FantasyMatchup(
            myTeam = setup.myName,
            opponent = setup.opponentName,
            period = setup.period,
            week = Scoring.record(weekCats),
            weekCategories = weekCats,
            today = Scoring.record(todayCats),
            todayCategories = todayCats,
            players = (today.mine + today.theirs).sortedWith(order),
        )
    }

    /** One league day: both lineups, their games and their lines. Null if it couldn't be fetched. */
    private suspend fun day(d: LeagueDay, setup: WeekSetup, playersJson: String, now: Long): Day? = coroutineScope {
        val rosters = get("$base/getTeamRosters?leagueId=$leagueId&period=${d.number}") ?: return@coroutineScope null
        val mine = FantraxData.starters(rosters, setup.myId, playersJson)
        val theirs = FantraxData.starters(rosters, setup.opponentId, playersJson)

        // The day's NBA games: the US dates it spans, games tipping off inside it.
        val first = Instant.ofEpochMilli(d.start).atZone(eastern).toLocalDate()
        val last = Instant.ofEpochMilli(d.end).atZone(eastern).toLocalDate()
        val dates = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        val games = dates.map { async { espn.scoreboard(Teams.nba, it) } }.awaitAll().flatten()
            .filter { it.startMillis in d.start..d.end }
            .distinctBy { it.id }

        fun gameOf(p: FantasyPlayer) = p.nbaTeam.takeIf { it.isNotBlank() }?.let { NbaGames.gameFor(games, it) }
        val needed = (mine + theirs).mapNotNull(::gameOf).filter { it.state != GameState.UPCOMING }.distinctBy { it.id }
        val boxes = needed.map { g ->
            async { g.id to (espn.summary(Teams.nba, g.id)?.let { runCatching { BoxScore.parse(it) }.getOrNull() } ?: emptyList()) }
        }.awaitAll().toMap()

        fun lineOf(p: FantasyPlayer, g: Game?): StatLine? {
            val box = g?.let { boxes[it.id] } ?: return null
            val key = BoxScore.nameKey(p.name)
            val team = NbaGames.espnAbbr(p.nbaTeam)
            return (box.firstOrNull { NbaGames.espnAbbr(it.team) == team && BoxScore.nameKey(it.name) == key }
                ?: box.firstOrNull { BoxScore.nameKey(it.name) == key })?.line
        }
        fun lineup(list: List<FantasyPlayer>, isMine: Boolean) = list.map { p -> gameOf(p).let { g -> LineupPlayer(p, isMine, g, lineOf(p, g)) } }

        val day = Day(lineup(mine, true), lineup(theirs, false))
        // Over and every game final: never changes again.
        if (d.end < now && games.all { it.state == GameState.FINISHED } && needed.all { boxes[it.id].orEmpty().isNotEmpty() }) done[d.number] = day
        day
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }
}
