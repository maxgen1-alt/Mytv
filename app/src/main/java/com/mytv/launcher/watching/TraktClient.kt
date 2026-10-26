package com.mytv.launcher.watching

import android.content.Context
import com.mytv.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

sealed interface PollResult {
    data object Pending : PollResult
    data object SlowDown : PollResult
    data object Linked : PollResult
    data object Expired : PollResult
    data object Denied : PollResult
}

/**
 * Trakt.tv is the one place that can collect "what are we in the middle of"
 * across services: apps that scrobble (Plex, Kodi, Infuse, Jellyfin...),
 * Trakt's own streaming-service sync, and manual check-ins all land there.
 * We read two things:
 *  - /sync/playback: movies/episodes paused part-way through
 *  - recently watched shows' next unwatched episode ("Up Next")
 */
class TraktClient(context: Context) {

    private val prefs = context.getSharedPreferences("trakt", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
    private val api = "https://api.trakt.tv"

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val isLinked: Boolean get() = prefs.getString(KEY_ACCESS, null) != null

    fun unlink() = prefs.edit().clear().apply()

    // ---- Device login (shows a code on the TV, you approve on your phone) ----

    suspend fun startDeviceLogin(): DeviceCode? {
        val body = post("/oauth/device/code", JSONObject().put("client_id", clientId))?.second ?: return null
        val j = JSONObject(body)
        return DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            verificationUrl = j.optString("verification_url", "https://trakt.tv/activate"),
            expiresInSeconds = j.optInt("expires_in", 600),
            intervalSeconds = j.optInt("interval", 5),
        )
    }

    suspend fun pollDeviceLogin(code: DeviceCode): PollResult {
        val (status, body) = post(
            "/oauth/device/token",
            JSONObject().put("code", code.deviceCode).put("client_id", clientId).put("client_secret", clientSecret),
        ) ?: return PollResult.Pending
        return when (status) {
            200 -> { saveTokens(JSONObject(body)); PollResult.Linked }
            429 -> PollResult.SlowDown
            404, 409, 410 -> PollResult.Expired
            418 -> PollResult.Denied
            else -> PollResult.Pending // 400 = user hasn't approved yet
        }
    }

    // ---- Continue watching ----

    suspend fun continueWatching(): List<WatchItem> = coroutineScope {
        if (!isLinked) return@coroutineScope emptyList()
        refreshIfNeeded()
        val paused = async { pausedItems() }
        val upNext = async { upNextEpisodes() }
        // A show paused mid-episode beats its "next episode" entry.
        (paused.await() + upNext.await())
            .distinctBy { it.key }
            .sortedByDescending { it.lastWatchedMillis }
            .take(20)
    }

    private suspend fun pausedItems(): List<WatchItem> {
        val arr = getArray("/sync/playback?extended=images&limit=30") ?: return emptyList()
        return arr.objects().mapNotNull { item ->
            val progress = (item.optDouble("progress", 0.0) / 100.0).toFloat()
            if (progress >= 0.95f) return@mapNotNull null
            val pausedAt = parseDate(item.optString("paused_at"))
            when (item.optString("type")) {
                "movie" -> {
                    val m = item.optJSONObject("movie") ?: return@mapNotNull null
                    val title = m.optString("title")
                    WatchItem(
                        key = "movie:" + m.traktId(),
                        title = title,
                        subtitle = listOfNotNull("Movie", m.optInt("year").takeIf { it > 0 }?.toString()).joinToString(" · "),
                        imageUrl = image(m, "fanart") ?: image(m, "poster"),
                        progress = progress,
                        lastWatchedMillis = pausedAt,
                        searchQuery = title,
                        source = "Trakt",
                    )
                }
                "episode" -> {
                    val show = item.optJSONObject("show") ?: return@mapNotNull null
                    val ep = item.optJSONObject("episode") ?: return@mapNotNull null
                    episodeItem(show, ep, progress, pausedAt)
                }
                else -> null
            }
        }
    }

