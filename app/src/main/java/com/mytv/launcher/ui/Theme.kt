@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.mytv.launcher.sports.GameDayTheme

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

    /** Game days: dark in the top-left (where most text is), the team's colour in the bottom-right. */
    private fun gameDay(mid: Long, end: Long) = Brush.linearGradient(
        0f to Base,
        0.5f to Color(mid),
        1f to Color(end),
        start = Offset.Zero,
        end = Offset.Infinite,
    )
    val Arsenal = gameDay(0xFF3A0306, 0xFFC8102E)
    val LakersHome = gameDay(0xFF3A2A05, 0xFFE2A41F)
    val LakersAway = gameDay(0xFF24123A, 0xFF5B2A8C)

    fun backgroundFor(theme: GameDayTheme): Brush = when (theme) {
        GameDayTheme.NONE -> Background
        GameDayTheme.ARSENAL -> Arsenal
        GameDayTheme.LAKERS_HOME -> LakersHome
        GameDayTheme.LAKERS_AWAY -> LakersAway
    }

    /** Pulse colours of a team button while its game is live. */
    val ArsenalRed = Color(0xFFEF0107)
    val LakersGold = Color(0xFFFDB927)

    val Ink = Color(0xFFF5F5F7)
    val InkSoft = Color(0xFFA1A1AA)
    val Card = Color(0xFF232429)
    val CardSoft = Color(0x1FFFFFFF)
    val Live = Color(0xFFE5383B)
}

/** The app background: graphite normally, team colours on game days. */
val LocalBackground = staticCompositionLocalOf<Brush> { Palette.Background }

@Composable
fun MyTvTheme(gameDay: GameDayTheme = GameDayTheme.NONE, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackground provides Palette.backgroundFor(gameDay)) {
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
}
