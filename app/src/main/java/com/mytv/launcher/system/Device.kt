package com.mytv.launcher.system

import android.content.Context
import android.os.Build

/**
 * Which kind of TV box MyTV is running on. Everything Fire TV-specific checks
 * [isFireTv], so on a Chromecast / Google TV Streamer MyTV behaves exactly as before.
 */
object Device {
    /** Amazon's own home screen on Fire TV (the Home button and power-on always go here). */
    const val FIRE_TV_HOME = "com.amazon.tv.launcher"

    /** Amazon Appstore (Fire TV has no Google Play). */
    const val AMAZON_APPSTORE = "com.amazon.venezia"

    @Volatile private var fire: Boolean? = null

    /** A Fire TV Stick / Cube / Fire TV smart TV (Fire OS). */
    fun isFireTv(context: Context): Boolean = fire ?: (
        runCatching { context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") }.getOrDefault(false) ||
            (Build.MANUFACTURER.equals("Amazon", ignoreCase = true) && Build.MODEL.startsWith("AFT"))
        ).also { fire = it }

    /** The box's own home screen, which MyTV's home helper opens MyTV over. */
    fun homePackage(context: Context): String = if (isFireTv(context)) FIRE_TV_HOME else HomeSetup.GOOGLE_TV_HOME

    /** "Fire TV" or "Google TV", for messages. */
    fun homeName(context: Context): String = if (isFireTv(context)) "Fire TV" else "Google TV"
}
