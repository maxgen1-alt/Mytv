package com.mytv.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes MyTV the home screen, and undoes it with one button.
 *
 * Chromecast with Google TV always sends the Home button (and power-on) to
 * Google TV's own home screen. Two ways round that:
 * - Preferred: MyTV's home helper ([HomeOverride]) opens MyTV the moment
 *   Google TV's home screen appears. Nothing is switched off. Needs the
 *   "write secure settings" permission, granted once from a phone:
 *   `pm grant com.mytv.launcher android.permission.WRITE_SECURE_SETTINGS`.
 * - Fallback, on TVs that accept a debugging connection from MyTV itself
 *   ([AdbShell]): switch Google TV's home screen off (never uninstalled).
 */
class HomeSetup(private val context: Context) {
    private val prefs = context.getSharedPreferences("home_setup", Context.MODE_PRIVATE)
    private val adb = AdbShell(java.io.File(context.filesDir, "adb"))
    private val me = context.packageName
    private val settings = MyTvSettings(context)
    private val cr get() = context.contentResolver

    /** Granted once from a phone: then MyTV turns its helpers on and off itself. */
    val canWriteSecure: Boolean
        get() = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private val googleHomeOff: Boolean
        get() = runCatching {
            context.packageManager.getApplicationEnabledSetting(GOOGLE_TV_HOME).let {
                it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        }.getOrDefault(false)

    private fun overrideServiceOn(): Boolean = secureList(ACCESSIBILITY).any { it.equals(HomeOverride.component, ignoreCase = true) }

    /** The home helper is switched on in the system and Android has actually started it. */
    val helperRunning: Boolean
        get() = runCatching {
            context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == me }
        }.getOrDefault(false)

    /** MyTV is the home screen: its home helper is on, or Google TV's home screen is switched off. */
    val isOn: Boolean get() = googleHomeOff || (settings.homeOverride && overrideServiceOn())

    /** Why the last debugging connection failed, in a few words. */
    @Volatile private var lastError: String = ""

    /** What was switched off over a debugging connection, so undo turns exactly that back on. */
    private val disabled: Set<String> get() = prefs.getStringSet("disabled", emptySet()) ?: emptySet()

    sealed class Result {
        data class Done(val message: String) : Result()
        /** MyTV can't make the change itself: the message says what to do. */
        data class NoAccess(val message: String) : Result()
    }

    private fun secureList(name: String): List<String> =
        Settings.Secure.getString(cr, name)?.split(':')?.filter { it.isNotBlank() }.orEmpty()

    private fun setSecureList(name: String, items: List<String>) {
        Settings.Secure.putString(cr, name, items.distinct().joinToString(":"))
    }

