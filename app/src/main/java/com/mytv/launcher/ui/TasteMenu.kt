@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.mytv.launcher.ang.Rating

/**
 * Hold select on a title: Like / Already watched / Dislike / Won't watch.
 * Used by Ang's TV (her colours) and your rows (the home colours).
 */
@Composable
fun TasteMenu(
    title: String,
    current: Rating?,
    container: Color,
    accent: Color,
    onPick: (Rating?) -> Unit,
    onDismiss: () -> Unit,
    likeHint: String = "More like this, and new seasons",
    watchedHint: String = "Hide it",
) {
    val first = remember { FocusRequester() }
    val plain = Color.White.copy(alpha = 0.14f)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .ignoreHeldSelect()
                .width(320.dp)
                .background(container, RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
            current?.let { Text("Currently: ${it.label}", fontSize = 12.sp, color = accent) }
            Spacer(Modifier.height(4.dp))
            MenuButton("♥  Like it", likeHint, accent, Modifier.focusRequester(first)) { onPick(Rating.LIKE) }
            MenuButton("✓  Already watched", watchedHint, plain) { onPick(Rating.WATCHED) }
            MenuButton("✕  Dislike", "Hide it, and less like this", plain) { onPick(Rating.DISLIKE) }
            MenuButton("Won't watch", "Just hide it", plain) { onPick(Rating.WONT_WATCH) }
            if (current != null) MenuButton("Clear", "Forget what I said", Color.Transparent) { onPick(null) }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun MenuButton(title: String, hint: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, container = color, corner = 12.dp, focusedScale = 1.04f, modifier = modifier.fillMaxWidth().height(52.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(hint, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
        }
    }
}
