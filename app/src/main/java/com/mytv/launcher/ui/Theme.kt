@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.lightColorScheme

object Palette {
    val Sky = Color(0xFFCFE6F7)
    val Ink = Color(0xFF1C3550)
    val InkSoft = Color(0xFF4F6A85)
    val Card = Color(0xFFFFFFFF)
    val CardSoft = Color(0x99FFFFFF)
    val Live = Color(0xFFE5383B)
    val Highlight = Color(0xFFE3F1FB)
}

@Composable
fun MyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Ink,
            onPrimary = Color.White,
            background = Palette.Sky,
            onBackground = Palette.Ink,
            surface = Palette.Card,
            onSurface = Palette.Ink,
        ),
        content = content,
    )
}
