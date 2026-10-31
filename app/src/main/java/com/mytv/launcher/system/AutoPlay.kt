package com.mytv.launcher.system

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * One click to a game in Prime Video: after MyTV opens a game's page there,
 * the home helper presses the buttons you'd press yourself, for a few seconds
 * only: your profile on "Who's watching?" ([PROFILE], else the one Prime has highlighted),
 * then Watch live (Watch now for a game that's over). It presses nothing
 * else, never Buy/Rent/Subscribe/Trailer, and only on the page of the game
 * you picked (both team names on screen).
 */
object AutoPlay {
    private val main = Handler(Looper.getMainLooper())

    private class Target(val pkg: String, val words: List<String>, var until: Long) {
        var clicks = 0
        var lastClick = 0L
        var onGamePage = false
    }

    @Volatile private var target: Target? = null
    private var service: AccessibilityService? = null
    private val step = Runnable { service?.let(::step) }

    /** Your profile on Prime Video. */
    private const val PROFILE = "max"

    private val profileScreen = listOf("who's watching", "who’s watching", "who is watching", "choose a profile", "choose your profile", "select a profile")
    private val liveButtons = listOf("watch live", "join live", "live now")
    private val watchButtons = listOf("watch now", "resume", "play now", "watch from")
    private val never = listOf("trailer", "beginning", "start over", "buy", "rent", "subscribe", "trial", "sign up", "add to", "channel", "episodes", "more")

    /** Arms the helper for the game page MyTV is about to open in [pkg]; [teams] are its two teams as Prime writes them. */
    fun start(pkg: String, teams: List<String>) {
        val t = Target(pkg, teams.map { it.lowercase().trim() }.filter { it.isNotEmpty() }, SystemClock.uptimeMillis() + 40_000L)
        target = t
        // Nothing to press by then: say so (and stop).
        main.postDelayed({
            if (target === t) {
                target = null
                if (t.clicks == 0) say("MyTV couldn't find Prime's buttons to press")
            }
        }, 40_000L)
    }

    /** Every Prime Video screen change, while armed: look again once it settles. */
    fun onEvent(svc: AccessibilityService, event: AccessibilityEvent) {
        val t = target ?: return
        if (event.packageName?.toString() != t.pkg) return
        service = svc
        main.removeCallbacks(step)
        main.postDelayed(step, 700L)
    }

    private fun step(svc: AccessibilityService) {
        val t = target ?: return
        val now = SystemClock.uptimeMillis()
        if (now > t.until) { target = null; return }
        if (now - t.lastClick < 1_500L) { main.postDelayed(step, 1_500L); return }
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return
        if (root.packageName?.toString() != t.pkg) return
        val nodes = flatten(root)
        val labelled = nodes.map { it to label(it) }.filter { it.second.isNotEmpty() }

        // "Who's watching?": yours, else the profile Prime already has selected.
        if (labelled.any { (_, text) -> profileScreen.any { text.contains(it) } }) {
            val pick = labelled.firstOrNull { (_, text) -> text == PROFILE || text.split(' ', ',', '.').firstOrNull() == PROFILE }?.first
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: labelled.firstOrNull { (n, text) -> n.isClickable && profileScreen.none { text.contains(it) } && listOf("add", "edit", "kids", "manage").none { text.contains(it) } }?.first
            if (pick != null && click(pick)) { say("MyTV pressed: ${label(pick).ifEmpty { "your profile" }}"); done(t, now, final = false) }
            return
        }

        // The game's page: both teams on screen. Then Watch live (or a "Watch live / from the beginning" choice).
        if (t.words.isNotEmpty() && t.words.all { w -> labelled.any { it.second.contains(w) } }) t.onGamePage = true
        if (!t.onGamePage) return
        fun button(names: List<String>) = labelled.firstOrNull { (_, text) ->
            text.length <= 40 && names.any { text.startsWith(it) } && never.none { text.contains(it) }
        }?.first
        val live = button(liveButtons)
        if (live != null) {
            if (click(live)) { say("MyTV pressed: Watch live"); done(t, now, final = true) }
            return
        }
        val watch = button(watchButtons)
        if (watch != null && click(watch)) { say("MyTV pressed: ${label(watch)}"); done(t, now, final = false) }
    }

    /** After a click: a little longer for the next screen, then stop. */
    private fun done(t: Target, now: Long, final: Boolean) {
        t.clicks++
        t.lastClick = now
        t.until = minOf(t.until, now + if (final) 6_000L else 15_000L)
        if (t.clicks >= 4) target = null
    }

    /** A short note on screen, so you can see what the helper did. */
    private fun say(msg: String) {
        val ctx = service ?: return
        main.post { android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").lowercase().trim()

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && out.size < 1500) {
            val n = queue.removeFirst()
            if (!n.isVisibleToUser) continue
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }
}
