package com.mytv.launcher.system

import android.view.KeyEvent

/** Choosing the remote button for the fantasy panel (MyTV settings → Fantasy basketball). */
object FantasyButton {
    /** The next button pressed (not a reserved one) becomes the fantasy button. */
    @Volatile var choosing = false
    @Volatile var onChosen: ((Int) -> Unit)? = null

    /** Buttons you need for everything else: never taken. */
    val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
    )

    /** "KEYCODE_PROG_RED" -> "Prog red". */
    fun label(code: Int): String {
        if (code == 0) return "none"
        val raw = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
        return raw.replaceFirstChar { it.uppercase() }
    }
}
