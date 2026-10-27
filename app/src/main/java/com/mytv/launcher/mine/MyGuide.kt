package com.mytv.launcher.mine

import com.mytv.launcher.ang.AngGuideBuilder
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.FollowedShow
import com.mytv.launcher.ang.Rated
import com.mytv.launcher.ang.Rating
import com.mytv.launcher.ang.TasteRanker
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Your own lists: the home "Shows for you" row and the My Watchlist tabs. */
data class MyGuide(
    /** What you're in the middle of ("Watching now"), newest first; also in Continue Watching. */
    val watching: List<AngItem> = emptyList(),
    /** What you saved with "Watch later", newest first. */
    val watchLater: List<AngItem> = emptyList(),
    /** Everything you marked watched or liked, newest first. */
    val watched: List<AngItem> = emptyList(),
    /** Recommended from what you watched / liked, varied across genres. */
    val forYou: List<AngItem> = emptyList(),
    /** Recommended shows that premiered in the last year. */
    val newShows: List<AngItem> = emptyList(),
    /** Recommended older shows (2000 on, newer ones first). */
    val missed: List<AngItem> = emptyList(),
    /** Shows you follow with a new episode out today ("Tonight" row). */
    val tonight: List<AngItem> = emptyList(),
    /** Recommended movies released in the last year. */
    val newMovies: List<AngItem> = emptyList(),
    /** Recommended older movies (2000 on, newer ones first). */
    val missedMovies: List<AngItem> = emptyList(),
    /** New seasons, out now, of shows you watched or liked. */
    val newSeasons: List<AngItem> = emptyList(),
    /** Next seasons of your shows + upcoming new shows in your genres, soonest first. */
    val comingSoon: List<AngItem> = emptyList(),
) {
    companion object {
        /** "Shows for you" appears on the home screen once you've rated this many titles. */
        const val MIN_RATINGS_FOR_HOME_ROW = 3
    }
}

object MyGuideBuilder {

