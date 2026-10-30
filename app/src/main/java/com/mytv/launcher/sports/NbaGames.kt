package com.mytv.launcher.sports

/** The NBA page: every game of the day, live ones on top. */
object NbaGames {

    /**
     * The games to show for a day: that day's scoreboard, plus (for today) any
     * game from last night's scoreboard that is still on after midnight.
     * Live first, then upcoming by tip-off, then finals.
     */
    fun forDay(day: List<Game>, stillOnFromDayBefore: List<Game> = emptyList()): List<Game> =
        (stillOnFromDayBefore.filter { it.state == GameState.LIVE } + day)
            .distinctBy { it.id }
            .sortedWith(compareBy<Game>({ order(it.state) }, { it.startMillis }))

    private fun order(state: GameState) = when (state) {
        GameState.LIVE -> 0
        GameState.UPCOMING -> 1
        GameState.FINISHED -> 2
    }

    /** "Celtics @ Lakers". */
    fun title(game: Game): String = "${game.away.name} @ ${game.home.name}"

    /** Words a Prime Video listing for this game must contain: both nicknames ("Lakers", "Blazers"). */
    fun matchWords(game: Game): List<String> =
        listOf(game.away, game.home).map { it.name.trim().split(' ').last() }.filter { it.isNotBlank() }
}
