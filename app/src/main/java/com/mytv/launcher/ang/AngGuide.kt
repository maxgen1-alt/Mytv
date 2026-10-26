package com.mytv.launcher.ang

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AngItem(
    val showId: Long,
    val title: String,
    /** e.g. "Season 3 · Prime Video" */
    val detail: String,
    val imageUrl: String?,
    val service: String?,
    /** Premiere date of the season this item is about. */
    val date: LocalDate?,
    /** Small label on the poster: "New season", "New show"... */
    val badge: String?,
)

data class AngGuide(
    /** Her shows with something to watch right now. */
    val watchNow: List<AngItem> = emptyList(),
    /** Newly premiered shows of the same kind she might like. */
    val newShows: List<AngItem> = emptyList(),
    /** Dated premieres coming soon, soonest first. */
    val upcoming: List<AngItem> = emptyList(),
)

object AngGuideBuilder {

    fun build(
        today: LocalDate,
        curated: List<Pair<CuratedShow, TvShow>>,
        premieres: List<Premiere>,
    ): AngGuide {
        val watchNow = mutableListOf<Pair<AngItem, LocalDate?>>()
        val upcoming = mutableListOf<AngItem>()

        for ((spec, show) in curated) {
            val latest = show.seasons
                .filter { it.premiere != null && !it.premiere.isAfter(today) }
                .maxByOrNull { it.premiere!! }
            val isNew = latest?.premiere?.let { ChronoUnit.DAYS.between(it, today) <= AngShows.NEW_SEASON_DAYS } == true

            if (spec.follow == Follow.ALWAYS || isNew) {
                watchNow += item(show, latest?.number, latest?.premiere, if (isNew) newLabel(latest!!.number) else null) to latest?.premiere
            }

            nextPremiere(show, today)?.let { (season, date) ->
                upcoming += item(show, season, date, newLabel(season))
            }
        }

        val curatedIds = curated.map { it.second.id }.toSet()
        val herKind = premieres
            .filter { it.show.id !in curatedIds && AngShows.isHerKindOfShow(it.show) }
            .filter { !it.date.isBefore(today.minusDays(AngShows.DISCOVER_PAST_DAYS)) }

        val newShows = herKind.filter { !it.date.isAfter(today) }
            .sortedByDescending { it.date }
            .distinctBy { it.show.id }
            .map { item(it.show, it.season, it.date, newLabel(it.season)) }

        upcoming += herKind.filter { it.date.isAfter(today) }
            .map { item(it.show, it.season, it.date, newLabel(it.season)) }

        return AngGuide(
            // Rows are keyed by show id, so never list a show twice in one row.
            watchNow = watchNow.sortedByDescending { it.second ?: LocalDate.MIN }.map { it.first }.distinctBy { it.showId },
            newShows = newShows.distinctBy { it.showId },
            upcoming = upcoming.sortedBy { it.date }.distinctBy { it.showId },
        )
    }

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
        showId = show.id,
        title = show.name,
        detail = listOfNotNull(season?.let { "Season $it" }, show.service).joinToString(" · "),
        imageUrl = show.imageUrl,
        service = show.service,
        date = date,
        badge = badge,
    )
}
