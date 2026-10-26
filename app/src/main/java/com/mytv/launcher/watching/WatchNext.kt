package com.mytv.launcher.watching

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android TV's shared "Watch Next" list: the system table streaming apps add
 * to whenever you watch something, and the same source Google TV's own
 * Continue Watching row reads. Reading other apps' rows needs the
 * TV listings permission, which the user grants once (on-screen prompt, or
 * over ADB on firmware that doesn't show one).
 */
class WatchNext(private val context: Context) {

    fun hasPermission(): Boolean = PERMISSIONS.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun load(): List<WatchItem> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val cursor = runCatching {
            context.contentResolver.query(CONTENT_URI, null, null, null, null)
        }.getOrNull() ?: return@withContext emptyList()

        val pm = context.packageManager
        val labels = HashMap<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        val items = mutableListOf<WatchItem>()
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.int("browsable") == 0) continue
                // 0 = continue, 1 = next episode, 2 = new episode, 3 = watchlist (skipped)
                val type = c.int("watch_next_type") ?: 0
                if (type == 3) continue
                val title = c.str("title") ?: continue
                val pkg = c.str("package_name") ?: continue
                val appName = label(pkg)

                val season = c.str("season_display_number")
                val episode = c.str("episode_display_number")
                val episodeTitle = c.str("episode_title")
                val subtitle = listOfNotNull(
                    appName,
                    season?.let { "S$it" },
                    episode?.let { "E$it" },
                    episodeTitle,
                ).joinToString(" · ")

                val position = c.long("last_playback_position_millis") ?: 0L
                val duration = c.long("duration_millis") ?: 0L
                val progress = if (position > 0 && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null

                items += WatchItem(
                    key = "wn:$pkg:${c.long("_id")}",
                    title = title,
                    subtitle = subtitle,
                    imageUrl = c.str("thumbnail_uri") ?: c.str("poster_art_uri"),
                    progress = progress,
                    lastWatchedMillis = c.long("last_engagement_time_utc_millis") ?: 0L,
                    searchQuery = if (season != null && episode != null) "$title season $season episode $episode" else title,
                    source = appName,
                    packageName = pkg,
                    intentUri = c.str("intent_uri"),
                )
            }
        }
        items.sortedByDescending { it.lastWatchedMillis }
            // Apps sometimes leave several rows for one show; keep the newest.
            .distinctBy { it.packageName + "|" + it.title.lowercase() }
    }

    /** Opens the item right where you left off, inside the app that published it. */
    fun open(item: WatchItem): Boolean {
        val uri = item.intentUri ?: return false
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        if (intent.`package` == null && intent.component == null) item.packageName?.let(intent::setPackage)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun Cursor.index(name: String): Int? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
    private fun Cursor.str(name: String): String? = index(name)?.let { getString(it) }?.takeIf { it.isNotBlank() }
    private fun Cursor.long(name: String): Long? = index(name)?.let { getLong(it) }
    private fun Cursor.int(name: String): Int? = index(name)?.let { getInt(it) }

    companion object {
        val CONTENT_URI: Uri = Uri.parse("content://android.media.tv/watch_next_program")

        /** The name differs between Android versions / vendors; any one is enough. */
        val PERMISSIONS = listOf(
            "android.permission.READ_TV_LISTINGS",
            "com.android.providers.tv.permission.READ_EPG_DATA",
        )
    }
}
