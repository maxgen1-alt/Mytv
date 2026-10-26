@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.onPreviewKeyEvent
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

/** The one card style used everywhere: rounded, grows and gets a white border when focused. */
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
    // Holding select must ONLY long-press: the built-in handling also fires a
    // click when the button comes back up, which opened the app. So with a
    // long-press action we read the select key ourselves: a quick press
    // clicks, holding it (Android starts repeating the key) long-presses.
    val holdHandled = remember { booleanArrayOf(false) }
    val keys = if (onLongClick == null) modifier else modifier.onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER &&
            k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) return@onPreviewKeyEvent false
        when (k.action) {
            KeyEvent.ACTION_DOWN -> {
                if (k.repeatCount == 0) holdHandled[0] = false
                else if (!holdHandled[0]) { holdHandled[0] = true; onLongClick() }
            }
            KeyEvent.ACTION_UP -> {
                if (!holdHandled[0]) onClick()
                holdHandled[0] = false
            }
        }
        true
    }
    Surface(
        onClick = onClick,
        modifier = keys,
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

/**
 * For menus opened by holding select: the button is still held when the menu
 * appears, and letting go would "click" whatever is focused. Ignore select
 * until it has been released and pressed again.
 */
@Composable
fun Modifier.ignoreHeldSelect(): Modifier {
    val armed = remember { booleanArrayOf(false) }
    return onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (armed[0] || (k.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && k.keyCode != KeyEvent.KEYCODE_ENTER &&
                k.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) return@onPreviewKeyEvent false
        if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
            armed[0] = true
            false
        } else {
            true // still the held press (repeats / its release): swallow it
        }
    }
}