    /** Runs [cmd] over the TV's debugging connection; null if MyTV can't reach it. */
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
        if (canWriteSecure) {
            setSecureList(ACCESSIBILITY, secureList(ACCESSIBILITY) + HomeOverride.component)
            Settings.Secure.putInt(cr, "accessibility_enabled", 1)
            // Watch tracking too, while we're at it.
            setSecureList(LISTENERS, secureList(LISTENERS) + "$me/$me.watching.PlayingListener")
            settings.homeOverride = true
            return@withContext Result.Done("MyTV is now your home screen. Press Home to try it.")
        }
        val enabled = shell("pm list packages -e") ?: return@withContext noAccess()
        val enabledSet = enabled.lines().map { it.removePrefix("package:").trim() }.toSet()
        val off = disabled.toMutableSet()
        for (pkg in BLOAT.filter { it in enabledSet }) {
            if ("disabled" in shell("pm disable-user --user 0 $pkg").orEmpty()) off += pkg
        }
        prefs.edit().putStringSet("disabled", off).apply()
        shell("cmd package set-home-activity $me/$HOME_ACTIVITY")
        shell("cmd notification allow_listener $me/$me.watching.PlayingListener")
        shell("appops set $me SYSTEM_ALERT_WINDOW allow")
        Result.Done("MyTV is now your home screen. ${off.size} Google TV parts switched off.")
    }

    suspend fun undo(): Result = withContext(Dispatchers.IO) {
        settings.homeOverride = false
        if (canWriteSecure) setSecureList(ACCESSIBILITY, secureList(ACCESSIBILITY).filterNot { it.equals(HomeOverride.component, true) })
        restoreScreensaver()
        if (!googleHomeOff) return@withContext Result.Done("Google TV is back. Press Home to see it.")
        // Google TV's home screen was switched off over a debugging connection: turn it back on the same way.
        if (shell("echo ok") == null) {
            return@withContext Result.NoAccess(
                "Almost: Google TV's home screen was switched off from your phone. To bring it back, " +
                    "run this in Bugjaeger's Shell: pm enable $GOOGLE_TV_HOME",
            )
        }
        for (pkg in disabled + GOOGLE_TV_HOME) shell("pm enable $pkg")
        googleHome()?.let { shell("cmd package set-home-activity ${it.flattenToString()}") }
        prefs.edit().putStringSet("disabled", emptySet()).apply()
        Result.Done("Google TV is back. Press Home to see it.")
    }

    // ---- Screensaver ----

    val usesMyTvScreensaver: Boolean get() = prefs.getBoolean("dream_on", false)

    /** Switches the screensaver between MyTV's and Google's (Ambient mode / Google Photos). */
    suspend fun setMyTvScreensaver(on: Boolean): Result = withContext(Dispatchers.IO) {
        if (!on) {
            if (!canWriteSecure && shell("echo ok") == null) return@withContext noAccess()
            restoreScreensaver()
            return@withContext Result.Done("Google's screensaver (Ambient mode, Google Photos) is back.")
        }
        val current = if (canWriteSecure) Settings.Secure.getString(cr, SCREENSAVER).orEmpty()
        else shell("settings get secure $SCREENSAVER")?.trim() ?: return@withContext noAccess()
        if (!prefs.contains("dream_before")) prefs.edit().putString("dream_before", current).apply()
        val mine = "$me/$me.dream.MyTvDream"
        if (canWriteSecure) {
            Settings.Secure.putInt(cr, "screensaver_enabled", 1)
            Settings.Secure.putString(cr, SCREENSAVER, mine)
        } else {
            shell("settings put secure screensaver_enabled 1")
            shell("settings put secure $SCREENSAVER $mine")
        }
        prefs.edit().putBoolean("dream_on", true).apply()
        Result.Done("MyTV's screensaver is on.")
    }

    private suspend fun restoreScreensaver() {
        val before = prefs.getString("dream_before", null) ?: return
        val value = before.takeIf { it.isNotBlank() && it != "null" }
        if (canWriteSecure) Settings.Secure.putString(cr, SCREENSAVER, value)
        else if (value == null) shell("settings delete secure $SCREENSAVER")
        else shell("settings put secure $SCREENSAVER $value")
        prefs.edit().remove("dream_before").putBoolean("dream_on", false).apply()
    }

    /** Puts the TV to sleep (and, over HDMI-CEC, turns the TV off). */
    suspend fun sleep(): Boolean = HomeOverride.sleep() || shell("input keyevent KEYCODE_SLEEP") != null

    private fun googleHome(): ComponentName? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName == GOOGLE_TV_HOME }
            ?.let { ComponentName(it.packageName, it.name) }
    }

    private fun noAccess() = Result.NoAccess(
        when (lastError) {
            "timeout" -> "The TV didn't answer in time. Try again: when it asks \"Allow USB debugging?\", tick Always allow and choose OK."
            else -> "One-time step from your phone: in Bugjaeger's Shell, run   " + PHONE_COMMAND + "   then select this button again."
        },
    )

    companion object {
        private const val HOME_ACTIVITY = "com.mytv.launcher.MainActivity"
        const val GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx"
        private const val ACCESSIBILITY = "enabled_accessibility_services"
        private const val LISTENERS = "enabled_notification_listeners"
        private const val SCREENSAVER = "screensaver_components"

        /** Run once from a phone (Bugjaeger's Shell): MyTV can then do the rest itself, undo included. */
        const val PHONE_COMMAND =
            "pm grant com.mytv.launcher android.permission.WRITE_SECURE_SETTINGS; appops set com.mytv.launcher SYSTEM_ALERT_WINDOW allow; appops set com.mytv.launcher ACCESS_RESTRICTED_SETTINGS allow"

        /**
         * Switched off (never uninstalled) by the debugging-connection fallback:
         * Google TV's home screen (it would grab the Home button) and extras
         * nobody uses on a Chromecast. Casting, the Assistant, the Play Store,
         * Ambient mode (Google Photos) and every streaming app are left alone.
         */
        val BLOAT = listOf(
            GOOGLE_TV_HOME,
            "com.google.android.feedback",
            "com.google.android.play.games",
            "com.android.printspooler",
        )
    }
}
