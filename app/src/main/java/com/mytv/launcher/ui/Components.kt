@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** The one card style used everywhere: white, rounded, grows and glows when focused. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    corner: Dp = 14.dp,
    container: Color = Palette.Card,
    focusedScale: Float = 1.08f,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Palette.Ink,
            focusedContainerColor = container,
            focusedContentColor = Palette.Ink,
            pressedContainerColor = container,
            pressedContentColor = Palette.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
        ),
        // No glow: blurred shadows are expensive on a Chromecast's GPU. Scale + border is enough.
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 10.dp),
        color = Palette.Ink,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Palette.Live, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
