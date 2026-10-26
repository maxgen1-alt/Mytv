package com.mytv.launcher.watching

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Notes what's playing in any app, for MyTV's own Continue Watching.
 *
 * Streaming apps tell Android what they're playing (so the remote's play /
 * pause works). Seeing that from another app needs "Notification access",
 * which is why this is a notification listener; it never reads notifications.
 */
class PlayingListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watched = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private val lookedUp = HashSet<String>()
    private var sessions: MediaSessionManager? = null

    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    /** Position only changes on play / pause / seek, so check playing sessions every half minute. */
    private val tick = object : Runnable {
        override fun run() {
            watched.values.forEach { (c, _) -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) note(c, flush = false) }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = m
        val me = ComponentName(this, PlayingListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(onSessions, me, main)
            track(m.getActiveSessions(me))
        }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 30_000)
    }

    override fun onListenerDisconnected() = stop()

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        main.removeCallbacks(tick)
        runCatching { sessions?.removeOnActiveSessionsChangedListener(onSessions) }
        watched.values.forEach { (c, cb) -> note(c, flush = true); c.unregisterCallback(cb) }
        watched.clear()
    }

    private fun track(controllers: List<MediaController>) {
        val live = controllers.filter { NowPlaying.shouldTrack(it.packageName) }.associateBy { it.sessionToken.toString() }
        (watched.keys - live.keys).forEach { k -> watched.remove(k)?.let { (c, cb) -> note(c, flush = true); c.unregisterCallback(cb) } }
        live.forEach { (k, c) ->
            if (k in watched) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = note(c, flush = false)
                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    note(c, flush = state?.state != PlaybackState.STATE_PLAYING)
                override fun onSessionDestroyed() = note(c, flush = true)
            }
            c.registerCallback(cb, main)
            watched[k] = c to cb
            note(c, flush = false)
        }
    }

    private fun note(c: MediaController, flush: Boolean) {
        val md = c.metadata ?: return
        val state = c.playbackState ?: return
        val playing = state.state == PlaybackState.STATE_PLAYING
        // Only things actually watched: playing now, or paused part-way.
        val position = currentPosition(state)
        if (!playing && position <= 0) return
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        if (NowPlaying.tooShort(duration)) return

        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() }
            .getOrDefault(c.packageName)
        val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE)
        val subtitle = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val parsed = NowPlaying.parse(title, subtitle, app) ?: return

        val entry = PlayHistory.Entry(
            packageName = c.packageName,
            app = app,
            show = parsed.show,
            season = parsed.season,
            episode = parsed.episode,
            episodeTitle = parsed.episodeTitle,
            positionMillis = position,
            durationMillis = duration,
            imageUrl = artUrl(md, c.packageName + "|" + parsed.show.lowercase()),
            at = System.currentTimeMillis(),
            raw = listOfNotNull(title, subtitle).joinToString(" / "),
        )
        PlayHistory.record(this, entry, flush)
        if (entry.imageUrl == null) findPicture(entry)
    }

    private fun currentPosition(s: PlaybackState): Long {
        if (s.position < 0) return 0
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        val since = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return s.position + (since * s.playbackSpeed).toLong().coerceAtLeast(0)
    }

    /** The app's own picture: a web link if it gave one, else its image saved to a file. */
    private fun artUrl(md: MediaMetadata, id: String): String? {
        listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { md.getString(it)?.takeIf { u -> u.startsWith("http") } }
            ?.let { return it }
        val bmp = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { md.getBitmap(it) } ?: return null
        if (bmp.width < 200) return null // an icon, not a picture of the show
        val f = File(File(filesDir, "art").apply { mkdirs() }, "${id.hashCode().toUInt()}.jpg")
        if (!f.exists()) runCatching {
            val w = minOf(bmp.width, 640)
            val scaled = if (w < bmp.width) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return f.takeIf { it.exists() }?.let { "file://" + it.absolutePath }
    }

    /** No picture from the app: borrow the show's backdrop from TMDB (once per show). */
    private fun findPicture(e: PlayHistory.Entry) {
        if (!Tmdb.isConfigured || !lookedUp.add(e.id)) return
        scope.launch {
            val path = "/search/multi?language=en-US&query=" + java.net.URLEncoder.encode(e.show, "UTF-8")
            val url = Tmdb.get(path) { json ->
                val results = JSONObject(json).optJSONArray("results") ?: return@get null
                (0 until results.length()).map { results.getJSONObject(it) }
                    .firstOrNull { it.optString("media_type") in setOf("tv", "movie") && it.optString("backdrop_path").isNotBlank() }
                    ?.let { "https://image.tmdb.org/t/p/w780" + it.getString("backdrop_path") }
            } ?: return@launch
            PlayHistory.setImage(this@PlayingListener, e.id, url)
        }
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, PlayingListener::class.java)
    }
}
