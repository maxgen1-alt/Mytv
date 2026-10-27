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
    // For syncing between TVs: when each rating was last changed (incl. cleared ones), and what hasn't been uploaded yet.
    private val sync = context.getSharedPreferences("${name}_sync", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences(if (name == "taste") "ang" else "unused_legacy", Context.MODE_PRIVATE)

    /** Decoded ratings, kept in memory (read many times per Home press); dropped on every change. */
    @Volatile private var cache: List<Rated>? = null

    fun all(): List<Rated> = cache ?: load().also { cache = it }

    private fun load(): List<Rated> {
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
        cache = null
        legacy.edit().remove("watched:${item.key}").apply()
        if (rating == null) {
            prefs.edit().remove(item.key).apply()
        } else {
            val r = Rated(item.key, item.title, rating, today, item.tmdbId, item.isMovie, item.imageUrl, item.service)
            prefs.edit().putString(item.key, encode(r).toString()).apply()
        }
        sync.edit().putLong("at:${item.key}", System.currentTimeMillis()).putBoolean("dirty:${item.key}", true).apply()
        cache = null
    }

    fun has(key: String): Boolean = prefs.contains(key)

    /** Saves a rating as is (imports), queued for syncing like any other. */
    fun put(r: Rated) {
        cache = null
        prefs.edit().putString(r.key, encode(r).toString()).apply()
        sync.edit().putLong("at:${r.key}", System.currentTimeMillis()).putBoolean("dirty:${r.key}", true).apply()
        cache = null
    }

    // ---- Sync support ----

    /** A rating as stored (JSON), or null if cleared, plus when it changed. */
    data class Change(val key: String, val json: String?, val at: Long)

    /**
     * First sync on this TV: queue everything already rated here for upload,
     * timestamped with the day it was rated (so newer changes elsewhere still win).
     */
    fun seedForSync() {
        if (sync.getBoolean("seeded", false)) return
        val e = sync.edit()
        prefs.all.forEach { (key, v) ->
            if (v !is String || sync.contains("at:$key")) return@forEach
            val day = runCatching { JSONObject(v).getLong("date") }.getOrDefault(0L)
            e.putLong("at:$key", day * 86_400_000L).putBoolean("dirty:$key", true)
        }
        e.putBoolean("seeded", true).apply()
    }

    /** Local changes not uploaded yet. */
    fun pending(): List<Change> = sync.all.keys.filter { it.startsWith("dirty:") }.map { it.removePrefix("dirty:") }.map { key ->
        Change(key, prefs.getString(key, null), sync.getLong("at:$key", 0))
    }

    fun markUploaded(keys: Collection<String>) {
        val e = sync.edit()
        keys.forEach { e.remove("dirty:$it") }
        e.apply()
    }

    /** Applies a change from the other TV if it's newer than ours. Returns true if something changed. */
    fun applyRemote(change: Change): Boolean {
        if (change.at <= sync.getLong("at:${change.key}", 0)) return false
        cache = null
        if (change.json == null) prefs.edit().remove(change.key).apply()
        else prefs.edit().putString(change.key, change.json).apply()
        sync.edit().putLong("at:${change.key}", change.at).remove("dirty:${change.key}").apply()
        cache = null
        return true
    }

    private fun encode(r: Rated) = JSONObject()
        .put("key", r.key).put("title", r.title).put("rating", r.rating.name)
        .put("date", r.date.toEpochDay()).put("tmdbId", r.tmdbId ?: -1).put("movie", r.isMovie)
        .put("image", r.imageUrl ?: "").put("service", r.service ?: "")
        .apply { r.stars?.let { put("stars", it) } }

    private fun decode(o: JSONObject) = Rated(
        key = o.getString("key"),
        title = o.optString("title"),
        rating = Rating.valueOf(o.getString("rating")),
        date = LocalDate.ofEpochDay(o.getLong("date")),
        tmdbId = o.optLong("tmdbId", -1).takeIf { it > 0 },
        isMovie = o.optBoolean("movie"),
        imageUrl = o.optString("image").ifBlank { null },
        service = o.optString("service").ifBlank { null },
        stars = o.optInt("stars", 0).takeIf { it in 1..10 },
    )
}
