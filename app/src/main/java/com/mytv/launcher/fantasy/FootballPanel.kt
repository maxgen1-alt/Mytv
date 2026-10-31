package com.mytv.launcher.fantasy

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mytv.launcher.sports.GameOpener
import com.mytv.launcher.sports.GameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Your fantasy football matchup (ESPN), slid in over the right half of the
 * screen on top of whatever is playing: both scores and projections, and every
 * starter's points, projection and NFL game. Up/down moves through the
 * players; OK on one whose game is on switches to it. The football button (or
 * Back) slides it away again.
 */
object FootballPanel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val live = FootballLive()
    private var root: FrameLayout? = null
    private var content: LinearLayout? = null
    private var refreshJob: Job? = null
    private var last: FootballMatchup? = null
    private var updatedAt = 0L

    private const val INK = 0xFFF5F5F7.toInt()
    private const val SOFT = 0xFFA1A1AA.toInt()
    private const val GREEN = 0xFF7CD992.toInt()
    private const val RED = 0xFFFF8A80.toInt()
    private const val LIVE_RED = 0xFFE5383B.toInt()
    private val kickoff = DateTimeFormatter.ofPattern("EEE h:mm a")

    val isShowing: Boolean get() = root != null

    /** The matchup now (the last good one if ESPN can't be reached). */
    suspend fun fetch(): FootballMatchup? {
        live.matchup()?.let { last = it; updatedAt = System.currentTimeMillis() }
        return last
    }

    fun toggle(context: Context) = scope.launch { if (isShowing) hide() else show(context) }

    /** Slides it away if it's out (the basketball panel opening). */
    fun close() = scope.launch { hide() }

    private fun show(context: Context) {
        FantasyPanel.close()
        val app = context.applicationContext
        val type = when {
            Settings.canDrawOverlays(app) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            context is android.accessibilityservice.AccessibilityService -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else -> {
                Toast.makeText(app, "MyTV needs \"Display over other apps\" for the fantasy panel", Toast.LENGTH_LONG).show()
                return
            }
        }
        val ctx = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) context else app
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val width = ctx.resources.displayMetrics.widthPixels / 2

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 22), dp(ctx, 28), dp(ctx, 22))
        }
        val frame = object : FrameLayout(ctx) {
            // Back closes it, wherever you are in it.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
        }.apply {
            setBackgroundColor(0xF50E0E12.toInt())
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(list) })
        }
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.TOP }
        if (runCatching { wm.addView(frame, params) }.isFailure) return
        root = frame
        content = list
        render(ctx, last, focus = null)
        frame.translationX = width.toFloat()
        frame.animate().translationX(0f).setDuration(260).start()

        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive && root === frame) {
                fetch()
                if (root !== frame) break
                render(ctx, last, focus = frame.findFocus()?.tag as? String)
                delay(30_000L)
            }
        }
    }

    private fun hide() {
        val frame = root ?: return
        root = null
        content = null
        refreshJob?.cancel()
        val wm = frame.context.getSystemService(WindowManager::class.java)
        frame.animate().translationX(frame.width.toFloat()).setDuration(220)
            .withEndAction { runCatching { wm?.removeView(frame) } }
            .start()
    }

    private fun render(context: Context, m: FootballMatchup?, focus: String?) {
        val list = content ?: return
        list.removeAllViews()
        if (m == null) {
            list.addView(text(context, "Fantasy football", 14f, SOFT))
            list.addView(text(context, "Loading your matchup…", 20f, INK, bold = true))
            root?.requestFocus()
            return
        }
        val ahead = m.mine.points - m.theirs.points
        list.addView(text(context, "Week ${m.week}  ·  updated ${time(updatedAt)}", 13f, SOFT))
        list.addView(scoreLine(context, m.mine.name, m.mine.points, when { ahead > 0 -> GREEN; ahead < 0 -> RED; else -> INK }))
        list.addView(scoreLine(context, m.theirs.name, m.theirs.points, SOFT))
        val projection = listOfNotNull(
            if (m.mine.projected != null && m.theirs.projected != null) "Projected ${pts(m.mine.projected)} – ${pts(m.theirs.projected)}" else null,
            m.mine.winChance?.let { "${(it * 100).toInt()}% to win" },
        ).joinToString("  ·  ")
        if (projection.isNotEmpty()) list.addView(text(context, projection, 15f, SOFT).apply { setPadding(0, dp(context, 4), 0, dp(context, 6)) })
        val redZone = redZoneButton(context)
        list.addView(redZone)
        if (focus == redZone.tag) redZone.requestFocus()

        var first: View? = null
        var firstLive: View? = null
        var wanted: View? = null
        for ((side, mine) in listOf(m.mine to true, m.theirs to false)) {
            list.addView(text(context, side.name, 15f, SOFT, bold = true).apply { setPadding(0, dp(context, 14), 0, dp(context, 6)) })
            side.starters.forEach { p ->
                val row = playerRow(context, p, mine)
                list.addView(row)
                if (first == null) first = row
                if (firstLive == null && p.live && mine) firstLive = row
                if (row.tag == focus) wanted = row
            }
        }
        if (focus != redZone.tag) (wanted ?: firstLive ?: first)?.requestFocus() ?: root?.requestFocus()
    }

    private fun scoreLine(context: Context, name: String, points: Double, color: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(context, name, 22f, INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(context, pts(points), 36f, color, bold = true))
    }

    /** NFL RedZone: one press opens the NFL app on it (or its Google Play page, if it isn't installed yet). */
    private fun redZoneButton(context: Context) = TextView(context).apply {
        val installed = com.mytv.launcher.apps.RedZone.installed(context)
        text = if (installed) "▶  Watch NFL RedZone" else "Get the NFL app (for RedZone)"
        tag = "redzone"
        isFocusable = true
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        val normal = GradientDrawable().apply { cornerRadius = dp(context, 22).toFloat(); setColor(0xFFC8102E.toInt()) }
        val selected = GradientDrawable().apply { cornerRadius = dp(context, 22).toFloat(); setColor(0xFFC8102E.toInt()); setStroke(dp(context, 3), 0xFFFFFFFF.toInt()) }
        background = normal
        setOnFocusChangeListener { v, has -> v.background = if (has) selected else normal }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 4)
            bottomMargin = dp(context, 4)
        }
        setOnClickListener {
            hide()
            com.mytv.launcher.apps.RedZone.open(context)
        }
    }

    /** One starter: slot and name, NFL game, then points and projection. OK on a live one opens the game. */
    private fun playerRow(context: Context, p: FootballPlayer, mine: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = "${if (mine) "m" else "t"}:${p.slot}:${p.name}"
        isFocusable = true
        setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
        val normal = GradientDrawable().apply { cornerRadius = dp(context, 10).toFloat(); setColor(if (p.live) 0x22E5383B else 0x14FFFFFF) }
        val selected = GradientDrawable().apply { cornerRadius = dp(context, 10).toFloat(); setColor(if (p.live) 0x44E5383B else 0x2AFFFFFF); setStroke(dp(context, 3), 0xFFFFFFFF.toInt()) }
        background = normal
        setOnFocusChangeListener { v, has -> v.background = if (has) selected else normal }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 6) }
        if (p.live) setOnClickListener { watch(context, p) }

        addView(text(context, p.slot, 13f, SOFT, bold = true), LinearLayout.LayoutParams(dp(context, 52), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (p.live) addView(text(context, "▶ ", 16f, LIVE_RED, bold = true))
                addView(text(context, p.name, 17f, INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            })
            addView(text(context, gameText(p), 13f, if (p.live) RED else SOFT, bold = p.live).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(text(context, pts(p.points), 20f, INK, bold = true).apply { gravity = Gravity.END })
            p.projected?.let { addView(text(context, "proj ${pts(it)}", 12f, SOFT).apply { gravity = Gravity.END }) }
        })
    }

    /** "@ BUF · Q2 5:21", "vs MIN · Sun 1:00 PM", "@ DEN · Final", "Bye". */
    private fun gameText(p: FootballPlayer): String {
        val g = p.game ?: return "Bye"
        val home = g.home.teamId == p.nflTeamId
        val them = if (home) g.away.abbreviation else g.home.abbreviation
        val status = when (g.state) {
            GameState.UPCOMING -> Instant.ofEpochMilli(g.startMillis).atZone(ZoneId.systemDefault()).format(kickoff)
            GameState.LIVE -> g.statusText.ifBlank { "Live" }
            GameState.FINISHED -> "Final"
        }
        return "${if (home) "vs" else "@"} $them · $status"
    }

    /** OK on a live player: the panel slides away and their game opens right from where you are. */
    private fun watch(context: Context, p: FootballPlayer) {
        val game = p.game ?: return
        hide()
        scope.launch {
            GameOpener(context).open(
                game,
                "${game.away.abbreviation} @ ${game.home.abbreviation}",
                listOf(game.home.name, game.away.name).map { it.split(' ').last() },
            )
        }
    }

    private fun pts(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun time(millis: Long) = if (millis == 0L) "–" else
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))

    private fun text(context: Context, s: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
