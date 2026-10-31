package com.mytv.launcher.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class TvApp(
    val packageName: String,
    val label: String,
    /** Wide 16:9 TV banner if the app ships one, pre-rendered small (320x180). */
    val banner: ImageBitmap?,
    /** Square icon, only rendered when there's no banner. */
    val icon: ImageBitmap?,
)

/**
 * Finds every app that shows up on the TV home screen (LEANBACK_LAUNCHER) and
 * keeps the user's own ordering / hidden list.
 */
class InstalledApps(private val context: Context) {

    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val pm: PackageManager = context.packageManager

    /** Rendered apps by "package@lastUpdateTime", so unchanged apps are never re-drawn. */
    private val rendered = HashMap<String, TvApp>()

    /**
     * All launchable TV apps except this launcher, in the user's order.
     * Slow (loads and draws every banner): call it off the main thread.
     */
    @Synchronized
    fun load(): List<TvApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val current = HashSet<String>()
        val found = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val updated = runCatching { pm.getPackageInfo(info.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                rendered.getOrPut("${info.packageName}@$updated".also(current::add)) {
                    val banner = (info.loadBanner(pm) ?: info.applicationInfo.loadBanner(pm))?.render(320, 180)
                    TvApp(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        banner = banner,
                        icon = if (banner == null) info.loadIcon(pm)?.render(96, 96) else null,
                    )
                }
            }
        // Drop banners of uninstalled apps and old versions of updated ones.
        rendered.keys.retainAll(current)
        runCatching { saveIndex(found, current) }
        return sort(found)
    }

    // ---- Saved copy, so the apps row is there instantly after a reboot ----

    private val artDir = java.io.File(context.filesDir, "appart")
    private val indexFile = java.io.File(context.filesDir, "apps-index.json")

    /** The apps row as it was last time (labels + pictures from disk). Fast: no package manager. */
    fun cached(): List<TvApp> = runCatching {
        val a = org.json.JSONArray(indexFile.readText())
        val apps = (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val bmp = android.graphics.BitmapFactory.decodeFile(java.io.File(artDir, o.getString("f")).path)?.asImageBitmap()
            val isBanner = o.optBoolean("b")
            TvApp(o.getString("p"), o.getString("l"), banner = if (isBanner) bmp else null, icon = if (isBanner) null else bmp)
        }
        visible(sort(apps))
    }.getOrDefault(emptyList())

    private fun saveIndex(apps: List<TvApp>, keys: Set<String>) {
        artDir.mkdirs()
        val byPkg = keys.associateBy { it.substringBefore('@') }
        val a = org.json.JSONArray()
        for (app in apps) {
            val file = (byPkg[app.packageName] ?: app.packageName).replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".png"
            val art = app.banner ?: app.icon
            val f = java.io.File(artDir, file)
            if (art != null && !f.exists()) f.outputStream().use { art.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            a.put(org.json.JSONObject().put("p", app.packageName).put("l", app.label).put("f", file).put("b", app.banner != null))
        }
        indexFile.writeText(a.toString())
        // Pictures of old versions / removed apps.
        val wanted = apps.map { (byPkg[it.packageName] ?: it.packageName).replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".png" }.toSet()
        artDir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
    }

    private fun Drawable.render(w: Int, h: Int): ImageBitmap? =
        runCatching { toBitmap(w, h).asImageBitmap().also { it.prepareToDraw() } }.getOrNull()

    fun visible(all: List<TvApp>): List<TvApp> {
        val hidden = hiddenPackages()
        return all.filter { it.packageName !in hidden }
    }

    fun hiddenPackages(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun setHidden(packageName: String, hidden: Boolean) {
        val set = hiddenPackages().toMutableSet()
        if (hidden) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, set).putLong(KEY_CHANGED, System.currentTimeMillis()).apply()
    }

    /** The row's layout (hidden apps + order) and when it last changed here, for syncing between TVs. */
    data class Layout(val hidden: Set<String>, val order: List<String>, val changedAt: Long)

    /** Dates a layout set up before syncing existed, so it can be shared. */
    fun markLayoutChanged() = prefs.edit().putLong(KEY_CHANGED, System.currentTimeMillis()).apply()

    fun layout() = Layout(hiddenPackages(), savedOrder(), prefs.getLong(KEY_CHANGED, 0L))

    /** Takes another TV's layout. Apps only this TV has keep their place after the shared ones. */
    fun applyLayout(remote: Layout) {
        val order = remote.order + savedOrder().filter { it !in remote.order }
        prefs.edit()
            .putStringSet(KEY_HIDDEN, remote.hidden)
            .putString(KEY_ORDER, order.joinToString(","))
            .putLong(KEY_CHANGED, remote.changedAt)
            .apply()
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
        prefs.edit().putString(KEY_ORDER, (order + rest).joinToString(",")).putLong(KEY_CHANGED, System.currentTimeMillis()).apply()
    }

    fun launch(packageName: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        com.mytv.launcher.system.HomeOverride.holdOff(15_000L)
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
        private const val KEY_CHANGED = "layout_changed_at"

        /** Keywords (matched against package name + label) for the out-of-the-box order. */
        private val DEFAULT_ORDER = listOf(
            "netflix", "youtube tv", "tvunplugged", "youtube", "hulu", "disney", "max",
            "prime video", "amazon", "apple tv", "peacock", "paramount", "espn", "fubo",
            "sling", "spotify", "plex",
        )
    }
}
