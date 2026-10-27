package com.mytv.launcher.alerts

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small banner in the top-right corner, over whatever is playing
 * (needs "display over other apps", granted during MyTV's setup).
 * With [onSelect], it takes the remote for a few seconds: OK runs it,
 * any other button closes it.
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, title: String, subtitle: String, accent: Int, seconds: Int = 8, onSelect: (() -> Unit)? = null) {
        if (!canShow(context)) return
        main.post { showNow(context.applicationContext, title, subtitle, accent, seconds, onSelect) }
    }

    private fun showNow(context: Context, title: String, subtitle: String, accent: Int, seconds: Int, onSelect: (() -> Unit)?) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        dismiss(wm)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xEE141418.toInt())
                setStroke(dp(3), accent)
            }
            addView(TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(0xFFC8C8CE.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        val focusable = onSelect != null
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(40)
            y = dp(32)
        }
        if (focusable) {
            box.isFocusable = true
            box.isFocusableInTouchMode = true
            box.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener true
                dismiss(wm)
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) onSelect?.invoke()
                true
            }
        }
        runCatching { wm.addView(box, params) }.onSuccess {
            current = box
            if (focusable) box.requestFocus()
            main.postDelayed({ if (current === box) dismiss(wm) }, seconds * 1000L)
        }
    }

    private fun dismiss(wm: WindowManager) {
        current?.let { runCatching { wm.removeView(it) } }
        current = null
    }
}
