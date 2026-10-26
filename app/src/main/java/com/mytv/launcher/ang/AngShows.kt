package com.mytv.launcher.ang

import java.time.LocalDate

/** How a curated show earns a spot in Ang's guide. */
enum class Follow {
    /** Always on the "Watch now" shelf. */
    ALWAYS,
    /** Shows up once a new season is out (and in Coming soon before that). */
    NEW_SEASONS,
}

/**
 * [query] is a TVmaze search. [hint] picks the right result when several
 * shows share a name: matched against the network / streaming service.
 * Several hints can be given as "Hulu|Netflix" (tried in order).
 * [caughtUpAsOf]: she's seen everything that premiered up to this date, so
 * only later seasons count as new.
 * [announced]: a premiere date we know before the TV databases do; [service]:
 * where it streams now, if that changed (e.g. a revival on another app).
 */
data class CuratedShow(
    val query: String,
    val follow: Follow,
    val hint: String? = null,
    val caughtUpAsOf: LocalDate? = null,
    val announced: LocalDate? = null,
    val service: String? = null,
)

object AngShows {
    private val caughtUp = LocalDate.of(2026, 10, 26)

    val curated = listOf(
        CuratedShow("The Kardashians", Follow.ALWAYS, hint = "Hulu"),

        CuratedShow("The Summer I Turned Pretty", Follow.NEW_SEASONS, hint = "Prime", caughtUpAsOf = caughtUp),
        CuratedShow("Sterling Point", Follow.NEW_SEASONS, caughtUpAsOf = caughtUp),
        CuratedShow("Off Campus", Follow.NEW_SEASONS, caughtUpAsOf = caughtUp),
        CuratedShow("Crew Girl", Follow.NEW_SEASONS, hint = "Netflix", caughtUpAsOf = caughtUp),
        CuratedShow("Love Island Australia", Follow.NEW_SEASONS, caughtUpAsOf = caughtUp),

        CuratedShow("Summer House", Follow.NEW_SEASONS, hint = "Bravo"),
        CuratedShow("Love Island", Follow.NEW_SEASONS, hint = "ITV"),
        CuratedShow("Love Island USA", Follow.NEW_SEASONS),
        // Revival hosted by Tinx: premieres Tue Nov 17 2026 on Hulu, finale Dec 18.
        CuratedShow("The Circle", Follow.NEW_SEASONS, hint = "Hulu|Netflix", announced = LocalDate.of(2026, 11, 17), service = "Hulu"),
    )

    /** A season counts as "new" for this many days after it premieres. */
    const val NEW_SEASON_DAYS = 60L



    /** English-language shows from the US, Canada or UK (null = a global streamer like Netflix). */
    private val englishCountries = setOf("US", "GB", "CA")

    private val datingWords = listOf(
        "love", "dating", "bachelor", "married", "marry", "single", "temptation",
        "perfect match", "ex on", "ultimatum", "match", "villa", "romance",
    )
    private val notHerGenres = setOf(
        "horror", "crime", "thriller", "anime", "science-fiction", "war", "espionage", "children", "adult",
    )

    fun isEnglish(show: TvShow): Boolean =
        show.language == "English" && (show.country == null || show.country in englishCountries)

    /**
     * "Her kind of show": rom-coms, rom-dramas and romance series, or
     * romantic / dating reality, on any service. English only.
     */
    fun isHerKindOfShow(show: TvShow): Boolean {
        if (!isEnglish(show)) return false
        val genres = show.genres.map { it.lowercase() }
        if (genres.any { it in notHerGenres }) return false
        val name = show.name.lowercase()
        return when (show.type) {
            "Scripted" -> "romance" in genres
            "Reality" -> "romance" in genres || datingWords.any { name.contains(it) }
            else -> false
        }
    }
}
