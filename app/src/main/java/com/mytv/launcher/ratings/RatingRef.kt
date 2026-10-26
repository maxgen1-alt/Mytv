package com.mytv.launcher.ratings

/** What we know about a title, to find its rating. */
data class RatingRef(
    val imdbId: String? = null,
    val tmdbId: Long? = null,
    val isMovie: Boolean = false,
    /** TMDB's own score (0-10), the fallback when there's no OMDb key. */
    val tmdbVote: Double = 0.0,
) {
    val cacheKey: String get() = imdbId ?: "tmdb:${if (isMovie) "m" else "t"}:$tmdbId"
}

/** e.g. source "IMDb", value "7.8". */
data class ShownRating(val source: String, val value: String)
