package com.mytv.launcher.system

import android.content.Context

/** MyTV's own settings (the System screen), shared by the app, the screensaver and the alerts. */
class MyTvSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mytv_settings", Context.MODE_PRIVATE)

    /** MyTV opens whenever Google TV's home screen would (see HomeOverride). */
    var homeOverride: Boolean
        get() = prefs.getBoolean("home_override", false)
        set(v) = prefs.edit().putBoolean("home_override", v).apply()

    /** Pop-ups over other apps when a game is about to start. */
    var kickoffAlerts: Boolean
        get() = prefs.getBoolean("kickoff_alerts", true)
        set(v) = prefs.edit().putBoolean("kickoff_alerts", v).apply()

    /** Pop-ups for goals / scores and the final result (off = spoiler-free). */
    var scoreAlerts: Boolean
        get() = prefs.getBoolean("score_alerts", true)
        set(v) = prefs.edit().putBoolean("score_alerts", v).apply()

    /** "stable" (default: only builds you release) or "latest" (every test build). */
    var updateChannel: String
        get() = prefs.getString("update_channel", "stable") ?: "stable"
        set(v) = prefs.edit().putString("update_channel", v).apply()

    /** The remote button that slides the fantasy panel in and out (0 = none chosen). */
    var fantasyKey: Int
        get() = prefs.getInt("fantasy_key", 0)
        set(v) = prefs.edit().putInt("fantasy_key", v).apply()

    /** Who's watching: "me", "ang" or "guest". */
    var profile: String
        get() = prefs.getString("profile", "me") ?: "me"
        set(v) = prefs.edit().putString("profile", v).apply()

    /** Sleep timer: when to put the TV to sleep (0 = off), or at the end of the current episode. */
    var sleepAt: Long
        get() = prefs.getLong("sleep_at", 0L)
        set(v) = prefs.edit().putLong("sleep_at", v).apply()
    var sleepAfterEpisode: Boolean
        get() = prefs.getBoolean("sleep_episode", false)
        set(v) = prefs.edit().putBoolean("sleep_episode", v).apply()
}
