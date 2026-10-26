@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Palette {
    /** Solid version of the background (its darkest corner), for fades and overlays. */
    val Base = Color(0xFF050506)

    /** Black in the top-left fading to soft graphite in the bottom-right. */
    val Background = Brush.linearGradient(
        0f to Base,
        0.45f to Color(0xFF16171B),
        1f to Color(0xFF4A4D55),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    val Ink = Color(0xFFF5F5F7)
    val InkSoft = Color(0xFFA1A1AA)
    val Card = Color(0xFF232429)
    val CardSoft = Color(0x1FFFFFFF)
    val Live = Color(0xFFE5383B)
}

@Composable
fun MyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Ink,
            onPrimary = Palette.Base,
            background = Palette.Base,
            onBackground = Palette.Ink,
            surface = Palette.Card,
            onSurface = Palette.Ink,
            inverseSurface = Palette.Ink,
            inverseOnSurface = Palette.Base,
        ),
        content = content,
    )
}
