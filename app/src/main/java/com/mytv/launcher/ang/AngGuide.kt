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
    /** TMDB genre ids. */
    val genres: List<Int> = emptyList(),
    /** TMDB description (only used to spot her kind of show; not saved). */
    val overview: String = "",
) {
    val ratingRef: com.mytv.launcher.ratings.RatingRef
        get() = com.mytv.launcher.ratings.RatingRef(imdbId, tmdbId, isMovie, voteAverage)
}

data class AngGuide(
    /** Her shows (and liked shows) with something new to watch right now. */
    val watchNow: List<AngItem> = emptyList(),
    /** Easy Watch: sitcoms (Friends, The Office, Modern Family...). */
    val easyWatch: List<AngItem> = emptyList(),
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
    /** Sitcom candidates for Easy Watch. */
    val sitcoms: List<AngItem> = emptyList(),
    /** Her hand-picked shows with a known premiere date (listed even if the databases can't find them). */
    val announced: List<CuratedShow> = emptyList(),
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
            val service = spec.service ?: show.service
            val started = show.seasons
                .filter { it.premiere != null && !it.premiere.isAfter(today) }
                .maxByOrNull { it.premiere!! }
            var latest: Pair<Int, LocalDate>? = started?.let { it.number to it.premiere!! }
            var next = nextPremiere(show, today)
            // A premiere date we know before the databases list it.
            spec.announced?.let { day ->
                val season = (show.seasons.maxOfOrNull { it.number } ?: 0).let { n -> if (latest?.second == day) latest!!.first else n + 1 }
                if (!day.isAfter(today) && (latest == null || day.isAfter(latest!!.second))) latest = season to day
                if (day.isAfter(today) && next?.second == null) next = season to day
            }
            val caughtUp = listOfNotNull(spec.caughtUpAsOf, rating?.takeIf { it.rating == Rating.WATCHED }?.date).maxOrNull()
            val isNew = latest?.let { isNewSince(it.second, caughtUp, today) } == true
            if (spec.follow == Follow.ALWAYS || isNew) {
                watchNow += curatedItem(show, service, latest?.first, latest?.second, if (isNew) newLabel(latest!!.first) else null) to latest?.second
            }
            next?.let { (season, date) -> upcoming += curatedItem(show, service, season, date, newLabel(season)) }
            shown += normalize(show.name)
            shown += normalize(spec.query)
        }

        // Announced shows the TV databases couldn't find yet: still list them by name.
        for (spec in inputs.announced) {
            val day = spec.announced ?: continue
            if (normalize(spec.query) in shown) continue
            val r = byTitle[normalize(spec.query)]
            if (r?.rating == Rating.DISLIKE || r?.rating == Rating.WONT_WATCH) continue
            val item = AngItem(
                key = "announced:" + normalize(spec.query),
                title = spec.query,
                detail = listOfNotNull("New season", spec.service).joinToString(" · "),
                imageUrl = null,
                service = spec.service,
                date = day,
                badge = "New season",
            )
            if (day.isAfter(today)) upcoming += item
            else if (isNewSince(day, listOfNotNull(spec.caughtUpAsOf, r?.takeIf { it.rating == Rating.WATCHED }?.date).maxOrNull(), today)) {
                watchNow += item to day
            }
            shown += normalize(spec.query)
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

        val easyWatch = ranked(inputs.sitcoms).take(ROW_SIZE)
        shown += easyWatch.map { normalize(it.title) }

        val yearAgo = today.minusYears(1)
        val shows = ranked(inputs.shows)
        val newShows = shows.filter { it.date != null && !it.date.isBefore(yearAgo) }.take(ROW_SIZE)
        shown += newShows.map { normalize(it.title) }
        val missed = shows.filter { (it.date == null || it.date.isBefore(yearAgo)) && normalize(it.title) !in shown }.take(ROW_SIZE)
        val movies = ranked(inputs.movies).take(ROW_SIZE)
        // Coming soon also gets brand-new shows of her kind on your apps, best taste match first.
        upcoming += inputs.upcomingShows
            .filter {
                it.date?.isAfter(today) == true && isAvailable(it.service) &&
                    ratingOf(it.key, it.title)?.rating?.hides != true && normalize(it.title) !in shown
            }
            .distinctBy { it.key }
            .sortedByDescending { TasteRanker.score(it, inputs.likedRecs, inputs.dislikedRecs) }
            .take(15)
            .map { it.copy(detail = listOfNotNull("New show", it.service).joinToString(" · ")) }

        return AngGuide(
            watchNow = watchNow.sortedByDescending { it.second ?: LocalDate.MIN }.map { it.first }.distinctBy { it.key },
            easyWatch = easyWatch,
            newShows = newShows,
            missed = missed,
            movies = movies,
            // Dated premieres soonest first, then renewed-but-no-date-yet.
            upcoming = upcoming.sortedWith(compareBy(nullsLast()) { it.date }).distinctBy { it.key },
        )
    }

    /** A season counts as new for 60 days after it starts, if it started after [since]. */
    private fun isNewSince(premiere: LocalDate, since: LocalDate?, today: LocalDate) =
        ChronoUnit.DAYS.between(premiere, today) in 0..AngShows.NEW_SEASON_DAYS &&
            (since == null || premiere.isAfter(since))

    fun keyOf(show: TvShow) = "tvmaze:${show.id}"

    fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Next season after today: from the season list, or a scheduled episode 1;
     * or, if a later season is listed without a date, that season with no date (TBA).
     */
    private fun nextPremiere(show: TvShow, today: LocalDate): Pair<Int, LocalDate?>? {
        show.seasons
            .filter { it.premiere?.isAfter(today) == true }
            .minByOrNull { it.premiere!! }
            ?.let { return it.number to it.premiere!! }
        val ep = show.nextEpisode
        val date = ep?.airdate
        if (ep != null && date != null && ep.number == 1 && date.isAfter(today)) return ep.season to date
        val latest = show.seasons.filter { it.premiere != null && !it.premiere.isAfter(today) }.maxOfOrNull { it.number } ?: 0
        return show.seasons.filter { it.premiere == null && it.number > latest }.minByOrNull { it.number }?.let { it.number to null }
    }

    private fun newLabel(season: Int) = if (season <= 1) "New show" else "New season"

    private fun seasonDetail(season: Int?, service: String?) =
        listOfNotNull(season?.let { "Season $it" }, service).joinToString(" · ")

    private fun curatedItem(show: TvShow, service: String?, season: Int?, date: LocalDate?, badge: String?) = AngItem(
        key = keyOf(show),
        title = show.name,
        detail = seasonDetail(season, service),
        imageUrl = show.imageUrl,
        service = service,
        date = date,
        badge = badge,
        imdbId = show.imdbId,
    )
}
