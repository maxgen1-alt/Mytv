package com.mytv.launcher.ang

import java.time.LocalDate
import kotlin.math.ln

/** What she said about a show or movie (hold select → menu). */
enum class Rating(val label: String) {
    LIKE("Liked"),
    /** In progress: shows up in Continue Watching. */
    WATCHING("Watching now"),
    WATCHED("Already watched"),
    DISLIKE("Disliked"),
    WONT_WATCH("Won't watch"),
    ;

    /** Everything except a like hides the title from suggestions. */
    val hides: Boolean get() = this != LIKE
}

data class Rated(
    val key: String,
    val title: String,
    val rating: Rating,
    val date: LocalDate,
    /** TMDB id + type, for "more like this" and following new seasons. */
    val tmdbId: Long? = null,
    val isMovie: Boolean = false,
    /** For showing it in a list (e.g. My Watchlist → Watched). */
    val imageUrl: String? = null,
    val service: String? = null,
) {
    fun toItem() = AngItem(
        key = key,
        title = title,
        detail = listOfNotNull(if (isMovie) "Movie" else "Show", service).joinToString(" · "),
        imageUrl = imageUrl,
        service = service,
        date = null,
        badge = null,
        tmdbId = tmdbId,
        isMovie = isMovie,
    )
}

/**
 * Ranks candidates by her taste: TMDB popularity and rating, boosted when a
 * show she liked recommends it, pushed down when a show she disliked does.
 */
object TasteRanker {
    /** [likedRecs] / [dislikedRecs]: candidate key → how many liked / disliked titles recommend it. */
    fun score(item: AngItem, likedRecs: Map<String, Int>, dislikedRecs: Map<String, Int>): Double {
        val quality = if (item.voteAverage > 0) item.voteAverage / 7.0 else 1.0
        val base = ln(1.0 + item.popularity) * quality
        val boost = 1.0 + 1.5 * (likedRecs[item.key] ?: 0) - 1.0 * (dislikedRecs[item.key] ?: 0)
        return base * boost.coerceAtLeast(0.15)
    }
}
