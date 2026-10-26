@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mytv.launcher.WatchState
import com.mytv.launcher.apps.TvApp
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.Team
import com.mytv.launcher.sports.Teams
import com.mytv.launcher.topten.TopTenService
import com.mytv.launcher.topten.TopTitle
import com.mytv.launcher.watching.WatchItem
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val RowPadding = PaddingValues(horizontal = 48.dp)
private val TileWidth = 176.dp
private val TileHeight = 99.dp

@Composable
fun HomeScreen(
    apps: List<TvApp>,
    liveGames: Map<String, Game>,
    watching: WatchState,
    busy: String?,
    updateReady: Boolean,
    topTen: Map<TopTenService, List<TopTitle>>,
    popular: List<Pair<TopTenService, TopTitle>>,
    onPopular: (TopTenService, TopTitle) -> Unit,
    topTenConfigured: Boolean,
    onUpdate: () -> Unit,
    onApp: (TvApp) -> Unit,
    onMoveApp: (TvApp, Int) -> Unit,
    onHideApp: (TvApp) -> Unit,
    onEditApps: () -> Unit,
    onTeam: (Team) -> Unit,
    onTeamPage: (Team) -> Unit,
    onAng: () -> Unit,
    onWatchItem: (WatchItem) -> Unit,
    onAllowWatchNext: () -> Unit,
    onLinkTrakt: () -> Unit,
) {
    val firstApp = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<TvApp?>(null) }
    val scroll = rememberScrollState()
    var focusedApp by remember { mutableStateOf<TvApp?>(null) }
    var appFocusTick by remember { mutableIntStateOf(0) }

    // Landing on the apps row always brings the Top 10 back into view.
    LaunchedEffect(appFocusTick) { if (appFocusTick > 0) scroll.animateScrollTo(0) }

    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) runCatching { firstApp.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(vertical = 28.dp),
        ) {
            TopBar(updateReady, onUpdate)
            Spacer(Modifier.height(8.dp))

            // Above the apps: Top 10 for whichever app is focused (not focusable, just visual).
            val focusedService = focusedApp?.let { TopTenService.forApp(it.packageName, it.label) }
            TopTenHero(
                service = focusedService,
                titles = focusedService?.let { topTen[it] }.orEmpty(),
                configured = topTenConfigured,
            )

            // Row 1: streaming apps
            LazyRow(
                contentPadding = RowPadding,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.height(TileHeight + 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppTile(
                        app = app,
                        modifier = (if (app == apps.first()) Modifier.focusRequester(firstApp) else Modifier)
                            .onFocusChanged {
                                if (it.hasFocus || it.isFocused) {
                                    focusedApp = app
                                    appFocusTick++
                                }
                            },
                        onClick = { onApp(app) },
                        onLongClick = { menuFor = app },
                    )
                }
                item(key = "__edit") { EditTile(onEditApps) }
            }

            Spacer(Modifier.height(14.dp))

            // Row 2: team buttons
            LazyRow(
                contentPadding = RowPadding,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.height(TileHeight + 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(Teams.all, key = { it.key }) { team ->
                    TeamTile(
                        team = team,
                        live = liveGames[team.key],
                        onClick = { onTeam(team) },
                        onLongClick = { onTeamPage(team) },
                    )
                }
                item(key = "__ang") { AngTile(onAng) }
            }

            Spacer(Modifier.height(18.dp))

            // Row 3: continue watching
            SectionTitle("Continue Watching", Modifier.padding(start = 48.dp))
            ContinueWatchingRow(watching, onWatchItem, onAllowWatchNext, onLinkTrakt)

            // Row 4: what's hot across the apps you have
            if (popular.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionTitle("Popular on Your Apps", Modifier.padding(start = 48.dp))
                LazyRow(
                    contentPadding = RowPadding,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(250.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    items(popular, key = { it.second.key }) { (service, title) ->
                        PopularCard(service, title, onClick = { onPopular(service, title) })
                    }
                }
            }
        }

        if (busy != null) BusyOverlay(busy)
    }

    menuFor?.let { app ->
        AppMenu(
            app = app,
            onDismiss = { menuFor = null },
            onMove = { delta -> onMoveApp(app, delta) },
            onHide = { onHideApp(app); menuFor = null },
        )
    }
}

