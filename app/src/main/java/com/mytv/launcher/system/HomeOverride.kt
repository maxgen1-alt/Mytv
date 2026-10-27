package com.mytv.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.mytv.launcher.MainActivity

/**
 * Makes MyTV the home screen without switching anything off: whenever Google
 * TV's home screen comes up (Home button, TV turning on), MyTV opens on top of
 * it straight away. Its other screens (search results...) are left alone.
 * Only listens to that one app's window changes; reads nothing on screen.
 * Turned on / off in MyTV settings ([MyTvSettings.homeOverride]).
 */
class HomeOverride : AccessibilityService() {
    private val settings by lazy { MyTvSettings(this) }
    private var homeClass: String? = null
    private var lastLaunch = 0L

    override fun onServiceConnected() {
        homeClass = googleHomeClass()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != HomeSetup.GOOGLE_TV_HOME) return
        if (!settings.homeOverride) return
        // Any Google TV home window except its search screens (voice search results stay put).
        val cls = event.className?.toString().orEmpty()
        if (cls.contains("search", ignoreCase = true) || cls.contains("voice", ignoreCase = true)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < 800) return
        lastLaunch = now
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }

    override fun onInterrupt() {}

    /** Google TV's home activity, as the system resolves the Home button to it. */
    private fun googleHomeClass(): String? =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .firstOrNull { it.activityInfo.packageName == HomeSetup.GOOGLE_TV_HOME }
            ?.activityInfo?.let { it.targetActivity ?: it.name }

    companion object {
        val component: String get() = "com.mytv.launcher/com.mytv.launcher.system.HomeOverride"

        @Volatile private var instance: HomeOverride? = null

        /** Sleep timer: puts the TV to sleep (screen off; the TV turns off over HDMI-CEC). */
        fun sleep(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}
