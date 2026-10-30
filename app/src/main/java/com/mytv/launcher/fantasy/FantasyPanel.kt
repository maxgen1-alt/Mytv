package com.mytv.launcher.fantasy

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
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
import com.mytv.launcher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * Your fantasy matchup, slid in over the right half of the screen on top of
 * whatever is playing. The fantasy button (or Back) slides it away again.
 * Players in a live game get a ▶ that switches to that game.
 */
object FantasyPanel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = FantraxClient()
    private val pts = DecimalFormat("0.#")
    private var root: FrameLayout? = null
    private var content: LinearLayout? = null
    private var refreshJob: Job? = null
    private var last: FantasyResult? = null
    private var updatedAt = 0L

    val isShowing: Boolean get() = root != null

    /** The last matchup fetched (for the home screen's Fantasy tile). */
    suspend fun fetch(): FantasyResult = client.matchup().also { if (it is FantasyResult.Ok || last == null) last = it }

    fun toggle(context: Context) = scope.launch { if (isShowing) hide() else show(context) }

    private fun show(context: Context) {
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
        val windowContext = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) context else app
        val wm = windowContext.getSystemService(WindowManager::class.java) ?: return
        val width = windowContext.resources.displayMetrics.widthPixels / 2

        val list = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(windowContext, 28), dp(windowContext, 24), dp(windowContext, 28), dp(windowContext, 24))
        }
        val frame = object : FrameLayout(windowContext) {
            // Back closes it, whichever ▶ is selected (the fantasy button does too, through the home helper).
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
        }.apply {
            setBackgroundColor(0xF20E0E12.toInt())
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(ScrollView(windowContext).apply { isFillViewport = true; addView(list) })
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
        render(windowContext, last, focus = null)
        frame.translationX = width.toFloat()
        frame.animate().translationX(0f).setDuration(260).start()

        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive && root === frame) {
                val result = client.matchup()
                if (result is FantasyResult.Ok || last !is FantasyResult.Ok) last = result
                updatedAt = System.currentTimeMillis()
                if (root !== frame) break
                // Keep the ▶ you're on selected across refreshes.
                render(windowContext, last, focus = (frame.findFocus()?.tag as? String))
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

    private fun render(context: Context, result: FantasyResult?, focus: String?) {
        val list = content ?: return
        list.removeAllViews()
        fun text(s: String, size: Float, color: Int = 0xFFF5F5F7.toInt(), bold: Boolean = false) = TextView(context).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        list.addView(text("Fantasy · live", 13f, 0xFFA1A1AA.toInt()))
        when (result) {
            null -> { list.addView(text("Loading…", 18f)); focusPanel(); return }
            is FantasyResult.Error -> { list.addView(text(result.message, 18f)); focusPanel(); return }
            is FantasyResult.Ok -> Unit
        }
        val m = (result as? FantasyResult.Ok)?.matchup ?: return
        val winning = m.mine.points >= m.them.points
        list.addView(scoreLine(context, m.mine, if (winning) 0xFF7CD992.toInt() else 0xFFF5F5F7.toInt()))
        list.addView(scoreLine(context, m.them, if (!winning) 0xFFFF8A80.toInt() else 0xFFF5F5F7.toInt()))
        val live = (m.mine.players + m.them.players).count { it.state == FState.LIVE }
        list.addView(text(
            (if (live > 0) "$live playing now · " else "") +
                "Updated " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())),
            12f, 0xFFA1A1AA.toInt(),
        ).apply { setPadding(0, dp(context, 4), 0, dp(context, 10)) })

        var firstPlay: View? = null
        var wanted: View? = null
        for (team in listOf(m.mine, m.them)) {
            list.addView(text(team.name, 16f, bold = true).apply { setPadding(0, dp(context, 14), 0, dp(context, 6)) })
            if (team.players.isEmpty()) list.addView(text("No players in the lineup yet", 13f, 0xFFA1A1AA.toInt()))
            team.players.forEach { p ->
                val (row, play) = playerRow(context, p)
                list.addView(row)
                if (play != null) {
                    if (firstPlay == null) firstPlay = play
                    if (p.id == focus) wanted = play
                }
            }
        }
        (wanted ?: firstPlay)?.requestFocus() ?: focusPanel()
    }

    private fun focusPanel() { root?.requestFocus() }

    private fun scoreLine(context: Context, team: FTeam, color: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 6), 0, 0)
        addView(TextView(context).apply {
            text = team.name
            setTextColor(0xFFF5F5F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = pts.format(team.points)
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    /** One player: ▶ (live games only), name, team · position · game, fantasy points. */
    private fun playerRow(context: Context, p: FPlayer): Pair<View, View?> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 5), 0, dp(context, 5))
        }
        val play = if (p.state == FState.LIVE && p.nbaTeam.isNotBlank()) TextView(context).apply {
            text = "▶"
            tag = p.id
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isFocusable = true
            val normal = GradientDrawable().apply { cornerRadius = dp(context, 16).toFloat(); setColor(0xFFE5383B.toInt()) }
            val focused = GradientDrawable().apply { cornerRadius = dp(context, 16).toFloat(); setColor(0xFFE5383B.toInt()); setStroke(dp(context, 3), 0xFFFFFFFF.toInt()) }
            background = normal
            setOnFocusChangeListener { v, has ->
                v.background = if (has) focused else normal
                v.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(120).start()
            }
            setOnClickListener { watch(context, p) }
        } else null
        row.addView(play ?: View(context), LinearLayout.LayoutParams(dp(context, 40), dp(context, 32)).apply { marginEnd = dp(context, 12) })
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = p.name
                setTextColor(0xFFF5F5F7.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = if (p.state == FState.LIVE) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = listOf(p.nbaTeam, p.position, p.status).filter { it.isNotBlank() }.joinToString(" · ")
                setTextColor(if (p.state == FState.LIVE) 0xFFFF8A80.toInt() else 0xFFA1A1AA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = pts.format(p.points)
            setTextColor(0xFFF5F5F7.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        return row to play
    }

    /** ▶: the panel slides away and MyTV switches to that player's game, in whichever app has it. */
    private fun watch(context: Context, p: FPlayer) {
        hide()
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_WATCH_NBA, p.nbaTeam)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
