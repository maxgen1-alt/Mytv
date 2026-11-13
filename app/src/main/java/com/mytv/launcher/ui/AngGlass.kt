package com.mytv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ang's TV: frosted glass over a dusky backdrop with a soft hint of Love Island
 * (teal and pink glows, not wall-to-wall teal). No real blur: blurred layers are
 * too slow on a Chromecast's GPU, so the glass is translucent fills, a top sheen
 * and a thin light edge, which reads the same from the sofa.
 */
object AngPalette {
    val Teal = Color(0xFF3FD0CB)
    val DeepTeal = Color(0xFF1C5F66)
    val Pink = Color(0xFFFF6FB1)
    val Yellow = Color(0xFFFFD86B)
    val Lavender = Color(0xFFB9A6FF)

    /** Text: white on the dark glass. */
    val Ink = Color(0xFFF7F8FA)
    val InkSoft = Color(0xFFF7F8FA).copy(alpha = 0.62f)
    val InkFaint = Color(0xFFF7F8FA).copy(alpha = 0.40f)

    /** The base: deep ink with a little teal at the top, a little plum at the bottom. */
    val Base = Color(0xFF0C1418)
    val Background = Brush.linearGradient(
        0f to Color(0xFF0E1D22),
        0.55f to Color(0xFF111821),
        1f to Color(0xFF1C1424),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    /** Ang's tile on the home screen and profile picker: the same colours, softer. */
    val Tile = Brush.linearGradient(listOf(Color(0xFFE5679F), Color(0xFF4FB3B4)))

    /** Menus and dialogs: dark smoked glass (dialogs can't see what's behind them). */
    val Sheet = Color(0xF2141C22)

    // Glass
    val Glass = Color.White.copy(alpha = 0.075f)
    val GlassFocused = Color.White.copy(alpha = 0.20f)
    val GlassStrong = Color.White.copy(alpha = 0.13f)
    val Edge = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.16f)),
    )
    val Sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent))
    val Right = Color(0xFF4ADE9B)
    val Wrong = Color(0xFFFF6B7D)
}

/** A frosted glass pane: translucent fill, a sheen across the top, a thin light edge. */
fun Modifier.glass(corner: Dp = 22.dp, fill: Color = AngPalette.Glass): Modifier {
    val shape = RoundedCornerShape(corner)
    return background(fill, shape).background(AngPalette.Sheen, shape).border(1.dp, AngPalette.Edge, shape)
}

/** The backdrop: the base gradient plus soft teal / pink / gold glows (one cached draw). */
fun Modifier.angBackdrop(): Modifier = background(AngPalette.Background).drawWithCache {
    val w = size.width
    val h = size.height
    fun glow(c: Color, a: Float, x: Float, y: Float, r: Float) =
        Brush.radialGradient(listOf(c.copy(alpha = a), c.copy(alpha = 0f)), center = Offset(x, y), radius = r)
    val teal = glow(AngPalette.Teal, 0.20f, w * 0.12f, h * 0.05f, w * 0.45f)
    val pink = glow(AngPalette.Pink, 0.13f, w * 0.92f, h * 0.95f, w * 0.50f)
    val gold = glow(AngPalette.Yellow, 0.05f, w * 0.85f, h * 0.0f, w * 0.30f)
    val lav = glow(AngPalette.Lavender, 0.06f, w * 0.35f, h * 1.0f, w * 0.35f)
    onDrawBehind {
        drawRect(teal)
        drawRect(pink)
        drawRect(gold)
        drawRect(lav)
    }
}
