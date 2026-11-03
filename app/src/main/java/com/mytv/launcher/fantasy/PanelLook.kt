package com.mytv.launcher.fantasy

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import com.mytv.launcher.system.MyTvSettings

/** The slide-in panels' background: the classic dark one, or the Nothing look's black with a team-colour strip on top. */
internal object PanelLook {
    const val FANTASY = 0xFF7C3AED.toInt()
    const val FOOTBALL = 0xFF2563EB.toInt()

    fun background(context: Context, accent: Int): Drawable {
        val s = MyTvSettings(context)
        if (!(s.nothingTheme && s.profile == "me")) return ColorDrawable(0xF50E0E12.toInt())
        val strip = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, context.resources.displayMetrics).toInt()
        return LayerDrawable(arrayOf(ColorDrawable(0xF5000000.toInt()), ColorDrawable(accent))).apply {
            setLayerHeight(1, strip)
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
        }
    }
}
