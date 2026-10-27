package com.mytv.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.mytv.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-updates from this repo's "latest" GitHub release. Every CI build
 * publishes MyTV.apk plus a small version.json; we compare its versionCode
 * with ours, download the APK in the background and install it.
 *
 * The very first update shows Android's one-click "Update" prompt. From then
 * on MyTV is the app that installed itself, which on Android 12+ lets later
 * updates install with no prompt (we do that when MyTV is in the background,
 * e.g. while you're in another app or the screensaver is on).
 */
class Updater(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "updates")
    // "stable": only builds you release (Release to TVs); "latest": every test build.
    private val base get() =
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/${com.mytv.launcher.system.MyTvSettings(context).updateChannel}"

    /** versionCode of the latest release, from the last successful check (0 = never checked). */
    @Volatile var lastSeenVersion: Int = 0
        private set

    /** A downloaded, verified APK newer than what's installed, if any. */
    fun pendingApk(): File? =
        dir.listFiles()?.filter { it.name.endsWith(".apk") }
            ?.firstOrNull { versionOf(it)?.let { v -> v > BuildConfig.VERSION_CODE } == true }

    /**
     * Checks GitHub (at most once per [minIntervalMillis]; 0 = now) and
     * downloads a newer build if there is one. Returns the ready-to-install APK.
     */
    suspend fun checkAndDownload(minIntervalMillis: Long = 5 * 60_000L): File? = withContext(Dispatchers.IO) {
        cleanUp()
        pendingApk()?.let { return@withContext it }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < minIntervalMillis) return@withContext null

        val info = runCatching {
            http.newCall(Request.Builder().url("$base/version.json").build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body!!.string()) else null
            }
        }.getOrNull() ?: return@withContext null
        // Only a successful check counts, so a dropped connection doesn't delay the next one.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val latest = info.optInt("versionCode", 0)
        lastSeenVersion = latest
        if (latest <= BuildConfig.VERSION_CODE) return@withContext null

        dir.mkdirs()
        val target = File(dir, "MyTV-$latest.apk")
        val tmp = File(dir, "download.tmp")
        val ok = runCatching {
            http.newCall(Request.Builder().url("$base/MyTV.apk").build()).execute().use { r ->
                if (!r.isSuccessful) return@use false
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                true
            }
        }.getOrDefault(false)
        if (!ok || !tmp.renameTo(target)) {
            tmp.delete()
            return@withContext null
        }
        // Only keep it if it really is a newer MyTV (the release may have moved on mid-download).
        if ((versionOf(target) ?: 0) <= BuildConfig.VERSION_CODE) {
            target.delete()
            return@withContext null
        }
        target
    }

    /** Installs a downloaded update now if it can go in without a prompt. Returns true if it started. */
    fun installIfQuiet(): Boolean {
        if (!canInstallSilently()) return false
        return pendingApk()?.let(::install) ?: false
    }

    /** True once updates can install without a prompt (Android 12+, and we installed ourselves). */
    fun canInstallSilently(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val installer = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        }.getOrNull()
        return installer == context.packageName
    }

    /** Hands the APK to Android's package installer. */
    fun install(apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("MyTV.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun versionOf(apk: File): Int? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return null
        if (info.packageName != context.packageName) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
    }

    /** Drops APKs that are already installed (or older). */
    private fun cleanUp() {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".apk") && (versionOf(f) ?: 0) <= BuildConfig.VERSION_CODE) f.delete()
        }
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check"
    }
}
