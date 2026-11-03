package com.mytv.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One click to a game in Prime Video: after MyTV opens a game's page there,
 * the home helper presses the buttons you'd press yourself, for a few seconds
 * only: your profile on "Who's watching?" ([PROFILE], else the one Prime has highlighted),
 * then Watch / Watch live. It presses nothing else, never Buy/Rent/Subscribe/Trailer.
 * The same for NFL RedZone in the NFL app: RedZone, then Watch.
 *
 * It looks at the screen several times a second while it's armed (Prime doesn't always
 * say when its screen changes). If Prime's screen can't be read, it presses OK on the
 * remote instead, like you would: on the profile Prime highlights, then Watch, then
 * Watch live. It stops the moment the game is playing, so it never pauses it.
 */
object AutoPlay {
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Target(
        val pkg: String,
        val words: List<String>,
        var until: Long,
        /** Buttons to press in order (each a list of names), instead of the Prime game routine. */
        val steps: List<List<String>>? = null,
        val app: String = "Prime",
    ) {
        var stage = 0
        var clicks = 0
        var lastClick = 0L
        var onGamePage = false
        /** When the app first came to the front (0 = not yet). */
        var frontSince = 0L
        /** OK presses made without reading the screen. */
        var blind = 0
        /** What the app was already playing when MyTV opened the game (another game, say): not this one. */
        var before: Set<Any> = emptySet()
    }

    /** Set at startup (MyTvApp), for messages when the helper isn't running. */
    @Volatile var appContext: android.content.Context? = null

    @Volatile private var target: Target? = null
    private var service: AccessibilityService? = null
    private val tick = object : Runnable {
        override fun run() {
            val t = target ?: return
            service?.let { runCatching { step(it) } }
            if (target === t) main.postDelayed(this, POLL_MS)
        }
    }

    /** Your profile on Prime Video. */
    private const val PROFILE = "max"
    private const val POLL_MS = 600L
    /** OK presses when the screen can't be read: profile, Watch, Watch live. */
    private const val MAX_BLIND = 3
    /** How long the screen must sit with nothing to press before pressing OK. */
    private const val FIRST_BLIND_WAIT = 5_000L
    private const val NEXT_BLIND_WAIT = 4_000L

    private val profileScreen = listOf("who's watching", "who’s watching", "who is watching", "choose a profile", "choose your profile", "select a profile")
    private val liveButtons = listOf("watch live", "join live", "live now")
    private val watchButtons = listOf("watch now", "watch", "resume", "play now", "play", "watch from")
    private val never = listOf("trailer", "beginning", "start over", "buy", "rent", "subscribe", "trial", "sign up", "add to", "channel", "episodes", "more", "watchlist")

    /** Arms the helper for the game page MyTV is about to open in [pkg]; [teams] are its two teams as Prime writes them. */
    fun start(pkg: String, teams: List<String>) =
        arm(Target(pkg, teams.map { it.lowercase().trim() }.filter { it.isNotEmpty() }, SystemClock.uptimeMillis() + 45_000L))

    /** Arms the helper to press [steps] in order in [app] ([pkg]), e.g. RedZone then Watch. */
    fun startSteps(pkg: String, app: String, steps: List<List<String>>) =
        arm(Target(pkg, emptyList(), SystemClock.uptimeMillis() + 45_000L, steps.map { names -> names.map { it.lowercase() } }, app))

