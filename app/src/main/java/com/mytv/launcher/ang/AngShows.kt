package com.mytv.launcher.ang

/** How a curated show earns a spot in Ang's guide. */
enum class Follow {
    /** Always on the "Watch now" shelf. */
    ALWAYS,
    /** She's caught up: only shows up once a new season is out (and in Upcoming before that). */
    CAUGHT_UP,
    /** Mostly about premiere dates: Upcoming, plus "Watch now" while a new season is airing. */
    TRACK,
}

/**
 * [query] is a TVmaze search. [hint] picks the right result when several
 * shows share a name: matched against the network / streaming service.
 */
data class CuratedShow(val query: String, val follow: Follow, val hint: String? = null)

object AngShows {
    val curated = listOf(
        CuratedShow("The Kardashians", Follow.ALWAYS, hint = "Hulu"),

        CuratedShow("The Summer I Turned Pretty", Follow.CAUGHT_UP, hint = "Prime"),
        CuratedShow("Sterling Point", Follow.CAUGHT_UP),
        CuratedShow("Off Campus", Follow.CAUGHT_UP),
        CuratedShow("Crew Girl", Follow.CAUGHT_UP, hint = "Netflix"),

        CuratedShow("Summer House", Follow.TRACK, hint = "Bravo"),
        CuratedShow("Love Island", Follow.TRACK, hint = "ITV"),
        CuratedShow("Love Island USA", Follow.TRACK),
        CuratedShow("Love Island Australia", Follow.TRACK),
    )

    /** A season counts as "new" for this many days after it premieres. */
    const val NEW_SEASON_DAYS = 60L

    /** How far back / ahead we scan streaming premieres for new shows of the same kind. */
    const val DISCOVER_PAST_DAYS = 21L
    const val DISCOVER_FUTURE_DAYS = 45L

    private val datingWords = listOf(
        "love", "dating", "bachelor", "married", "marry", "single", "temptation",
        "perfect match", "ex on", "ultimatum", "match",
    )
    private val notHerGenres = setOf("horror", "crime", "thriller", "anime", "science-fiction", "war", "espionage")

    /**
     * "Her kind of show": romantic coming-of-age / romance dramas on any
     * service, or romantic reality on Netflix.
     */
    fun isHerKindOfShow(show: TvShow): Boolean {
        if (show.language != null && show.language != "English") return false
        val genres = show.genres.map { it.lowercase() }
        if (genres.any { it in notHerGenres }) return false
        val name = show.name.lowercase()
        return when (show.type) {
            "Scripted" -> "romance" in genres && ("drama" in genres || "comedy" in genres)
            "Reality" -> (show.service ?: "").contains("netflix", ignoreCase = true) &&
                ("romance" in genres || datingWords.any { name.contains(it) })
            else -> false
        }
    }
}
