package com.mytv.launcher.ang

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AngItem(
    /** "tvmaze:123", "tmdb:tv:456" or "tmdb:movie:789": unique per title. */
    val key: String,
    val title: String,
    /** e.g. "Season 3 · Prime Video" */
    val detail: String,
    val imageUrl: String?,
    val service: String?,
    /** Premiere / release date of what this item is about. */
    val date: LocalDate?,
    /** Small label on the poster: "New season", "New show", "♥"... */
    val badge: String?,
    /** For ordering discovered titles. */
    val popularity: Double = 0.0,
    /** TMDB id / type, when known (for "more like this" and following new seasons). */
    val tmdbId: Long? = null,
    val isMovie: Boolean = false,
    /** TMDB average rating, 0-10. */
    val voteAverage: Double = 0.0,
    /** IMDb id when known (her hand-picked shows). */
    val imdbId: String? = null,
) {
    val ratingRef: com.mytv.launcher.ratings.RatingRef
        get() = com.mytv.launcher.ratings.RatingRef(imdbId, tmdbId, isMovie, voteAverage)
}

data class AngGuide(
    /** Her shows (and liked shows) with something new to watch right now. */
    val watchNow: List<AngItem> = emptyList(),
    /** Shows from the last year she might like. */
    val newShows: List<AngItem> = emptyList(),
    /** Good shows from the last 10 years she might have missed. */
    val missed: List<AngItem> = emptyList(),
    /** Rom-coms / romance / romantic dramas from the last 10 years. */
    val movies: List<AngItem> = emptyList(),
    /** Next seasons of her shows and liked shows, and new shows of her kind, soonest first. */
    val upcoming: List<AngItem> = emptyList(),
)

/** Everything the guide is built from. */
data class AngInputs(
    val curated: List<Pair<CuratedShow, TvShow>> = emptyList(),
    val ratings: List<Rated> = emptyList(),
    /** Liked shows with their season dates. */
    val followed: List<FollowedShow> = emptyList(),
    /** Candidate shows / movies on your apps (discover + "more like what she liked"). */
    val shows: List<AngItem> = emptyList(),
    val movies: List<AngItem> = emptyList(),
    /** New shows of her kind that haven't premiered yet. */
    val upcomingShows: List<AngItem> = emptyList(),
    /** Candidate key → how many liked / disliked titles recommend it. */
    val likedRecs: Map<String, Int> = emptyMap(),
    val dislikedRecs: Map<String, Int> = emptyMap(),
)

object AngGuideBuilder {
    private const val ROW_SIZE = 20

    /** [isAvailable]: whether a network / service name is on an app you have. */
    fun build(today: LocalDate, inputs: AngInputs, isAvailable: (String?) -> Boolean = { true }): AngGuide {
        val byKey = inputs.ratings.associateBy { it.key }
        val byTitle = inputs.ratings.filter { it.title.isNotBlank() }.associateBy { normalize(it.title) }
        fun ratingOf(key: String, title: String) = byKey[key] ?: byTitle[normalize(title)]

        val watchNow = mutableListOf<Pair<AngItem, LocalDate?>>()
        val upcoming = mutableListOf<AngItem>()
        val shown = mutableSetOf<String>() // normalized titles already on a shelf

        // Her hand-picked shows.
        for ((spec, show) in inputs.curated) {
            val rating = ratingOf(keyOf(show), show.name)
            if (rating?.rating == Rating.DISLIKE || rating?.rating == Rating.WONT_WATCH) continue
            val latest = show.seasons
                .filter { it.premiere != null && !it.premiere.isAfter(today) }
                .maxByOrNull { it.premiere!! }
            val caughtUp = listOfNotNull(spec.caughtUpAsOf, rating?.takeIf { it.rating == Rating.WATCHED }?.date).maxOrNull()
            val isNew = latest?.premiere?.let { isNewSince(it, caughtUp, today) } == true
            if (spec.follow == Follow.ALWAYS || isNew) {
                watchNow += curatedItem(show, latest?.number, latest?.premiere, if (isNew) newLabel(latest!!.number) else null) to latest?.premiere
            }
            nextPremiere(show, today)?.let { (season, date) -> upcoming += curatedItem(show, season, date, newLabel(season)) }
            shown += normalize(show.name)
        }

        // Shows she liked: followed for new seasons.
        for (f in inputs.followed) {
            val title = normalize(f.item.title)
            if (title in shown) continue
            val likedOn = ratingOf(f.item.key, f.item.title)?.date
            f.latestSeason?.let { (n, date) ->
                if (isNewSince(date, likedOn, today)) {
                    watchNow += f.item.copy(detail = seasonDetail(n, f.item.service), date = date, badge = newLabel(n)) to date
                }
            }
            f.nextSeason?.let { (n, date) ->
                upcoming += f.item.copy(detail = seasonDetail(n, f.item.service), date = date, badge = newLabel(n))
            }
            shown += title
        }

        // Suggestions: hide anything rated watched / disliked / won't watch, keep only your apps.
        fun eligible(item: AngItem): Boolean {
            val r = ratingOf(item.key, item.title)
            return r?.rating?.hides != true && isAvailable(item.service) && normalize(item.title) !in shown
        }
        fun ranked(items: List<AngItem>) = items
            .filter(::eligible)
            .distinctBy { it.key }
            .sortedByDescending { TasteRanker.score(it, inputs.likedRecs, inputs.dislikedRecs) }
            .map { if (ratingOf(it.key, it.title)?.rating == Rating.LIKE) it.copy(badge = "♥ Liked") else it }

        val yearAgo = today.minusYears(1)
        val shows = ranked(inputs.shows)
        val newShows = shows.filter { it.date != null && !it.date.isBefore(yearAgo) }.take(ROW_SIZE)
        shown += newShows.map { normalize(it.title) }
        val missed = shows.filter { (it.date == null || it.date.isBefore(yearAgo)) && normalize(it.title) !in shown }.take(ROW_SIZE)
        val movies = ranked(inputs.movies).take(ROW_SIZE)
        // Coming soon also gets brand-new shows of her kind, best taste match first.
        upcoming += ranked(inputs.upcomingShows)
            .filter { it.date?.isAfter(today) == true }
            .take(12)
            .map { it.copy(detail = listOfNotNull("New show", it.service).joinToString(" · ")) }

        return AngGuide(
            watchNow = watchNow.sortedByDescending { it.second ?: LocalDate.MIN }.map { it.first }.distinctBy { it.key },
            newShows = newShows,
            missed = missed,
            movies = movies,
            upcoming = upcoming.sortedBy { it.date }.distinctBy { it.key },
        )
    }

    /** A season counts as new for 60 days after it starts, if it started after [since]. */
    private fun isNewSince(premiere: LocalDate, since: LocalDate?, today: LocalDate) =
        ChronoUnit.DAYS.between(premiere, today) in 0..AngShows.NEW_SEASON_DAYS &&
            (since == null || premiere.isAfter(since))

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

    private fun seasonDetail(season: Int?, service: String?) =
        listOfNotNull(season?.let { "Season $it" }, service).joinToString(" · ")

    private fun curatedItem(show: TvShow, season: Int?, date: LocalDate?, badge: String?) = AngItem(
        key = keyOf(show),
        title = show.name,
        detail = seasonDetail(season, show.service),
        imageUrl = show.imageUrl,
        service = show.service,
        date = date,
        badge = badge,
        imdbId = show.imdbId,
    )
}