    private suspend fun upNextEpisodes(): List<WatchItem> = coroutineScope {
        val watched = getArray("/sync/watched/shows?extended=noseasons,images") ?: return@coroutineScope emptyList()
        // Only shows touched in the last ~6 months, most recent 12.
        val cutoff = System.currentTimeMillis() - 183L * 24 * 3600_000
        watched.objects()
            .map { it to parseDate(it.optString("last_watched_at")) }
            .filter { it.second > cutoff }
            .sortedByDescending { it.second }
            .take(12)
            .map { (entry, lastWatched) ->
                async {
                    val show = entry.optJSONObject("show") ?: return@async null
                    val progress = getObject("/shows/${show.traktId()}/progress/watched?hidden=false&specials=false")
                        ?: return@async null
                    val next = progress.optJSONObject("next_episode") ?: return@async null
                    episodeItem(show, next, null, lastWatched)
                }
            }.awaitAll().filterNotNull()
    }

    private fun episodeItem(show: JSONObject, ep: JSONObject, progress: Float?, lastWatched: Long): WatchItem {
        val title = show.optString("title")
        val s = ep.optInt("season")
        val e = ep.optInt("number")
        val epTitle = ep.optString("title").takeIf { it.isNotBlank() && it != "null" }
        return WatchItem(
            key = "show:" + show.traktId(),
            title = title,
            subtitle = listOfNotNull("S$s · E$e", epTitle).joinToString(" · "),
            imageUrl = image(ep, "screenshot") ?: image(show, "fanart") ?: image(show, "poster"),
            progress = progress,
            lastWatchedMillis = lastWatched,
            searchQuery = "$title season $s episode $e",
            source = "Trakt",
        )
    }

    // ---- Plumbing ----

    private suspend fun refreshIfNeeded() {
        val expiresAt = prefs.getLong(KEY_EXPIRES, 0)
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return
        if (System.currentTimeMillis() < expiresAt - 24 * 3600_000L) return
        val (status, body) = post(
            "/oauth/token",
            JSONObject()
                .put("refresh_token", refresh)
                .put("client_id", clientId)
                .put("client_secret", clientSecret)
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                .put("grant_type", "refresh_token"),
        ) ?: return
        if (status == 200) saveTokens(JSONObject(body)) else if (status == 401) unlink()
    }

    private fun saveTokens(j: JSONObject) {
        val created = j.optLong("created_at", System.currentTimeMillis() / 1000)
        prefs.edit()
            .putString(KEY_ACCESS, j.getString("access_token"))
            .putString(KEY_REFRESH, j.optString("refresh_token"))
            .putLong(KEY_EXPIRES, (created + j.optLong("expires_in", 7776000)) * 1000)
            .apply()
    }

    private fun request(path: String): Request.Builder {
        val b = Request.Builder()
            .url(api + path)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
        prefs.getString(KEY_ACCESS, null)?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request(path).build()).execute().use { r ->
                if (r.code == 401) unlink()
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    private suspend fun getArray(path: String) = get(path)?.let { runCatching { JSONArray(it) }.getOrNull() }
    private suspend fun getObject(path: String) = get(path)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private suspend fun post(path: String, json: JSONObject): Pair<Int, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.toString().toRequestBody("application/json".toMediaType())
            http.newCall(request(path).post(body).build()).execute().use { r ->
                r.code to (r.body?.string() ?: "")
            }
        }.getOrNull()
    }

    private fun JSONObject.traktId(): String = optJSONObject("ids")?.optString("trakt").orEmpty()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun parseDate(s: String): Long =
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

    companion object {
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRES = "expires"

        /**
         * Trakt's `extended=images` gives image URLs without a scheme, as a
         * list per type (or, in older responses, an object of sizes). Accept
         * either.
         */
        fun image(obj: JSONObject, type: String): String? {
            val images = obj.optJSONObject("images") ?: return null
            val url = when (val v = images.opt(type)) {
                is JSONArray -> v.optString(0)
                is JSONObject -> v.optString("medium").ifBlank { v.optString("full") }.ifBlank { v.optString("thumb") }
                is String -> v
                else -> null
            }?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            return if (url.startsWith("http")) url else "https://$url"
        }
    }
}
