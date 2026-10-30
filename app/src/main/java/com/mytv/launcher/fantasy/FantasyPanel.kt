package com.mytv.launcher.fantasy

import android.annotation.SuppressLint
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mytv.launcher.MainActivity
import com.mytv.launcher.sports.EspnClient
import com.mytv.launcher.sports.NbaGames
import com.mytv.launcher.sports.Teams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the Fantasy tile and the panel's play row show. */
data class FantasyNow(val week: FantasyWeek?, val live: List<LivePlayer>)

/**
 * Your fantasy matchup, slid in over the right half of the screen on top of
 * whatever is playing: Fantrax's own live matchup screen (signed in as you),
 * with a ▶ row above it for your starters (and theirs) who are playing right
 * now. The fantasy button (or Back) slides it away again.
 */
object FantasyPanel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fantrax = FantraxClient()
    private val espn = EspnClient()
    private var root: FrameLayout? = null
    private var playRow: LinearLayout? = null
    private var web: WebView? = null
    private var refreshJob: Job? = null
    private var last: FantasyNow? = null

    val isShowing: Boolean get() = root != null

    /** The matchup and who's playing now (for the home screen's Fantasy tile too). */
    suspend fun fetch(): FantasyNow {
        val week = scope.async { fantrax.week() }
        val games = scope.async {
            // After midnight, last night's late games are still on.
            val today = LocalDate.now()
            espn.scoreboard(Teams.nba, today) + espn.scoreboard(Teams.nba, today.minusDays(1))
        }
        val w = week.await() ?: last?.week
        return FantasyNow(w, w?.let { FantraxData.liveNow(it, games.await()) }.orEmpty()).also { last = it }
    }

    suspend fun signedIn(): Boolean = fantrax.signedIn()

    fun toggle(context: Context) = scope.launch { if (isShowing) hide() else show(context) }

    @SuppressLint("SetJavaScriptEnabled")
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
        val ctx = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) context else app
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val width = ctx.resources.displayMetrics.widthPixels / 2

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        }
        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF0E0E12.toInt())
            isFocusable = true
            webViewClient = WebViewClient()
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val frame = object : FrameLayout(ctx) {
            // Back closes it, wherever you are in it (the fantasy button does too, through the home helper).
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) hide()
                return true
            }
        }.apply {
            setBackgroundColor(0xF20E0E12.toInt())
            addView(column)
        }
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.TOP }
        if (runCatching { wm.addView(frame, params) }.isFailure) {
            webView.destroy()
            return
        }
        root = frame
        playRow = row
        web = webView
        renderRow(ctx, last)
        frame.translationX = width.toFloat()
        frame.animate().translationX(0f).setDuration(260).start()

        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (fantrax.signedIn()) webView.loadUrl(FantasyLeague.MATCHUP_URL)
            else webView.loadData(SIGN_IN_FIRST, "text/html", "utf-8")
            var reloads = 0
            while (isActive && root === frame) {
                val now = fetch()
                if (root !== frame) break
                renderRow(ctx, now)
                delay(60_000L)
                // Fresh scores every couple of minutes, if Fantrax's page doesn't update itself.
                if (++reloads % 2 == 0 && webView.url?.startsWith("https://www.fantrax.com") == true) webView.reload()
            }
        }
    }

    private fun hide() {
        val frame = root ?: return
        root = null
        playRow = null
        val webView = web
        web = null
        refreshJob?.cancel()
        val wm = frame.context.getSystemService(WindowManager::class.java)
        frame.animate().translationX(frame.width.toFloat()).setDuration(220)
            .withEndAction {
                runCatching { wm?.removeView(frame) }
                webView?.destroy()
            }
            .start()
    }

    /** The ▶ row: everyone in either lineup whose game is on now. */
    private fun renderRow(context: Context, now: FantasyNow?) {
        val row = playRow ?: return
        val focused = row.findFocus()?.tag as? String
        row.removeAllViews()
        val live = now?.live.orEmpty()
        if (live.isEmpty()) {
            row.addView(label(context, if (now == null) "Fantasy · loading…" else "Fantasy · nobody in your matchup is playing right now"))
            if (root?.findFocus() == null) web?.requestFocus()
            return
        }
        row.addView(label(context, "On now"))
        var first: View? = null
        var wanted: View? = null
        live.forEach { lp ->
            val chip = chip(context, lp)
            row.addView(chip)
            if (first == null) first = chip
            if (lp.player.id == focused) wanted = chip
        }
        // Keep the ▶ you were on; don't pull you out of Fantrax's page while you scroll it.
        if (focused != null || root?.findFocus() == null) (wanted ?: first)?.requestFocus()
    }

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextColor(0xFFA1A1AA.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, dp(context, 12), 0)
    }

    /** "▶ Luka Doncic · LAL": your players in green, theirs in grey. */
    private fun chip(context: Context, lp: LivePlayer) = TextView(context).apply {
        text = "▶  ${lp.player.name} · ${lp.player.nbaTeam}"
        tag = lp.player.id
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = true
        setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
        val fill = if (lp.mine) 0xFF1F7A3D.toInt() else 0xFF3A3A44.toInt()
        val normal = GradientDrawable().apply { cornerRadius = dp(context, 18).toFloat(); setColor(fill) }
        val selected = GradientDrawable().apply { cornerRadius = dp(context, 18).toFloat(); setColor(fill); setStroke(dp(context, 3), 0xFFFFFFFF.toInt()) }
        background = normal
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(context, 10) }
        setOnFocusChangeListener { v, has -> v.background = if (has) selected else normal }
        setOnClickListener { watch(context, lp) }
    }

    /** ▶: the panel slides away and MyTV switches to that player's game, in whichever app has it. */
    private fun watch(context: Context, lp: LivePlayer) {
        hide()
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_WATCH_NBA, NbaGames.espnAbbr(lp.player.nbaTeam))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private const val SIGN_IN_FIRST = "<html><body style='background:#0e0e12;color:#f5f5f7;font-family:sans-serif;padding:24px'>" +
        "<h2>Sign in to Fantrax</h2><p>Fantrax only shows live scoring to the league's members. On MyTV: " +
        "<b>⚙ settings → Fantasy basketball → Sign in to Fantrax</b> (once).</p></body></html>"

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
