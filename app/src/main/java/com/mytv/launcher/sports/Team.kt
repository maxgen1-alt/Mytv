package com.mytv.launcher.sports

/** A competition the team plays in, as named in ESPN's public API. */
data class League(
    val sport: String,
    val code: String,
    val displayName: String,
    /** Where this competition streams in the US when ESPN lists no broadcaster. */
    val defaultNetworks: List<String>,
)

data class Team(
    val key: String,
    val name: String,
    val espnId: String,
    val logoUrl: String,
    /** League whose table we show on the team screen. */
    val standingsLeague: League,
    /** Every competition we check for live / upcoming games. */
    val leagues: List<League>,
    /** Accent colour for the team screen, ARGB. */
    val color: Long,
)

object Teams {
    private val premierLeague = League("soccer", "eng.1", "Premier League", listOf("Peacock", "USA Net", "NBC"))
    private val championsLeague = League("soccer", "uefa.champions", "Champions League", listOf("Paramount+", "CBS"))
    private val faCup = League("soccer", "eng.fa", "FA Cup", listOf("ESPN+"))
    private val leagueCup = League("soccer", "eng.league_cup", "Carabao Cup", listOf("Paramount+"))
    val nba = League("basketball", "nba", "NBA", listOf("League Pass", "ESPN", "Prime Video", "Peacock", "NBA TV"))
    val nfl = League("football", "nfl", "NFL", listOf("CBS", "FOX", "NBC", "ESPN", "Prime Video"))

    val arsenal = Team(
        key = "arsenal",
        name = "Arsenal",
        espnId = "359",
        logoUrl = "https://a.espncdn.com/i/teamlogos/soccer/500/359.png",
        standingsLeague = premierLeague,
        leagues = listOf(premierLeague, championsLeague, faCup, leagueCup),
        color = 0xFFDB0007,
    )

    val lakers = Team(
        key = "lakers",
        name = "Lakers",
        espnId = "13",
        logoUrl = "https://a.espncdn.com/i/teamlogos/nba/500/lal.png",
        standingsLeague = nba,
        leagues = listOf(nba),
        color = 0xFF552583,
    )

    val all = listOf(arsenal, lakers)

    fun byKey(key: String): Team? = all.firstOrNull { it.key == key }
}
