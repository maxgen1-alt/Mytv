package com.mytv.launcher.ang

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** Her ratings, saved on the TV. */
class TasteStore(context: Context) {
    private val prefs = context.getSharedPreferences("taste", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("ang", Context.MODE_PRIVATE)

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
        val r = Rated(item.key, item.title, rating, today, item.tmdbId, item.isMovie)
        prefs.edit().putString(item.key, encode(r).toString()).apply()
    }

    private fun encode(r: Rated) = JSONObject()
        .put("key", r.key).put("title", r.title).put("rating", r.rating.name)
        .put("date", r.date.toEpochDay()).put("tmdbId", r.tmdbId ?: -1).put("movie", r.isMovie)

    private fun decode(o: JSONObject) = Rated(
        key = o.getString("key"),
        title = o.optString("title"),
        rating = Rating.valueOf(o.getString("rating")),
        date = LocalDate.ofEpochDay(o.getLong("date")),
        tmdbId = o.optLong("tmdbId", -1).takeIf { it > 0 },
        isMovie = o.optBoolean("movie"),
    )
}
