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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mytv.launcher.MANUAL_PREFIX
import com.mytv.launcher.WatchState
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.Rating
import com.mytv.launcher.apps.TvApp
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameDay
import com.mytv.launcher.sports.TeamInfo
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
    teams: Map<String, TeamInfo>,
    watching: WatchState,
    busy: String?,
    updateReady: Boolean,
    popular: List<AngItem>,
    newForYou: List<AngItem>,
    missedForYou: List<AngItem>,
    newMovies: List<AngItem>,
    missedMovies: List<AngItem>,
    watchLater: List<AngItem>,
    myRatings: Map<String, Rating>,
    /** Continue Watching key → the "Watching now" title it came from. */
    watchingNow: Map<String, AngItem>,
    onOpenTitle: (AngItem) -> Unit,
    onRateTitle: (AngItem, Rating?) -> Unit,
    onWatchlist: () -> Unit,
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
    onTurnOnTracking: () -> Unit,
    onLinkTrakt: () -> Unit,
    tonight: List<AngItem> = emptyList(),
    /** Guest profile: just the apps and the teams, nothing personal. */
    guest: Boolean = false,
    profileLabel: String = "",
    onProfile: () -> Unit = {},
    weather: com.mytv.launcher.weather.Weather.Now? = null,
    onSystemSettings: () -> Unit = {},
    onSleepTimer: (Int) -> Unit = {},
) {
    val firstApp = remember { FocusRequester() }
    // The title in focus, for the preview at the top (after a short pause, so moving fast stays smooth).
    var focusedTitle by remember { mutableStateOf<AngItem?>(null) }
    var preview by remember { mutableStateOf<AngItem?>(null) }
    LaunchedEffect(focusedTitle) {
        if (focusedTitle == null) { preview = null; return@LaunchedEffect }
        delay(350)
        preview = focusedTitle
    }
    var sleepMenu by remember { mutableStateOf(false) }
    val onTitleFocus: (AngItem?) -> Unit = { focusedTitle = it }
    var menuFor by remember { mutableStateOf<TvApp?>(null) }
    var rateFor by remember { mutableStateOf<AngItem?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) runCatching { firstApp.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(LocalBackground.current)) {
        PreviewBackdrop(preview)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(vertical = 28.dp),
        ) {
            TopBar(updateReady, onUpdate, profileLabel, onProfile, weather)
            Spacer(Modifier.height(18.dp))

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
                        modifier = if (app == apps.first()) Modifier.focusRequester(firstApp) else Modifier,
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
                        info = teams[team.key],
                        onClick = { onTeam(team) },
                        onLongClick = { onTeamPage(team) },
                    )
                }
                if (!guest) {
                    item(key = "__ang") { AngTile(onAng) }
                    item(key = "__watchlist") { WatchlistTile(onWatchlist) }
                }
            }

            if (guest) {
                Spacer(Modifier.height(18.dp))
                QuickSettingsRow(onSystemSettings) { sleepMenu = true }
                return@Column
            }

            Spacer(Modifier.height(18.dp))

            // Row 3: continue watching
            SectionTitle("Continue Watching", Modifier.padding(start = 48.dp))
            ContinueWatchingRow(watching, onWatchItem, onAllowWatchNext, onTurnOnTracking, onLinkTrakt) { wi -> watchingNow[wi.key]?.let { rateFor = it } }
            TitleRow("Tonight", tonight, onOpenTitle, onTitleFocus) { rateFor = it }
            // Watch Later, shows and movies apart (tidier with a long list).
            val (laterMovies, laterShows) = remember(watchLater) { watchLater.partition { it.isMovie } }
            TitleRow("Watch Later · Shows", laterShows, onOpenTitle, onTitleFocus) { rateFor = it }
            TitleRow("Watch Later · Movies", laterMovies, onOpenTitle, onTitleFocus) { rateFor = it }

            // What's hot across the apps you have, then picks from what you watched and rated.
            TitleRow("Popular on Your Apps", popular, onOpenTitle, onTitleFocus) { rateFor = it }
            TitleRow("New Shows for You", newForYou, onOpenTitle, onTitleFocus) { rateFor = it }
            TitleRow("Shows You Might Have Missed", missedForYou, onOpenTitle, onTitleFocus) { rateFor = it }
            TitleRow("New Movies for You", newMovies, onOpenTitle, onTitleFocus) { rateFor = it }
            TitleRow("Movies You Might Have Missed", missedMovies, onOpenTitle, onTitleFocus) { rateFor = it }

            Spacer(Modifier.height(18.dp))
            QuickSettingsRow(onSystemSettings) { sleepMenu = true }
        }

        PreviewInfo(preview, Modifier.align(Alignment.TopEnd))

        if (busy != null) BusyOverlay(busy)
    }

    rateFor?.let { item ->
        TasteMenu(
            title = item.title,
            current = myRatings[item.key],
            container = Palette.Card,
            accent = Palette.Live,
            onPick = { r -> onRateTitle(item, r); rateFor = null },
            onDismiss = { rateFor = null },
            likeHint = "More like this in Shows for You",
            watchedHint = "Hide it, and use it for recommendations",
        )
    }

    if (sleepMenu) {
        SleepMenu(onPick = { m -> onSleepTimer(m); sleepMenu = false }, onDismiss = { sleepMenu = false })
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
private fun TopBar(
    updateReady: Boolean,
    onUpdate: () -> Unit,
    profileLabel: String,
    onProfile: () -> Unit,
    weather: com.mytv.launcher.weather.Weather.Now?,
) {
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
        if (profileLabel.isNotBlank()) {
            FocusCard(onClick = onProfile, corner = 18.dp, container = Palette.CardSoft, focusedScale = 1.06f, modifier = Modifier.height(36.dp)) {
                Text(
                    "👤 $profileLabel",
                    color = Palette.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
        }
        weather?.let { w ->
            Text("${w.icon} ${w.tempText}", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (w.place.isNotBlank()) Text("  ${w.place}", color = Palette.InkSoft, fontSize = 14.sp)
        }
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
        val banner = app.banner
        val icon = app.icon
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
private fun TeamTile(team: Team, info: TeamInfo?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val live = info?.live
    var focused by remember { mutableStateOf(false) }
    val pulseColor = if (team.key == Teams.lakers.key) Palette.LakersGold else Palette.ArsenalRed
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .size(TileWidth, TileHeight)
            .onFocusChanged { focused = it.hasFocus }
            .then(if (live != null) Modifier.livePulse(pulseColor, scale = if (focused) 1.08f else 1f) else Modifier),
    ) {
        AsyncImage(
            model = team.logoUrl,
            contentDescription = team.name,
            modifier = Modifier.align(Alignment.Center).size(if (focused && live == null && info?.next != null) 52.dp else 68.dp),
            contentScale = ContentScale.Fit,
        )
        // Hovering shows the next game, so you don't have to open the team page.
        val next = info?.next
        if (focused && live == null && next != null) {
            val them = next.opponentOf(team.espnId)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    remember(next.startMillis) { GameDay.whenText(next.startMillis, System.currentTimeMillis(), java.time.ZoneId.systemDefault()) },
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1,
                )
                Text(
                    (if (next.isHome(team.espnId)) "vs " else "at ") + them.name,
                    fontSize = 11.sp, color = Palette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    onTrack: () -> Unit,
    onLink: () -> Unit,
    onHold: (WatchItem) -> Unit,
) {
    // The hint sits inside the row (same height), so nothing below jumps when shows arrive.
    val hint = if (state.items.isNotEmpty()) null else when {
        state.loading -> "Loading…"
        !state.needsPermission && state.tracking -> "Nothing in progress right now. Start something in any app and it shows up here."
        else -> null
    }
    LazyRow(
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.height(190.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (hint != null) {
            item(key = "__hint") {
                Box(Modifier.height(130.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(hint, color = Palette.InkSoft, fontSize = 14.sp)
                }
            }
        }
        items(state.items, key = { it.key }) { item ->
            // Cards you added with "Watching now" can be changed with hold select.
            WatchCard(item, onClick = { onItem(item) }, onLongClick = if (item.key.startsWith(MANUAL_PREFIX)) ({ onHold(item) }) else null)
        }
        if (state.needsPermission) {
            item(key = "__allow") {
                SetupTile("Turn on Continue Watching", "Show what you're in the middle of in every app.", onAllow)
            }
        }
        if (!state.tracking && !state.loading) {
            item(key = "__track") {
                SetupTile("Turn on watch tracking", "MyTV notes what you play in each app, with episodes.", onTrack)
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
private fun WatchCard(item: WatchItem, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.width(232.dp).padding(top = 8.dp)) {
        FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.size(232.dp, 130.dp)) {
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

/** A row of posters you can open (click) or rate (hold select). Hidden when empty. */
@Composable
private fun TitleRow(
    title: String,
    items: List<AngItem>,
    onOpen: (AngItem) -> Unit,
    onFocus: (AngItem?) -> Unit = {},
    onHold: (AngItem) -> Unit,
) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    SectionTitle(title, Modifier.padding(start = 48.dp))
    LazyRow(
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.height(250.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items, key = { it.key }) { item ->
            TitleCard(item, onClick = { onOpen(item) }, onLongClick = { onHold(item) }, onFocused = { f -> onFocus(if (f) item else null) })
        }
    }
}

@Composable
private fun WatchlistTile(onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.size(TileWidth, TileHeight)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("★", fontSize = 26.sp, color = Color(0xFFF5C518))
            Text("My Watchlist", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        }
    }
}

/**
 * Apple TV-style preview: the focused title's wide picture fills the
 * background (dimmed, so the rows stay readable).
 */
@Composable
private fun PreviewBackdrop(item: AngItem?) {
    val url = item?.backdropUrl ?: return
    AsyncImage(
        model = coil.request.ImageRequest.Builder(LocalContext.current).data(url).size(960, 540).crossfade(300).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize().alpha(0.32f),
        contentScale = ContentScale.Crop,
    )
}

/** Title, IMDb rating, what's new and a short description of the focused title, top-right. */
@Composable
private fun PreviewInfo(item: AngItem?, modifier: Modifier) {
    item ?: return
    Column(
        modifier
            .padding(top = 20.dp, end = 40.dp)
            .width(460.dp)
            .background(Color(0xCC0B0B0E), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            RatingTag(item.ratingRef, true, Modifier, always = true)
        }
        val extra = listOfNotNull(item.badge, item.detail.takeIf { it.isNotBlank() }).joinToString(" · ")
        if (extra.isNotBlank()) Text(extra, fontSize = 13.sp, color = Color(0xFFF5C518), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.overview.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(item.overview, fontSize = 13.sp, color = Palette.InkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Wi-Fi, Bluetooth, sleep timer, the TV's settings and MyTV's: what you'd otherwise open Google's settings for. */
@Composable
private fun QuickSettingsRow(onSystemSettings: () -> Unit, onSleep: () -> Unit) {
    val context = LocalContext.current
    fun open(action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
    SectionTitle("Settings", Modifier.padding(start = 48.dp))
    LazyRow(
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.height(110.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "wifi") { QuickTile("📶", "Wi-Fi") { open(Settings.ACTION_WIFI_SETTINGS) } }
        item(key = "bt") { QuickTile("🎧", "Bluetooth") { open(Settings.ACTION_BLUETOOTH_SETTINGS) } }
        item(key = "sleep") { QuickTile("🌙", "Sleep timer", onSleep) }
        item(key = "display") { QuickTile("🔊", "Display & sound") { open("android.settings.DISPLAY_SETTINGS") } }
        item(key = "all") { QuickTile("⚙", "All settings") { open(Settings.ACTION_SETTINGS) } }
        item(key = "mytv") { QuickTile("▶", "MyTV settings", onSystemSettings) }
    }
}

@Composable
private fun QuickTile(icon: String, label: String, onClick: () -> Unit) {
    FocusCard(onClick = onClick, container = Palette.CardSoft, modifier = Modifier.size(150.dp, 84.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 22.sp)
            Text(label, fontSize = 13.sp, color = Palette.Ink, maxLines = 1)
        }
    }
}

@Composable
private fun SleepMenu(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Palette.Card, RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Sleep timer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Spacer(Modifier.height(4.dp))
            Text("The TV turns off when it's done", fontSize = 13.sp, color = Palette.InkSoft)
            Spacer(Modifier.height(16.dp))
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            listOf(30 to "30 minutes", 60 to "1 hour", 90 to "1½ hours", -1 to "End of this episode", 0 to "Off").forEachIndexed { i, (m, label) ->
                FocusCard(
                    onClick = { onPick(m) },
                    container = Palette.CardSoft,
                    focusedScale = 1.04f,
                    modifier = Modifier.padding(vertical = 4.dp).size(280.dp, 44.dp).ignoreHeldSelect()
                        .let { if (i == 0) it.focusRequester(first) else it },
                ) {
                    Text(label, fontSize = 15.sp, color = Palette.Ink, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
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
                .ignoreHeldSelect()
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
