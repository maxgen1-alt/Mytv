@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.mytv.launcher.LinkState
import com.mytv.launcher.apps.TvApp

/** Pick which apps appear in the top row. Click toggles shown / hidden. */
@Composable
fun EditAppsScreen(
    apps: List<TvApp>,
    hidden: Set<String>,
    version: String,
    updateStatus: String,
    onCheckUpdates: () -> Unit,
    watchStatus: String,
    needsWatchAccess: Boolean,
    onAllowWatchAccess: () -> Unit,
    trackStatus: String,
    tracking: Boolean,
    onTurnOnTracking: () -> Unit,
    syncStatus: String,
    onToggle: (TvApp, Boolean) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Palette.Background).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Your apps", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("MyTV $version", fontSize = 12.sp, color = Palette.InkSoft)
                Text(syncStatus, fontSize = 12.sp, color = Palette.InkSoft)
                if (updateStatus.isNotBlank()) Text(updateStatus, fontSize = 12.sp, color = Palette.Ink)
            }
            Spacer(Modifier.width(14.dp))
            Button(onClick = onCheckUpdates) { Text("Check for updates") }
        }
        Text(
            "Select an app to show or hide it on the top row. Long-press an app on the home screen to move it.",
            fontSize = 14.sp,
            color = Palette.InkSoft,
        )
        if (watchStatus.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(watchStatus, fontSize = 14.sp, color = Palette.Ink)
                if (needsWatchAccess) {
                    Spacer(Modifier.width(14.dp))
                    Button(onClick = onAllowWatchAccess) { Text("Allow access") }
                }
            }
        }
        if (trackStatus.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trackStatus, fontSize = 14.sp, color = Palette.Ink, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (!tracking) {
                    Spacer(Modifier.width(14.dp))
                    Button(onClick = onTurnOnTracking) { Text("Turn on") }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                val isHidden = app.packageName in hidden
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusCard(
                        onClick = { onToggle(app, !isHidden) },
                        modifier = Modifier
                            .size(150.dp, 84.dp)
                            .let { if (app == apps.first()) it.focusRequester(first) else it },
                    ) {
                        val bmp = app.banner ?: app.icon
                        Box(Modifier.fillMaxSize().alpha(if (isHidden) 0.3f else 1f), contentAlignment = Alignment.Center) {
                            if (bmp != null) {
                                Image(
                                    bmp,
                                    app.label,
                                    Modifier.fillMaxSize(),
                                    contentScale = if (app.banner != null) ContentScale.Crop else ContentScale.Inside,
                                )
                            } else {
                                Text(app.label, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (if (isHidden) "Hidden · " else "") + app.label,
                        fontSize = 12.sp,
                        color = if (isHidden) Palette.InkSoft else Palette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Link a Trakt account by typing a short code on your phone. */
@Composable
fun TraktLinkScreen(state: LinkState, linked: Boolean, onUnlink: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .background(Palette.Card, RoundedCornerShape(24.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Continue Watching", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Spacer(Modifier.height(12.dp))
            when (state) {
                LinkState.Starting -> Text("Getting a code…", fontSize = 16.sp, color = Palette.InkSoft)
                is LinkState.ShowCode -> {
                    Text("On your phone, go to", fontSize = 16.sp, color = Palette.InkSoft)
                    Text(state.code.verificationUrl, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    Spacer(Modifier.height(12.dp))
                    Text("and enter", fontSize = 16.sp, color = Palette.InkSoft)
                    Text(
                        state.code.userCode,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.Ink,
                        letterSpacing = 6.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This screen closes by itself once you approve.",
                        fontSize = 13.sp,
                        color = Palette.InkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
                LinkState.Linked -> Text("Connected!", fontSize = 22.sp, color = Palette.Ink)
                is LinkState.Failed -> Text(state.message, fontSize = 16.sp, color = Palette.InkSoft, textAlign = TextAlign.Center)
            }
            if (linked) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onUnlink, modifier = Modifier.focusRequester(focus)) { Text("Disconnect Trakt") }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }
}
