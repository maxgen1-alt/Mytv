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
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mytv.launcher.sports.GameOpener
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.NbaGames
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

/**
 * Your fantasy matchup, slid in over the right half of the screen on top of
 * whatever is playing: this week's and today's category scores, who's winning
 * each category, and every starter's line today. Up/down moves through the
 * players; OK on one who's playing switches to their game. The fantasy button
 * (or Back) slides it away again.
 */
object FantasyPanel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val live = FantasyLive()
    private var root: FrameLayout? = null
    private var content: LinearLayout? = null
    private var refreshJob: Job? = null
    private var last: FantasyMatchup? = null
    private var updatedAt = 0L

    private const val INK = 0xFFF5F5F7.toInt()
    private const val SOFT = 0xFFA1A1AA.toInt()
    private const val GREEN = 0xFF7CD992.toInt()
    private const val RED = 0xFFFF8A80.toInt()
    private const val LIVE_RED = 0xFFE5383B.toInt()
    private val tipOff = DateTimeFormatter.ofPattern("h:mm a")

    val isShowing: Boolean get() = root != null

    /** The matchup now (the last good one if Fantrax or ESPN can't be reached). */
    suspend fun fetch(): FantasyMatchup? {
        live.matchup()?.let { last = it; updatedAt = System.currentTimeMillis() }
        return last
    }

    fun toggle(context: Context) = scope.launch { if (isShowing) hide() else show(context) }

    /** Slides it away if it's out (the football panel opening). */
    fun close() = scope.launch { hide() }

    private fun show(context: Context) {
        FootballPanel.close()
        val app = context.applicationContext
        // A drawn-over-apps window (granted during MyTV's setup); from the home helper, its own overlay works too.
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
            // Back closes it, wherever you are in it (the fantasy button does too, through the home helper).
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
                // Keep the player you're on selected across refreshes.
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

    private fun render(context: Context, m: FantasyMatchup?, focus: String?) {
        val list = content ?: return
        list.removeAllViews()
        if (m == null) {
            list.addView(text(context, "Fantasy", 14f, SOFT))
            list.addView(text(context, "Loading your matchup…", 20f, INK, bold = true))
            root?.requestFocus()
            return
        }

        // Header: the week, big, then today.
        list.addView(text(context, "Week ${m.period}  ·  updated ${time(updatedAt)}", 13f, SOFT))
        list.addView(scoreRow(context, m.myTeam, m.week.toString(), "vs", m.opponent, winning(m.week)))
        list.addView(text(context, "Today ${m.today}", 18f, colorFor(m.today), bold = true).apply { setPadding(0, dp(context, 2), 0, dp(context, 10)) })

        // Every category this week: yours · theirs, the winner's side bright.
        val grid = GridLayout(context).apply { columnCount = 2 }
        m.weekCategories.forEach { c ->
            grid.addView(categoryCell(context, c), GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, dp(context, 8), dp(context, 6))
            })
        }
        list.addView(grid)

        // Players: yours, then theirs.
        var first: View? = null
        var wanted: View? = null
        var firstLive: View? = null
        for ((title, players) in listOf(m.myTeam to m.players.filter { it.mine }, m.opponent to m.players.filter { !it.mine })) {
            list.addView(text(context, "$title today", 15f, SOFT, bold = true).apply { setPadding(0, dp(context, 14), 0, dp(context, 6)) })
            if (players.isEmpty()) list.addView(text(context, "No lineup yet", 14f, SOFT))
            players.forEach { p ->
                val row = playerRow(context, p)
                list.addView(row)
                if (first == null) first = row
                if (firstLive == null && p.live && p.mine) firstLive = row
                if (p.player.id == focus) wanted = row
            }
        }
        (wanted ?: firstLive ?: first)?.requestFocus() ?: root?.requestFocus()
    }

    private fun scoreRow(context: Context, me: String, record: String, vs: String, them: String, ahead: Boolean?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 4), 0, 0)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(context, me, 22f, INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(context, record, 40f, when (ahead) { true -> GREEN; false -> RED; null -> INK }, bold = true))
        })
        addView(text(context, "$vs $them", 15f, SOFT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }

    private fun categoryCell(context: Context, c: CategoryResult) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 8).toFloat()
            setColor(when (c.outcome) { Outcome.WIN -> 0x332E9E57; Outcome.LOSS -> 0x33D9534F; Outcome.TIE -> 0x22FFFFFF })
        }
        addView(text(context, c.category.code, 13f, SOFT, bold = true), LinearLayout.LayoutParams(dp(context, 48), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text(context, c.mine, 15f, if (c.outcome == Outcome.WIN) GREEN else INK, bold = c.outcome == Outcome.WIN),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(context, c.theirs, 15f, if (c.outcome == Outcome.LOSS) RED else SOFT, bold = c.outcome == Outcome.LOSS).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** One starter: name, team and game, then today's line. Selectable; OK on a live one opens the game. */
    private fun playerRow(context: Context, p: LineupPlayer) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tag = p.player.id
        isFocusable = true
        setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
        val normal = GradientDrawable().apply { cornerRadius = dp(context, 10).toFloat(); setColor(if (p.live) 0x22E5383B else 0x14FFFFFF) }
        val selected = GradientDrawable().apply { cornerRadius = dp(context, 10).toFloat(); setColor(if (p.live) 0x44E5383B else 0x2AFFFFFF); setStroke(dp(context, 3), 0xFFFFFFFF.toInt()) }
        background = normal
        setOnFocusChangeListener { v, has -> v.background = if (has) selected else normal }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(context, 6) }
        if (p.live) setOnClickListener { watch(context, p) }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (p.live) addView(text(context, "▶ ", 16f, LIVE_RED, bold = true))
            addView(text(context, p.player.name, 17f, INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(context, gameText(p), 13f, if (p.live) RED else SOFT, bold = p.live).apply { maxLines = 1 })
        })
        addView(text(context, p.line?.summary() ?: "${p.player.nbaTeam} · ${p.player.position}", 14f, if (p.line != null) INK else SOFT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    /** "@ BOS · Q3 5:21", "vs MIA · 7:30 PM", "@ DEN · Final", or "No game". */
    private fun gameText(p: LineupPlayer): String {
        val g = p.game ?: return "No game"
        val team = NbaGames.espnAbbr(p.player.nbaTeam)
        val home = NbaGames.espnAbbr(g.home.abbreviation) == team
        val them = if (home) g.away.abbreviation else g.home.abbreviation
        val status = when (g.state) {
            GameState.UPCOMING -> Instant.ofEpochMilli(g.startMillis).atZone(ZoneId.systemDefault()).format(tipOff)
            GameState.LIVE -> g.statusText.ifBlank { "Live" }
            GameState.FINISHED -> "Final"
        }
        return "${if (home) "vs" else "@"} $them · $status"
    }

    /**
     * OK on a live player: the panel slides away and their game opens right
     * from where you are (MyTV never comes to the front), in whichever app has it.
     */
    private fun watch(context: Context, p: LineupPlayer) {
        val game = p.game ?: return
        hide()
        scope.launch {
            GameOpener(context).open(game, NbaGames.title(game), NbaGames.matchWords(game))
        }
    }

    private fun winning(r: Record): Boolean? = when {
        r.wins > r.losses -> true
        r.wins < r.losses -> false
        else -> null
    }

    private fun colorFor(r: Record) = when (winning(r)) { true -> GREEN; false -> RED; null -> INK }

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
