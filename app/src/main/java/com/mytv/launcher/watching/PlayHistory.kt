package com.mytv.launcher.watching

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What MyTV saw playing on this TV (see [PlayingListener]): its own Continue
 * Watching, for apps that only share theirs with Google TV. Kept on disk.
 */
object PlayHistory {

    data class Entry(
        val packageName: String,
        val app: String,
        val show: String,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val positionMillis: Long,
        val durationMillis: Long,
        val imageUrl: String?,
        val at: Long,
        /** The raw title / subtitle the app reported (shown on the apps screen, to check the parsing). */
        val raw: String,
    ) {
        val id get() = packageName + "|" + show.lowercase()
    }

    private const val MAX = 40
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    @Volatile private var file: File? = null
    private var lastSave = 0L

    fun entries(context: Context): StateFlow<List<Entry>> {
        load(context)
        return _entries.asStateFlow()
    }

    @Synchronized
    private fun load(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "play-history.json")
        file = f
        _entries.value = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    packageName = o.getString("pkg"),
                    app = o.optString("app"),
                    show = o.getString("show"),
                    season = o.optInt("s", -1).takeIf { it >= 0 },
                    episode = o.optInt("e", -1).takeIf { it >= 0 },
                    episodeTitle = o.optString("et").takeIf { it.isNotBlank() },
                    positionMillis = o.optLong("pos"),
                    durationMillis = o.optLong("dur"),
                    imageUrl = o.optString("img").takeIf { it.isNotBlank() },
                    at = o.optLong("at"),
                    raw = o.optString("raw"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Notes [entry] as the latest for its show. Writes to disk at most every 20s unless [flush]. */
    @Synchronized
    fun record(context: Context, entry: Entry, flush: Boolean) {
        load(context)
        val old = _entries.value.firstOrNull { it.id == entry.id }
        // Keep a picture found earlier if this update has none.
        val e = if (entry.imageUrl == null && old?.imageUrl != null) entry.copy(imageUrl = old.imageUrl) else entry
        _entries.value = (listOf(e) + _entries.value.filter { it.id != e.id }).take(MAX)
        val now = System.currentTimeMillis()
        if (flush || now - lastSave > 20_000) save(now)
    }

    @Synchronized
    fun setImage(context: Context, id: String, url: String) {
        load(context)
        _entries.value = _entries.value.map { if (it.id == id && it.imageUrl == null) it.copy(imageUrl = url) else it }
        save(System.currentTimeMillis())
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        load(context)
        _entries.value = _entries.value.filter { it.id != id }
        save(System.currentTimeMillis())
    }

    private fun save(now: Long) {
        lastSave = now
        val a = JSONArray()
        _entries.value.forEach { e ->
            a.put(JSONObject().apply {
                put("pkg", e.packageName); put("app", e.app); put("show", e.show)
                e.season?.let { put("s", it) }; e.episode?.let { put("e", it) }
                e.episodeTitle?.let { put("et", it) }
                put("pos", e.positionMillis); put("dur", e.durationMillis)
                e.imageUrl?.let { put("img", it) }
                put("at", e.at); put("raw", e.raw)
            })
        }
        runCatching { file?.writeText(a.toString()) }
    }
}
