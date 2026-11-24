package com.mytv.launcher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Fire TV: Fire OS sometimes switches third-party accessibility services off (after a restart,
 * an update, or on its own), and then the Home button goes back to Amazon's home screen. While
 * MyTV is meant to be the home screen, this turns the home helper back on whenever it finds it
 * off: when MyTV starts, when the TV wakes up, at boot, and every half minute from watch
 * tracking. Needs the "write secure settings" permission granted during setup.
 * Does nothing on Google TV.
 */
object HomeKeeper {
    private const val ACCESSIBILITY = "enabled_accessibility_services"
    @Volatile private var lastRestart = 0L

    fun ensure(context: Context) {
        runCatching {
            val ctx = context.applicationContext
            if (!Device.isFireTv(ctx)) return
            if (!MyTvSettings(ctx).homeOverride) return
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
            val cr = ctx.contentResolver
            val list = Settings.Secure.getString(cr, ACCESSIBILITY)?.split(':')?.filter { it.isNotBlank() }.orEmpty()
            val listed = list.any { it.equals(HomeOverride.component, ignoreCase = true) }
            if (!listed) {
                // Switched off: put it back.
                Settings.Secure.putString(cr, ACCESSIBILITY, (list + HomeOverride.component).distinct().joinToString(":"))
                Settings.Secure.putInt(cr, "accessibility_enabled", 1)
                return
            }
            if (Settings.Secure.getInt(cr, "accessibility_enabled", 0) != 1) Settings.Secure.putInt(cr, "accessibility_enabled", 1)
            // Listed but not running (Fire OS stopped it): switch it off and on again, at most every 2 minutes.
            val now = SystemClock.elapsedRealtime()
            if (!HomeOverride.isRunning && !systemSaysRunning(ctx) && now - lastRestart > 120_000L) {
                lastRestart = now
                val without = list.filterNot { it.equals(HomeOverride.component, ignoreCase = true) }
                Settings.Secure.putString(cr, ACCESSIBILITY, without.joinToString(":"))
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching {
                        val again = Settings.Secure.getString(cr, ACCESSIBILITY)?.split(':')?.filter { it.isNotBlank() }.orEmpty()
                        Settings.Secure.putString(cr, ACCESSIBILITY, (again + HomeOverride.component).distinct().joinToString(":"))
                        Settings.Secure.putInt(cr, "accessibility_enabled", 1)
                    }
                }, 1_000L)
            }
        }
    }

    private fun systemSaysRunning(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    }.getOrDefault(false)

    /** At boot and when the TV wakes up. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = ensure(context)
    }
}