    /**
     * [recs] / [likedRecs] / [dislikedRecs]: from your watched & liked titles.
     * [upcoming]: upcoming new shows in your favourite genres.
     */
    fun build(
        today: LocalDate,
        ratings: List<Rated>,
        recs: List<AngItem>,
        likedRecs: Map<String, Int>,
        dislikedRecs: Map<String, Int>,
        followed: List<FollowedShow>,
        upcoming: List<AngItem>,
        isAvailable: (String?) -> Boolean,
        /** Shows from the last year in your genres, on your apps (for New Shows for You). */
        fresh: List<AngItem> = emptyList(),
        /** Movies from the last year in your genres, on your apps (for New Movies for You). */
        freshMovies: List<AngItem> = emptyList(),
    ): MyGuide {
        val rated = ratings.map { it.key }.toSet()
        val ratedTitles = ratings.filter { it.title.isNotBlank() }.map { AngGuideBuilder.normalize(it.title) }.toSet()
        fun unseen(i: AngItem) = i.key !in rated && AngGuideBuilder.normalize(i.title) !in ratedTitles && isAvailable(i.service)

        val watched = ratings
            .filter { it.rating == Rating.WATCHED || it.rating == Rating.LIKE }
            .sortedByDescending { it.date }
            .map { r -> r.toItem().let { if (r.rating == Rating.LIKE) it.copy(badge = "♥ Liked") else it } }

        val watching = ratings.filter { it.rating == Rating.WATCHING }
            .sortedByDescending { it.date }
            .map { it.toItem().copy(date = it.date) }

        val watchLater = ratings.filter { it.rating == Rating.WANT }
            .sortedByDescending { it.date }
            .map { it.toItem() }

        val ranked = recs.filter(::unseen).distinctBy { it.key }
            .sortedByDescending { TasteRanker.score(it, likedRecs, dislikedRecs) }
        val forYou = varied(ranked, perGenre = 4).take(30)

        val yearAgo = today.minusYears(1)
        fun isNew(i: AngItem) = i.date != null && !i.date.isBefore(yearAgo) && !i.date.isAfter(today)
        val newShows = varied(
            (recs + fresh).filter { !it.isMovie && isNew(it) && unseen(it) }.distinctBy { it.key }
                // Recommended by your shows first, then the best of the year in your genres.
                .sortedByDescending { TasteRanker.score(it, likedRecs, dislikedRecs) * (1.0 + (likedRecs[it.key] ?: 0)) },
            perGenre = 5,
        ).take(30)
        // Older shows, 2000 on: newer ones get a lift (2024 counts about twice 2000).
        fun recency(i: AngItem) = 1.0 + 0.04 * ((i.date?.year ?: 2000) - 2000).coerceAtLeast(0)
        val missed = varied(
            ranked.filter { !it.isMovie && it.date != null && it.date.year >= 2000 && it.date.isBefore(yearAgo) }
                .sortedByDescending { TasteRanker.score(it, likedRecs, dislikedRecs) * recency(it) },
            perGenre = 4,
        ).take(30)
        // Same two rows for movies.
        val newMovies = varied(
            (recs + freshMovies).filter { it.isMovie && isNew(it) && unseen(it) }.distinctBy { it.key }
                .sortedByDescending { TasteRanker.score(it, likedRecs, dislikedRecs) * (1.0 + (likedRecs[it.key] ?: 0)) },
            perGenre = 5,
        ).take(30)
        val missedMovies = varied(
            ranked.filter { it.isMovie && it.date != null && it.date.year >= 2000 && it.date.isBefore(yearAgo) }
                .sortedByDescending { TasteRanker.score(it, likedRecs, dislikedRecs) * recency(it) },
            perGenre = 4,
        ).take(30)

        val newSeasons = mutableListOf<AngItem>()
        val comingSoon = mutableListOf<AngItem>()
        for (f in followed) {
            val rated = ratings.firstOrNull { it.key == f.item.key }
            val since = rated?.date
            f.latestSeason?.let { (n, date) ->
                // Ones you're watching are in Continue Watching instead.
                val fresh = rated?.rating != Rating.WATCHING &&
                    ChronoUnit.DAYS.between(date, today) in 0..60 && (since == null || date.isAfter(since))
                if (fresh) newSeasons += f.item.copy(detail = seasonDetail(n, f.item.service), date = date, badge = "New season")
            }
            f.nextSeason?.let { (n, date) ->
                comingSoon += f.item.copy(detail = seasonDetail(n, f.item.service), date = date, badge = null)
            }
        }
        comingSoon += upcoming
            .filter { it.date?.isAfter(today) == true && unseen(it) }
            .sortedByDescending { TasteRanker.score(it, likedRecs, dislikedRecs) }
            .take(15)
            .map { it.copy(detail = listOfNotNull("New show", it.service).joinToString(" · ")) }

        // "New episode Thursday" on the shows you're watching.
        val labels = followed.associate { it.item.key to Tonight.label(it, today) }
        return MyGuide(
            tonight = Tonight.row(followed, today),
            watching = watching.map { w -> labels[w.key]?.let { w.copy(badge = it) } ?: w },
            watchLater = watchLater,
            watched = watched,
            forYou = forYou,
            newShows = newShows,
            missed = missed,
            newMovies = newMovies,
            missedMovies = missedMovies,
            newSeasons = newSeasons.sortedByDescending { it.date }.distinctBy { it.key },
            comingSoon = comingSoon.sortedWith(compareBy(nullsLast()) { it.date }).distinctBy { it.key },
        )
    }

    /**
     * Keeps the order but lets at most [perGenre] titles of the same main
     * genre into each stretch of 20, so one genre can't take over.
     */
    fun varied(items: List<AngItem>, perGenre: Int): List<AngItem> {
        val out = mutableListOf<AngItem>()
        val pending = items.toMutableList()
        while (pending.isNotEmpty()) {
            val counts = HashMap<Int, Int>()
            val window = mutableListOf<AngItem>()
            val iterator = pending.iterator()
            while (iterator.hasNext() && window.size < 20) {
                val item = iterator.next()
                val genre = item.genres.firstOrNull() ?: -1
                if ((counts[genre] ?: 0) < perGenre) {
                    counts[genre] = (counts[genre] ?: 0) + 1
                    window += item
                    iterator.remove()
                }
            }
            if (window.isEmpty()) { out += pending; break }
            out += window
        }
        return out
    }

    /** Your favourite TV genres, from what you watched / liked recommends (weighted). */
    fun topGenres(recs: List<AngItem>, likedRecs: Map<String, Int>, n: Int = 3, movies: Boolean = false): List<Int> =
        recs.filter { it.isMovie == movies }
            .flatMap { item -> item.genres.map { it to (likedRecs[item.key] ?: 1) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }
            .take(n).map { it.key }

    private fun seasonDetail(season: Int, service: String?) =
        listOfNotNull("Season $season", service).joinToString(" · ")
}
