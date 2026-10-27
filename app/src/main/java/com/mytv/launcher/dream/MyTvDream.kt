package com.mytv.launcher.dream

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import com.mytv.launcher.snapshot.HomeSnapshot
import com.mytv.launcher.sports.EspnClient
import com.mytv.launcher.sports.GameDay
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.weather.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * MyTV's screensaver: big pictures of what you're watching and your picks,
 * slowly cross-fading, with the time, the weather and the next Arsenal /
 * Lakers game (or the live score). Plain views, no animations beyond the fade:
 * it may run for hours.
 */
class MyTvDream : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var back: ImageView
    private lateinit var front: ImageView
    private lateinit var caption: TextView
    private lateinit var weatherText: TextView
    private lateinit var sports: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        back = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        front = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(back, FrameLayout.LayoutParams(-1, -1))
        root.addView(front, FrameLayout.LayoutParams(-1, -1))
        // Darken the bottom so the text reads on any picture.
        root.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(-1, dp(360), Gravity.BOTTOM))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), 0, dp(56), dp(44))
        }
        info.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        weatherText = text(22f, 0xFFE8E8EC.toInt())
        sports = text(20f, 0xFFF5C518.toInt())
        caption = text(15f, 0xFFB8B8C0.toInt())
        info.addView(weatherText)
        info.addView(sports)
        info.addView(caption)
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
    }

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(0, dp(4), 0, 0)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scope.launch { slideshow() }
        scope.launch {
            while (isActive) {
                Weather.now()?.let { w -> weatherText.text = "${w.icon}  ${w.tempText}" + if (w.place.isNotBlank()) "   ${w.place}" else "" }
                delay(30 * 60_000L)
            }
        }
        scope.launch { scores() }
    }

    private suspend fun slideshow() {
        val pictures = withContext(Dispatchers.IO) {
            val snap = HomeSnapshot(filesDir)
            snap.load()?.let(snap::backdrops).orEmpty().shuffled()
        }
        if (pictures.isEmpty()) return
        var i = 0
        var showFront = false
        while (scope.isActive) {
            val (url, title) = pictures[i % pictures.size]
            val target = if (showFront) front else back
            val other = if (showFront) back else front
            val result = imageLoader.execute(ImageRequest.Builder(this).data(url).size(1280, 720).build())
            result.drawable?.let { d ->
                target.setImageDrawable(d)
                target.animate().alpha(1f).setDuration(2_000).start()
                other.animate().alpha(0f).setDuration(2_000).start()
                caption.text = title
                showFront = !showFront
            }
            i++
            delay(20_000)
        }
    }

    /** "LIVE  Arsenal 2–1 Chelsea" during a game, else the next game. Every 2 minutes. */
    private suspend fun scores() {
        val espn = EspnClient()
        val zone = ZoneId.systemDefault()
        while (scope.isActive) {
            val lines = Teams.all.mapNotNull { team ->
                runCatching {
                    val today = espn.todaysGame(team)
                    if (today != null && today.state == GameState.LIVE) {
                        val us = if (today.isHome(team.espnId)) today.home else today.away
                        val them = today.opponentOf(team.espnId)
                        "LIVE  ${team.name} ${us.score ?: "0"}–${them.score ?: "0"} ${them.name}"
                    } else {
                        val next = GameDay.next(espn.schedule(team), System.currentTimeMillis()) ?: return@runCatching null
                        val them = next.opponentOf(team.espnId)
                        "${team.name} ${if (next.isHome(team.espnId)) "vs" else "at"} ${them.name} · " +
                            GameDay.whenText(next.startMillis, System.currentTimeMillis(), zone)
                    }
                }.getOrNull()
            }
            sports.text = lines.joinToString("\n")
            delay(2 * 60_000L)
        }
    }

    override fun onDreamingStopped() {
        scope.cancel()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
