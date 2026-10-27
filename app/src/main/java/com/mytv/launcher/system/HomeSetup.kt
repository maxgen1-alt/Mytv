package com.mytv.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes MyTV the only home screen, and undoes it, through [AdbShell].
 *
 * Chromecast with Google TV always sends the Home button (and power-on) to
 * Google TV's own home screen, so the only way to make MyTV what you see is
 * to switch that one off. Nothing is uninstalled: everything switched off is
 * remembered and [undo] turns it back on.
 */
class HomeSetup(private val context: Context) {
    private val prefs = context.getSharedPreferences("home_setup", Context.MODE_PRIVATE)
    private val adb = AdbShell(java.io.File(context.filesDir, "adb"))
    private val me = context.packageName

    /** True when Google TV's home screen is switched off (by MyTV, or from your phone). */
    val isOn: Boolean get() = runCatching {
        context.packageManager.getApplicationEnabledSetting(GOOGLE_TV_HOME).let {
            it == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                it == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }.getOrDefault(prefs.getBoolean("on", false))

    /** Why the last connection failed, in a few words (shown on the settings screen). */
    @Volatile private var lastError: String = ""

    /** What we switched off, so undo turns exactly that back on. */
    private val disabled: Set<String> get() = prefs.getStringSet("disabled", emptySet()) ?: emptySet()

    sealed class Result {
        data class Done(val message: String) : Result()
        /** Couldn't reach the TV's debugging service: USB debugging is off, or the prompt was declined. */
        data class NoAccess(val message: String) : Result()
    }

    /** Runs [cmd]; null if MyTV can't reach the TV's shell. */
    suspend fun shell(cmd: String): String? = withContext(Dispatchers.IO) {
        runCatching { adb.run(cmd) }
            .onFailure { e ->
                lastError = when {
                    e is java.net.ConnectException -> "refused"
                    e is java.net.SocketTimeoutException -> "timeout"
                    e.message == "tls" -> "tls"
                    else -> e.javaClass.simpleName + ": " + e.message
                }
            }
            .getOrNull()
    }

    suspend fun takeOver(): Result = withContext(Dispatchers.IO) {
        val enabled = shell("pm list packages -e") ?: return@withContext noAccess()
        val enabledSet = enabled.lines().map { it.removePrefix("package:").trim() }.toSet()
        val toDisable = BLOAT.filter { it in enabledSet }
        val off = disabled.toMutableSet()
        for (pkg in toDisable) {
            val out = shell("pm disable-user --user 0 $pkg").orEmpty()
            if ("disabled" in out) off += pkg
        }
        prefs.edit().putStringSet("disabled", off).apply()
        shell("cmd package set-home-activity $me/${HOME_ACTIVITY}")
        // The permissions MyTV's extras need, granted the same way.
        shell("cmd notification allow_listener $me/$me.watching.PlayingListener")
        shell("appops set $me SYSTEM_ALERT_WINDOW allow")
        prefs.edit().putBoolean("on", true).apply()
        Result.Done("MyTV is now your home screen. ${off.size} Google TV parts switched off.")
    }

    suspend fun undo(): Result = withContext(Dispatchers.IO) {
        // Check we can reach the shell before changing anything.
        shell("echo ok") ?: return@withContext noAccess()
        for (pkg in disabled) shell("pm enable $pkg")
        // Give Home back to Google TV.
        googleHome()?.let { shell("cmd package set-home-activity ${it.flattenToString()}") }
        restoreScreensaver()
        prefs.edit().putStringSet("disabled", emptySet()).putBoolean("on", false).apply()
        Result.Done("Google TV is back. Press Home to see it.")
    }

    // ---- Screensaver ----

    val usesMyTvScreensaver: Boolean get() = prefs.getBoolean("dream_on", false)

    /** Switches the screensaver between MyTV's and Google's (Ambient mode / Google Photos). */
    suspend fun setMyTvScreensaver(on: Boolean): Result = withContext(Dispatchers.IO) {
        if (on) {
            val current = shell("settings get secure screensaver_components") ?: return@withContext noAccess()
            if (!prefs.contains("dream_before")) prefs.edit().putString("dream_before", current.trim()).apply()
            shell("settings put secure screensaver_enabled 1")
            shell("settings put secure screensaver_components $me/$me.dream.MyTvDream")
            prefs.edit().putBoolean("dream_on", true).apply()
            Result.Done("MyTV's screensaver is on.")
        } else {
            shell("echo ok") ?: return@withContext noAccess()
            restoreScreensaver()
            Result.Done("Google's screensaver (Ambient mode, Google Photos) is back.")
        }
    }

    private suspend fun restoreScreensaver() {
        val before = prefs.getString("dream_before", null) ?: return
        if (before.isBlank() || before == "null") shell("settings delete secure screensaver_components")
        else shell("settings put secure screensaver_components $before")
        prefs.edit().remove("dream_before").putBoolean("dream_on", false).apply()
    }

    /** Puts the TV to sleep (and, over HDMI-CEC, turns the TV off). */
    suspend fun sleep(): Boolean = shell("input keyevent KEYCODE_SLEEP") != null

    private fun googleHome(): ComponentName? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName == GOOGLE_TV_HOME }
            ?.let { ComponentName(it.packageName, it.name) }
    }

    private fun noAccess() = Result.NoAccess(
        when (lastError) {
            "refused" -> "The TV isn't accepting debugging connections (port 5555 is closed). " +
                "Use the phone setup instead (see the setup steps), or turn USB debugging off and on again and retry."
            "timeout" -> "The TV didn't answer in time. Try again: when it asks \"Allow USB debugging?\", tick Always allow and choose OK."
            "tls" -> "This TV only allows Wireless debugging, which MyTV can't use itself. Use the phone setup instead."
            else -> "MyTV couldn't get access ($lastError). Check Settings → System → Developer options → USB debugging is on, then try again."
        },
    )

    companion object {
        private const val HOME_ACTIVITY = "com.mytv.launcher.MainActivity"
        const val GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx"

        /**
         * Switched off (never uninstalled) when MyTV takes over: Google TV's home
         * screen (it would grab the Home button) and background extras nobody
         * uses on a Chromecast. Casting, the Assistant, the Play Store, Ambient
         * mode (Google Photos) and every streaming app are left alone.
         */
        val BLOAT = listOf(
            GOOGLE_TV_HOME,
            "com.google.android.feedback",
            "com.google.android.play.games",
            "com.android.printspooler",
        )
    }
}
