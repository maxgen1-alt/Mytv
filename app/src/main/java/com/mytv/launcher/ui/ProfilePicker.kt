@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.mytv.launcher.LauncherViewModel.Profile

/** "Who's watching?": you, Ang (her TV becomes Home), or a guest (just the apps). */
@Composable
fun ProfilePicker(current: Profile, onPick: (Profile) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF0050506)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Who's watching?", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Spacer(Modifier.height(32.dp))
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Profile.entries.forEach { p ->
                    val (bg, mark) = when (p) {
                        Profile.ME -> Brush.linearGradient(listOf(Color(0xFF2B2D35), Color(0xFF4A4D55))) to "▶"
                        Profile.ANG -> AngPalette.Tile to "♥"
                        Profile.GUEST -> Brush.linearGradient(listOf(Color(0xFF1B1C20), Color(0xFF2B2D35))) to "☺"
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FocusCard(
                            onClick = { onPick(p) },
                            corner = 28.dp,
                            focusedScale = 1.1f,
                            modifier = Modifier.size(170.dp).ignoreHeldSelect().let { if (p == current) it.focusRequester(focus) else it },
                        ) {
                            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
                                Text(mark, fontSize = 54.sp, color = Color.White)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(p.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    }
                }
            }
        }
    }
}