    private fun arm(t: Target) {
        main.post {
            target = t
            service = HomeOverride.service ?: service
            service?.let { svc -> t.before = playingSessions(svc, t.pkg) }
            if (service == null) {
                // Without the helper nothing can be pressed: say how to turn it on.
                appContext?.let { ctx ->
                    android.widget.Toast.makeText(ctx,
                        "To press ${t.app}'s buttons for you, MyTV's home helper must be on (⚙ MyTV settings → Make MyTV my home screen)",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            main.removeCallbacks(tick)
            main.postDelayed(tick, POLL_MS)
            // Nothing pressed by then: say so (and stop).
            main.postDelayed({
                if (target === t) {
                    target = null
                    if (t.clicks == 0) say("MyTV couldn't find ${t.app}'s buttons to press")
                }
            }, 45_000L)
        }
    }

    /** A screen change in the app, while armed: look right away (the poll keeps looking too). */
    fun onEvent(svc: AccessibilityService, event: AccessibilityEvent) {
        val t = target ?: return
        if (event.packageName?.toString() != t.pkg) return
        service = svc
    }

    private fun step(svc: AccessibilityService) {
        val t = target ?: return
        val now = SystemClock.uptimeMillis()
        if (now > t.until) { target = null; return }
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
        if (root?.packageName?.toString() != t.pkg) return // not in front yet (still starting)
        if (t.frontSince == 0L) t.frontSince = now
        // The game is on: job done (never press anything on a playing game).
        val nowPlaying = playingSessions(svc, t.pkg)
        if (nowPlaying.isNotEmpty() && (t.clicks > 0 || (nowPlaying - t.before).isNotEmpty())) {
            if (t.clicks > 0) say("MyTV: the game is on")
            target = null
            return
        }
        if (now - t.lastClick < 1_500L) return
        val nodes = flatten(root)
        val labelled = nodes.map { it to label(it) }.filter { it.second.isNotEmpty() }

        // A set routine (NFL RedZone): the next button in line, by name.
        t.steps?.let { steps ->
            val names = steps.getOrNull(t.stage) ?: return
            val hit = labelled.firstOrNull { (_, text) -> text.length <= 60 && names.any { text.contains(it) } && never.none { text.contains(it) } }?.first
            if (hit != null && click(hit)) {
                say("MyTV pressed: ${label(hit)}")
                t.stage++
                done(t, now, final = t.stage >= steps.size)
            }
            return
        }

        // "Who's watching?": yours, else the profile Prime already has selected.
        if (labelled.any { (_, text) -> profileScreen.any { text.contains(it) } }) {
            val pick = labelled.firstOrNull { (_, text) -> text == PROFILE || text.split(' ', ',', '.').firstOrNull() == PROFILE }?.first
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: labelled.firstOrNull { (n, text) -> n.isClickable && profileScreen.none { text.contains(it) } && listOf("add", "edit", "kids", "manage").none { text.contains(it) } }?.first
            if (pick != null && click(pick)) { say("MyTV pressed: ${label(pick).ifEmpty { "your profile" }}"); done(t, now, final = false); return }
        } else {
            // The game's page: both teams on screen. Then Watch live (or Watch, which asks live or from the start).
            if (t.words.isNotEmpty() && t.words.all { w -> labelled.any { it.second.contains(w) } }) t.onGamePage = true
            // A "Watch live / from the beginning" choice counts as the game's page too.
            if (labelled.any { (_, text) -> liveButtons.any { text.startsWith(it) } }) t.onGamePage = true
            if (t.onGamePage) {
                fun button(names: List<String>) = labelled.firstOrNull { (_, text) ->
                    text.length <= 40 && names.any { text.startsWith(it) } && never.none { text.contains(it) }
                }?.first
                val live = button(liveButtons)
                if (live != null) {
                    if (click(live)) { say("MyTV pressed: Watch live"); done(t, now, final = true) }
                    return
                }
                val watch = button(watchButtons)
                if (watch != null && click(watch)) { say("MyTV pressed: ${label(watch)}"); done(t, now, final = false); return }
            }
        }

        // Nothing it could read to press: press OK, like you would (Prime highlights your
        // profile, then Watch, then Watch live). A few times at most, spaced out.
        val since = maxOf(t.frontSince, t.lastClick)
        val wait = if (t.clicks == 0) FIRST_BLIND_WAIT else NEXT_BLIND_WAIT
        if (t.blind < MAX_BLIND && t.clicks < 4 && now - since >= wait) {
            t.blind++
            pressOk(svc)
            say("MyTV pressed OK")
            done(t, now, final = false)
        }
    }

    /** After a click: a little longer for the next screen, then stop. */
    private fun done(t: Target, now: Long, final: Boolean) {
        t.clicks++
        t.lastClick = now
        if (final) {
            // Watch live pressed: give the game a few seconds to start, nothing more to press.
            t.until = minOf(t.until, now + 4_000L)
            t.stage = Int.MAX_VALUE
            target = null
            return
        }
        t.until = minOf(t.until, now + 15_000L)
        if (t.clicks >= 4) target = null
    }

    /** The remote's OK button: the helper's own (Android 13+), else over the TV's debugging connection. */
    private fun pressOk(svc: AccessibilityService) {
        if (Build.VERSION.SDK_INT >= 33 && svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)) return
        val ctx = svc.applicationContext
        io.launch { runCatching { HomeSetup(ctx).shell("input keyevent 23") } }
    }

    /** [pkg]'s media sessions playing right now. Needs MyTV's watch tracking (notification access); empty without it. */
    private fun playingSessions(svc: AccessibilityService, pkg: String): Set<Any> = runCatching {
        val msm = svc.getSystemService(MediaSessionManager::class.java) ?: return emptySet()
        msm.getActiveSessions(ComponentName(svc, com.mytv.launcher.watching.PlayingListener::class.java))
            .filter { c ->
                c.packageName == pkg && c.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }
            }
            .map { it.sessionToken as Any }
            .toSet()
    }.getOrDefault(emptySet())

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
