package com.mytv.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class TvApp(
    val packageName: String,
    val label: String,
    /** Wide 16:9 TV banner if the app ships one, otherwise null. */
    val banner: Drawable?,
    val icon: Drawable?,
)

/**
 * Finds every app that shows up on the TV home screen (LEANBACK_LAUNCHER) and
 * keeps the user's own ordering / hidden list.
 */
class InstalledApps(private val context: Context) {

    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val pm: PackageManager = context.packageManager

    /** All launchable TV apps except this launcher, in the user's order. */
    fun load(): List<TvApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                TvApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    banner = info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm),
                    icon = info.loadIcon(pm),
                )
            }
        return sort(found)
    }

    fun visible(all: List<TvApp>): List<TvApp> {
        val hidden = hiddenPackages()
        return all.filter { it.packageName !in hidden }
    }

    fun hiddenPackages(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun setHidden(packageName: String, hidden: Boolean) {
        val set = hiddenPackages().toMutableSet()
        if (hidden) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    /** Moves an app one slot left (-1) or right (+1) within the visible row. */
    fun move(visibleApps: List<TvApp>, packageName: String, delta: Int) {
        val order = visibleApps.map { it.packageName }.toMutableList()
        val from = order.indexOf(packageName)
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order.add(to, order.removeAt(from))
        // Keep hidden apps' saved positions after the visible ones.
        val rest = savedOrder().filter { it !in order }
        prefs.edit().putString(KEY_ORDER, (order + rest).joinToString(",")).apply()
    }

    fun launch(packageName: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun savedOrder(): List<String> =
        prefs.getString(KEY_ORDER, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * Saved order first; anything new falls back to "streaming apps first, in
     * a sensible default order", then everything else alphabetically.
     */
    private fun sort(apps: List<TvApp>): List<TvApp> {
        val saved = savedOrder()
        return apps.sortedWith(
            compareBy<TvApp>(
                { saved.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { defaultRank(it) },
                { it.label.lowercase() },
            )
        )
    }

    private fun defaultRank(app: TvApp): Int {
        val key = (app.packageName + " " + app.label).lowercase()
        val i = DEFAULT_ORDER.indexOfFirst { key.contains(it) }
        return if (i < 0) DEFAULT_ORDER.size else i
    }

    companion object {
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_ORDER = "order"

        /** Keywords (matched against package name + label) for the out-of-the-box order. */
        private val DEFAULT_ORDER = listOf(
            "netflix", "youtube tv", "tvunplugged", "youtube", "hulu", "disney", "max",
            "prime video", "amazon", "apple tv", "peacock", "paramount", "espn", "fubo",
            "sling", "spotify", "plex",
        )
    }
}
