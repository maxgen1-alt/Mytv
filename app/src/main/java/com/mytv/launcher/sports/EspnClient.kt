package com.mytv.launcher.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches games and standings from ESPN's free, key-less public API. */
class EspnClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val base = "https://site.api.espn.com/apis/site/v2/sports"

    /**
     * The game to turn on right now: one that's live, or one that kicks off
     * within the next [preGameMinutes] minutes (so pressing the button just
     * before kick-off still works).
     */
    suspend fun findLiveGame(team: Team, preGameMinutes: Int = 20): Game? = coroutineScope {
        val now = System.currentTimeMillis()
        val games = team.leagues.map { league ->
            async { fetchGames("$base/${league.sport}/${league.code}/scoreboard", league) }
        }.awaitAll().flatten().filter { it.involves(team.espnId) }

        games.firstOrNull { it.state == GameState.LIVE }
            ?: games.firstOrNull {
                it.state == GameState.UPCOMING &&
                    it.startMillis - now in 0..preGameMinutes * 60_000L
            }
            // Backup: the scoreboard can be on a different day (late games) or briefly stale.
            // A game on the team's schedule that started in the last 3½ hours and isn't over is on now.
            ?: schedule(team).firstOrNull { g ->
                g.state != GameState.FINISHED && now - g.startMillis in -preGameMinutes * 60_000L..(3.5 * 3600_000L).toLong()
            }?.let { g -> if (g.startMillis <= now) g.copy(state = GameState.LIVE) else g }
    }

    /** Next few games across every competition, soonest first. */
    suspend fun upcomingGames(team: Team, limit: Int = 6): List<Game> = coroutineScope {
        val now = System.currentTimeMillis()
        team.leagues.flatMap { league ->
            val url = "$base/${league.sport}/${league.code}/teams/${team.espnId}/schedule"
            // Soccer schedules only include future fixtures with fixture=true.
            val urls = if (league.sport == "soccer") listOf("$url?fixture=true") else listOf(url)
            urls.map { async { fetchGames(it, league) } }
        }.awaitAll().flatten()
            .filter { it.state != GameState.FINISHED && it.startMillis > now - 3 * 3600_000L }
            .distinctBy { it.id }
            .sortedBy { it.startMillis }
            .take(limit)
    }

    /** Today's game from the live scoreboards (any state, with the score), if the team plays today. */
    suspend fun todaysGame(team: Team): Game? = coroutineScope {
        team.leagues.map { league ->
            async { fetchGames("$base/${league.sport}/${league.code}/scoreboard", league) }
        }.awaitAll().flatten().filter { it.involves(team.espnId) }
            .sortedBy { it.startMillis }
            .let { games -> games.firstOrNull { it.state == GameState.LIVE } ?: games.firstOrNull { it.state == GameState.UPCOMING } ?: games.lastOrNull() }
    }

    /** Every game on the team's schedules (results and fixtures), soonest first. */
    suspend fun schedule(team: Team): List<Game> = coroutineScope {
        team.leagues.flatMap { league ->
            val url = "$base/${league.sport}/${league.code}/teams/${team.espnId}/schedule"
            // Soccer: results without fixture=true, upcoming games with it.
            val urls = if (league.sport == "soccer") listOf(url, "$url?fixture=true") else listOf(url)
            urls.map { async { fetchGames(it, league) } }
        }.awaitAll().flatten().distinctBy { it.id }.sortedBy { it.startMillis }
    }

    /** Most recent finished games, newest first. */
    suspend fun recentResults(team: Team, limit: Int = 3): List<Game> = coroutineScope {
        team.leagues.map { league ->
            async { fetchGames("$base/${league.sport}/${league.code}/teams/${team.espnId}/schedule", league) }
        }.awaitAll().flatten()
            .filter { it.state == GameState.FINISHED }
            .distinctBy { it.id }
            .sortedByDescending { it.startMillis }
            .take(limit)
    }

    suspend fun standings(team: Team): Standings? {
        val league = team.standingsLeague
        // Note: standings live under /apis/v2, not /apis/site/v2.
        val url = "https://site.api.espn.com/apis/v2/sports/${league.sport}/${league.code}/standings"
        val body = get(url) ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { EspnParser.parseStandings(body, league, team.espnId) }.getOrNull()
        }
    }

    private suspend fun fetchGames(url: String, league: League): List<Game> = withContext(Dispatchers.IO) {
        val body = get(url) ?: return@withContext emptyList()
        runCatching { EspnParser.parseEvents(body, league) }.getOrDefault(emptyList())
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }
}
