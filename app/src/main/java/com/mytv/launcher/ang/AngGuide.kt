package com.mytv.launcher.ang

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AngItem(
    /** "tvmaze:123" or "tmdb:456": unique per show, used for rows and "Mark as watched". */
    val key: String,
    val title: String,
    /** e.g. "Season 3 · Prime Video" */
    val detail: String,
    val imageUrl: String?,
    val service: String?,
    /** Premiere date of the season this item is about. */
    val date: LocalDate?,
    /** Small label on the poster: "New season", "New show"... */
    val badge: String?,
    /** For ordering discovered shows. */
    val popularity: Double = 0.0,
)

data class AngGuide(
    /** Her shows with something to watch right now. */
    val watchNow: List<AngItem> = emptyList(),
    /** Shows of the same kind she might like, on apps you have. */
    val newShows: List<AngItem> = emptyList(),
    /** Dated premieres coming soon, soonest first. */
    val upcoming: List<AngItem> = emptyList(),
)

object AngGuideBuilder {

    /**
     * [watched]: item key → date she marked it watched (long-press).
     * [isAvailable]: whether a network / service name is on an app you have.
     * [discovered]: candidate "new shows" (from [AngDiscovery]).
     */
    fun build(
        today: LocalDate,
        curated: List<Pair<CuratedShow, TvShow>>,
        premieres: List<Premiere>,
        watched: Map<String, LocalDate> = emptyMap(),
        isAvailable: (String?) -> Boolean = { true },
        discovered: List<AngItem> = emptyList(),
    ): AngGuide {
        val watchNow = mutableListOf<Pair<AngItem, LocalDate?>>()
        val upcoming = mutableListOf<AngItem>()

        for ((spec, show) in curated) {
            val latest = show.seasons
                .filter { it.premiere != null && !it.premiere.isAfter(today) }
                .maxByOrNull { it.premiere!! }
            val caughtUp = listOfNotNull(spec.caughtUpAsOf, watched[keyOf(show)]).maxOrNull()
            val isNew = latest?.premiere?.let { premiere ->
                ChronoUnit.DAYS.between(premiere, today) <= AngShows.NEW_SEASON_DAYS &&
                    (caughtUp == null || premiere.isAfter(caughtUp))
            } == true

            if (spec.follow == Follow.ALWAYS || isNew) {
                watchNow += item(show, latest?.number, latest?.premiere, if (isNew) newLabel(latest!!.number) else null) to latest?.premiere
            }

            nextPremiere(show, today)?.let { (season, date) ->
                upcoming += item(show, season, date, newLabel(season))
            }
        }

        val curatedNames = curated.flatMap { listOf(normalize(it.first.query), normalize(it.second.name)) }.toSet()
        fun isCurated(title: String) = normalize(title) in curatedNames

        val newShows = discovered
            .filter { !isCurated(it.title) && it.key !in watched && isAvailable(it.service) }
            .sortedByDescending { it.popularity }
            .distinctBy { normalize(it.title) }

        upcoming += premieres
            .filter { it.date.isAfter(today) && AngShows.isHerKindOfShow(it.show) && isAvailable(it.show.service) }
            .filter { !isCurated(it.show.name) }
            .map { item(it.show, it.season, it.date, newLabel(it.season)) }

        return AngGuide(
            // Rows are keyed by show, so never list a show twice in one row.
            watchNow = watchNow.sortedByDescending { it.second ?: LocalDate.MIN }.map { it.first }.distinctBy { it.key },
            newShows = newShows.distinctBy { it.key },
            upcoming = upcoming.sortedBy { it.date }.distinctBy { it.key },
        )
    }

    fun keyOf(show: TvShow) = "tvmaze:${show.id}"

    fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Next season premiere after today: from the season list, or a scheduled episode 1. */
    private fun nextPremiere(show: TvShow, today: LocalDate): Pair<Int, LocalDate>? {
        show.seasons
            .filter { it.premiere?.isAfter(today) == true }
            .minByOrNull { it.premiere!! }
            ?.let { return it.number to it.premiere!! }
        val ep = show.nextEpisode ?: return null
        val date = ep.airdate ?: return null
        return if (ep.number == 1 && date.isAfter(today)) ep.season to date else null
    }

    private fun newLabel(season: Int) = if (season <= 1) "New show" else "New season"

    private fun item(show: TvShow, season: Int?, date: LocalDate?, badge: String?) = AngItem(
        key = keyOf(show),
        title = show.name,
        detail = listOfNotNull(season?.let { "Season $it" }, show.service).joinToString(" · "),
        imageUrl = show.imageUrl,
        service = show.service,
        date = date,
        badge = badge,
    )
}
