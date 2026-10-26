package com.mytv.launcher.ang

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * One profile's ratings, saved on the TV: [name] "taste" is Ang's (the
 * original), "taste_me" is yours. Only Ang's picks up "Mark as watched"
 * marks from older versions.
 */
class TasteStore(context: Context, name: String = "taste") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences(if (name == "taste") "ang" else "unused_legacy", Context.MODE_PRIVATE)

    fun all(): List<Rated> {
        val rated = prefs.all.values.mapNotNull { v -> (v as? String)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() } }
        // "Mark as watched" from older versions.
        val old = legacy.all.mapNotNull { (k, v) ->
            if (!k.startsWith("watched:") || v !is Long) return@mapNotNull null
            val key = k.removePrefix("watched:").let { id ->
                when {
                    id.toLongOrNull() != null -> "tvmaze:$id"
                    id.matches(Regex("tmdb:\\d+")) -> "tmdb:tv:" + id.removePrefix("tmdb:")
                    else -> id
                }
            }
            Rated(key, "", Rating.WATCHED, LocalDate.ofEpochDay(v))
        }
        val keys = rated.map { it.key }.toSet()
        return rated + old.filter { it.key !in keys }
    }

    fun set(item: AngItem, rating: Rating?, today: LocalDate = LocalDate.now()) {
        legacy.edit().remove("watched:${item.key}").apply()
        if (rating == null) {
            prefs.edit().remove(item.key).apply()
            return
        }
        val r = Rated(item.key, item.title, rating, today, item.tmdbId, item.isMovie, item.imageUrl, item.service)
        prefs.edit().putString(item.key, encode(r).toString()).apply()
    }

    private fun encode(r: Rated) = JSONObject()
        .put("key", r.key).put("title", r.title).put("rating", r.rating.name)
        .put("date", r.date.toEpochDay()).put("tmdbId", r.tmdbId ?: -1).put("movie", r.isMovie)
        .put("image", r.imageUrl ?: "").put("service", r.service ?: "")

    private fun decode(o: JSONObject) = Rated(
        key = o.getString("key"),
        title = o.optString("title"),
        rating = Rating.valueOf(o.getString("rating")),
        date = LocalDate.ofEpochDay(o.getLong("date")),
        tmdbId = o.optLong("tmdbId", -1).takeIf { it > 0 },
        isMovie = o.optBoolean("movie"),
        imageUrl = o.optString("image").ifBlank { null },
        service = o.optString("service").ifBlank { null },
    )
}