@Composable
private fun TopBar(updateReady: Boolean, onUpdate: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(15_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        if (updateReady) {
            FocusCard(onClick = onUpdate, corner = 18.dp, container = Palette.Ink, focusedScale = 1.06f, modifier = Modifier.height(36.dp)) {
                Text(
                    "Update ready",
                    color = Palette.Base,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
        }
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(now),
            color = Palette.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(16.dp))
        FocusCard(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            corner = 18.dp,
            container = Palette.CardSoft,
            focusedScale = 1.12f,
            modifier = Modifier.size(36.dp),
        ) {
            Text("⚙", fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun AppTile(app: TvApp, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(TileWidth, TileHeight),
    ) {
        val banner = remember(app.packageName) { runCatching { app.banner?.toBitmap()?.asImageBitmap() }.getOrNull() }
        val icon = remember(app.packageName) {
            if (banner == null) runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() else null
        }
        when {
            banner != null -> Image(banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Column(
                Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                icon?.let { Image(it, null, Modifier.size(44.dp)) }
                Spacer(Modifier.height(6.dp))
                Text(app.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EditTile(onClick: () -> Unit) {
    FocusCard(onClick = onClick, container = Palette.CardSoft, modifier = Modifier.size(TileHeight, TileHeight)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("+", fontSize = 30.sp, color = Palette.Ink)
            Text("Apps", fontSize = 12.sp, color = Palette.InkSoft)
        }
    }
}

@Composable
private fun TeamTile(team: Team, live: Game?, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.size(TileWidth, TileHeight)) {
        AsyncImage(
            model = team.logoUrl,
            contentDescription = team.name,
            modifier = Modifier.align(Alignment.Center).size(68.dp),
            contentScale = ContentScale.Fit,
        )
        if (live != null) {
            LiveBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
            val us = if (live.isHome(team.espnId)) live.home else live.away
            val them = live.opponentOf(team.espnId)
            Text(
                "${us.score ?: "0"}–${them.score ?: "0"}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Ink,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

@Composable
private fun AngTile(onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.size(TileWidth, TileHeight)) {
        Box(Modifier.fillMaxSize().background(AngPalette.Tile), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ang", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("♥", fontSize = 24.sp, color = AngPalette.Yellow)
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    state: WatchState,
    onItem: (WatchItem) -> Unit,
    onAllow: () -> Unit,
    onLink: () -> Unit,
) {
    if (state.items.isEmpty()) {
        when {
            state.loading -> Hint("Loading…")
            !state.needsPermission -> Hint("Nothing in progress right now. Start something in any app and it shows up here.")
        }
    }
    LazyRow(
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.height(190.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(state.items, key = { it.key }) { item -> WatchCard(item, onClick = { onItem(item) }) }
        if (state.needsPermission) {
            item(key = "__allow") {
                SetupTile("Turn on Continue Watching", "Show what you're in the middle of in every app.", onAllow)
            }
        }
        if (state.traktConfigured) {
            item(key = "__trakt") {
                if (state.traktLinked) TraktTile(onLink)
                else SetupTile("Connect Trakt", "Optional: add history from other devices.", onLink)
            }
        }
    }
}

@Composable
private fun SetupTile(title: String, body: String, onClick: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        FocusCard(onClick = onClick, container = Palette.CardSoft, modifier = Modifier.size(260.dp, 130.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = 12.sp, color = Palette.InkSoft)
            }
        }
    }
}

@Composable
private fun WatchCard(item: WatchItem, onClick: () -> Unit) {
    Column(Modifier.width(232.dp).padding(top = 8.dp)) {
        FocusCard(onClick = onClick, modifier = Modifier.size(232.dp, 130.dp)) {
            if (item.imageUrl != null) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    item.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            }
            item.progress?.let { p ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(10.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.5f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.02f, 1f)).background(Color.White))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.Ink)
        Text(item.subtitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.InkSoft)
    }
}

@Composable
private fun TraktTile(onClick: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        FocusCard(onClick = onClick, container = Palette.CardSoft, modifier = Modifier.size(TileHeight, 130.dp)) {
            Text("Trakt", fontSize = 13.sp, color = Palette.InkSoft, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PopularCard(service: TopTenService, title: TopTitle, onClick: () -> Unit) {
    Column(Modifier.width(126.dp).padding(top = 8.dp)) {
        FocusCard(onClick = onClick, modifier = Modifier.size(126.dp, 189.dp)) {
            AsyncImage(
                model = title.posterUrl,
                contentDescription = title.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(title.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.Ink)
        Text(service.displayName, fontSize = 11.sp, maxLines = 1, color = Palette.InkSoft)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.InkSoft, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp, top = 4.dp))
}

@Composable
private fun BusyOverlay(message: String) {
    Box(Modifier.fillMaxSize().background(Palette.Base.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}

@Composable
private fun AppMenu(app: TvApp, onDismiss: () -> Unit, onMove: (Int) -> Unit, onHide: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(Palette.Card, RoundedCornerShape(18.dp))
                .padding(24.dp)
                .width(280.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(app.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            Button(onClick = { onMove(-1) }, modifier = Modifier.fillMaxWidth().focusRequester(first)) { Text("Move left") }
            Button(onClick = { onMove(1) }, modifier = Modifier.fillMaxWidth()) { Text("Move right") }
            Button(onClick = onHide, modifier = Modifier.fillMaxWidth()) { Text("Hide from top row") }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
