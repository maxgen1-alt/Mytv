@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mytv.launcher.LauncherViewModel.SystemState

/** MyTV settings: home screen (and the one-button undo), screensaver, game alerts, updates, Send to TV. */
@Composable
fun SystemScreen(
    state: SystemState,
    sendToTvUrl: String?,
    onMakeHome: () -> Unit,
    onUndo: () -> Unit,
    onScreensaver: (Boolean) -> Unit,
    onKickoff: (Boolean) -> Unit,
    onScores: (Boolean) -> Unit,
    onChannel: (String) -> Unit,
    onBackToApps: () -> Unit,
    onTestNbc: () -> Unit = {},
    onChooseFantasyButton: () -> Unit = {},
    onShowFantasy: () -> Unit = {},
    onSignInFantrax: () -> Unit = {},
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().background(LocalBackground.current).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("MyTV settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            if (state.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.message, fontSize = 14.sp, color = Color(0xFFF5C518))
            }

            Section("Home screen", if (state.isHome) "MyTV is your home screen: it's what you see when the TV turns on and when you press Home." else
                "Make MyTV what you see when the TV turns on and when you press Home. Google TV stays installed; one button brings it back.")
            if (state.helper.isNotBlank()) {
                Text(state.helper, fontSize = 13.sp, color = if (state.helper.endsWith("running")) Color(0xFF7CD992) else Color(0xFFFF8A80))
                Spacer(Modifier.height(8.dp))
            }
            state.phoneSetup?.let { cmd ->
                Text("One-time step from your phone (Bugjaeger → Shell), then select the button below:", fontSize = 13.sp, color = Color(0xFFF5C518))
                Spacer(Modifier.height(4.dp))
                Text(
                    cmd,
                    fontSize = 13.sp,
                    color = Palette.Ink,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(10.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.isHome) Pill("Make MyTV my home screen", true, Modifier.focusRequester(first)) { if (!state.busy) onMakeHome() }
                Pill(if (state.isHome) "Undo everything (back to Google TV)" else "Undo everything", false,
                    if (state.isHome) Modifier.focusRequester(first) else Modifier) { if (!state.busy) onUndo() }
            }

            Section("Screensaver", "Google's shows your Google Photos (Ambient mode). MyTV's shows your shows, the time, weather and Arsenal / Lakers scores.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Google (Google Photos)", !state.myScreensaver) { if (!state.busy) onScreensaver(false) }
                Pill("MyTV", state.myScreensaver) { if (!state.busy) onScreensaver(true) }
            }

            Section("Games on NBC", "Open YouTube TV on NBC, the way the team buttons do when a game is on NBC.")
            Pill("Test NBC on YouTube TV", false, onClick = onTestNbc)

            Section(
                "Fantasy basketball",
                "Press your fantasy button in any app: Midland Monarchs' live matchup slides in from the right. Press it again (or Back) to slide it away. " +
                    "Pick a button you don't use, e.g. the YouTube or Netflix button (it stops opening that app).",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(if (state.fantraxSignedIn) "Fantrax: signed in" else "Sign in to Fantrax", !state.fantraxSignedIn, onClick = onSignInFantrax)
                Pill("Choose button (now: ${state.fantasyButton})", false, onClick = onChooseFantasyButton)
                Pill("Show it now", false, onClick = onShowFantasy)
            }

            Section("Game alerts", "Pop-ups over whatever you're watching. Kick-off: press OK to switch to the game.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(if (state.kickoffAlerts) "Kick-off alerts: on" else "Kick-off alerts: off", state.kickoffAlerts) { onKickoff(!state.kickoffAlerts) }
                Pill(if (state.scoreAlerts) "Goals & final scores: on" else "Spoiler-free: no scores", state.scoreAlerts) { onScores(!state.scoreAlerts) }
            }

            Section("Updates", "Stable: only versions you release, installed while the TV is off. Test: every new build.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Stable", state.channel == "stable") { onChannel("stable") }
                Pill("Test builds", state.channel == "latest") { onChannel("latest") }
                Pill("Apps & version", false, onClick = onBackToApps)
            }
        }
        Spacer(Modifier.width(32.dp))
        // Send to TV: scan with your phone to add shows to your lists from anywhere.
        if (sendToTvUrl != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(300.dp).padding(top = 48.dp)) {
                Text("Send to TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Spacer(Modifier.height(6.dp))
                Text("Scan with your phone's camera. Search any show or movie and add it to your list or Ang's.", fontSize = 13.sp, color = Palette.InkSoft)
                Spacer(Modifier.height(12.dp))
                val qr = remember(sendToTvUrl) { qrBitmap(sendToTvUrl, 512)?.asImageBitmap() }
                qr?.let { Image(it, "QR code", Modifier.size(240.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    Spacer(Modifier.height(4.dp))
    Text(body, fontSize = 13.sp, color = Palette.InkSoft)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        container = if (selected) Palette.Ink else Palette.CardSoft,
        corner = 20.dp,
        focusedScale = 1.05f,
        modifier = modifier.height(42.dp),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Palette.Base else Palette.Ink,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
        )
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()
