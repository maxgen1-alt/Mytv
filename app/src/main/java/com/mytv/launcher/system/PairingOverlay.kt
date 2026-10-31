package com.mytv.launcher.system

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wireless debugging's pairing code is only good while Settings' pairing
 * screen stays open, and on a TV going back to MyTV closes it. So MyTV floats
 * a small keypad over Settings instead: it waits in the corner until the
 * pairing screen opens, then takes the remote for the 6 digits, pairs, and
 * makes MyTV the home screen, all without leaving Settings.
 */
object PairingOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var box: LinearLayout? = null
    private var stopWatching: () -> Unit = {}
    private var port: Int? = null
    private var code = ""
    private var busy = false

    sealed interface Start {
        data object Shown : Start
        /** MyTV may not draw over Settings yet: its permission page was opened (or doesn't exist). */
        data class NeedsPermission(val pageOpened: Boolean) : Start
    }

    fun start(context: Context): Start {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            val page = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return Start.NeedsPermission(runCatching { app.startActivity(page) }.isSuccess)
        }
        dismiss()
        code = ""
        port = null
        busy = false
        show(app, focusable = false)
        status("MyTV is waiting: open Wireless debugging → Pair device with pairing code.")
        stopWatching = WirelessAdb.watchPairing(app) { p ->
            scope.launch {
                if (box == null) return@launch
                val first = port == null
                port = p
                if (first) {
                    // The pairing screen is open: now the keypad takes the remote.
                    show(app, focusable = true)
                    status("Type the 6-digit code shown on the pairing screen.")
                }
            }
        }
        // Developer options, where Wireless debugging is.
        runCatching {
            app.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // Give up after 10 minutes.
        scope.launch { delay(10 * 60_000L); if (port == null) dismiss() }
        return Start.Shown
    }

    private fun show(context: Context, focusable: Boolean) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        box?.let { runCatching { wm.removeView(it) } }
        val panel = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) dismiss()
                    return true
                }
                // Number buttons on the remote work too.
                if (event.action == KeyEvent.ACTION_UP && event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                    type(context, ('0' + (event.keyCode - KeyEvent.KEYCODE_0)).toString())
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18).toFloat()
                setColor(0xF0141418.toInt())
                setStroke(dp(context, 2), 0xFFF5C518.toInt())
            }
        }
        panel.addView(TextView(context).apply {
            text = "MyTV pairing"
            setTextColor(0xFFF5C518.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(context).apply { tag = "status"; setTextColor(0xFFF5F5F7.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
        if (focusable) {
            panel.addView(TextView(context).apply {
                tag = "code"
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 8), 0, dp(context, 8))
            })
            val grid = GridLayout(context).apply { columnCount = 3 }
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓").forEach { key ->
                grid.addView(key(context, key))
            }
            panel.addView(grid)
        }
        val params = WindowManager.LayoutParams(
            dp(context, 300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Out of the way of Settings' own pairing screen (code and port stay readable).
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(context, 32)
            y = dp(context, 32)
        }
        if (runCatching { wm.addView(panel, params) }.isFailure) return
        box = panel
        refreshCode()
        if (focusable) panel.findViewWithTag<View>("5")?.requestFocus()
    }

    private fun key(context: Context, label: String) = TextView(context).apply {
        text = label
        tag = label
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = true
        val fill = if (label == "✓") 0xFF1F7A3D.toInt() else 0xFF2A2A33.toInt()
        val normal = GradientDrawable().apply { cornerRadius = dp(context, 12).toFloat(); setColor(fill) }
        val selected = GradientDrawable().apply { cornerRadius = dp(context, 12).toFloat(); setColor(fill); setStroke(dp(context, 3), 0xFFFFFFFF.toInt()) }
        background = normal
        setOnFocusChangeListener { v, has -> v.background = if (has) selected else normal }
        layoutParams = GridLayout.LayoutParams().apply {
            width = dp(context, 76)
            height = dp(context, 48)
            setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        }
        setOnClickListener { type(context, label) }
    }

    private fun type(context: Context, key: String) {
        if (busy) return
        when (key) {
            "⌫" -> code = code.dropLast(1)
            "✓" -> { pair(context); return }
            else -> if (code.length < 6) code += key
        }
        refreshCode()
        if (code.length == 6) box?.findViewWithTag<View>("✓")?.requestFocus()
    }

    private fun pair(context: Context) {
        val p = port ?: return status("Open Pair device with pairing code first.")
        if (code.length != 6) return status("The code has 6 digits.")
        busy = true
        status("Pairing…")
        scope.launch {
            val error = WirelessAdb.pair(context, code, p)
            if (error != null) {
                busy = false
                code = ""
                refreshCode()
                status(error)
                return@launch
            }
            status("Paired. Setting MyTV up as your home screen…")
            val done = runCatching { HomeSetup(context).takeOver() }.getOrNull()
            status(
                when (done) {
                    is HomeSetup.Result.Done -> done.message
                    is HomeSetup.Result.NoAccess -> done.message
                    null -> "Paired, but setup didn't finish: open MyTV settings and choose Make MyTV my home screen."
                },
            )
            delay(6_000L)
            dismiss()
        }
    }

    private fun refreshCode() {
        box?.findViewWithTag<TextView>("code")?.text = (code + "______".drop(code.length)).toList().joinToString(" ")
    }

    private fun status(text: String) {
        box?.findViewWithTag<TextView>("status")?.text = text
    }

    fun dismiss() {
        stopWatching()
        stopWatching = {}
        val panel = box ?: return
        box = null
        runCatching { panel.context.getSystemService(WindowManager::class.java)?.removeView(panel) }
    }

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
