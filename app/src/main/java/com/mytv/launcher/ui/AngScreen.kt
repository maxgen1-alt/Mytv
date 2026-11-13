@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mytv.launcher.AngState
import com.mytv.launcher.MANUAL_PREFIX
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.Rating
import com.mytv.launcher.watching.WatchItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

@Composable
fun AngScreen(
    state: AngState,
    onOpenList: () -> Unit,
    /** Her Continue watching: the TV's list plus what she marked "Watching now". */
    continueWatching: List<WatchItem>,
    onShow: (AngItem) -> Unit,
    onRate: (AngItem, Rating?) -> Unit,
    onResume: (WatchItem) -> Unit,
    /** When her TV is the home screen (Ang's profile): her apps on top, and a way to switch profile. */
    apps: List<com.mytv.launcher.apps.TvApp>? = null,
    onApp: (com.mytv.launcher.apps.TvApp) -> Unit = {},
    onSwitchProfile: (() -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    val guide = state.guide
    var menuFor by remember { mutableStateOf<AngItem?>(null) }
    val all = remember(guide) { guide.watchNow + guide.easyWatch + guide.newShows + guide.missed + guide.movies + guide.upcoming }
    val hasContent = continueWatching.isNotEmpty() || all.isNotEmpty() || state.watchLater.isNotEmpty()
    // Focus the first card once, when the screen first has something: later updates
    // (Continue watching arriving, rows re-sorting) must not pull focus away from where you are.
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(hasContent) {
        if (hasContent && !focusedOnce) {
            kotlinx.coroutines.yield()
            focusedOnce = runCatching { first.requestFocus() }.isSuccess
        }
    }

    // Her rows, in order. The first one with something in it gets the first selection.
    // Remembered, with lambdas that never change: an update to one part of her TV (Continue
    // watching arriving, a rating) must not rebuild every row on screen.
    val shelves: List<Triple<String, List<AngItem>, (AngItem) -> String?>> = remember(state.tonight, state.watchLater, guide) {
        listOf(
            Triple("Tonight", state.tonight, BadgeLabel),
            Triple("Watch later", state.watchLater, NoLabel),
            Triple("Watch now", guide.watchNow, BadgeLabel),
            Triple("Easy Watch", guide.easyWatch, BadgeLabel),
            Triple("New shows you might like", guide.newShows, BadgeLabel),
            Triple("Shows you might have missed", guide.missed, BadgeLabel),
            Triple("Movies for you", guide.movies, BadgeLabel),
            Triple("Coming soon", guide.upcoming, DateLabel),
        )
    }
    val firstShelf = if (continueWatching.isNotEmpty()) "Continue watching" else shelves.firstOrNull { it.second.isNotEmpty() }?.first
    val focusFirst: () -> Modifier = remember { { Modifier.focusRequester(first) } }
    val focusNone: () -> Modifier = remember { { Modifier } }
    fun focusFor(title: String): () -> Modifier = if (title == firstShelf) focusFirst else focusNone
    val hold: (AngItem) -> Unit = remember { { menuFor = it } }
    val onHoldWatching: (WatchItem) -> Unit = remember(state.watchingNow) {
        { wi -> state.watchingNow.firstOrNull { MANUAL_PREFIX + it.key == wi.key }?.let { menuFor = it } }
    }

    Box(Modifier.fillMaxSize()) {
        // The backdrop in its own layer: drawn once, not again on every frame of a scroll.
        Box(Modifier.fillMaxSize().graphicsLayer().angBackdrop())
        // Lazy, top to bottom: only the rows on screen are built and load their pictures.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 32.dp),
        ) {
          item(key = "top", contentType = "top") { Column {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d").format(LocalDate.now()).uppercase(),
                        fontSize = 12.sp,
                        letterSpacing = 2.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AngPalette.InkFaint,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(greeting(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AngPalette.Ink)
                        Spacer(Modifier.width(10.dp))
                        Text("♥", fontSize = 24.sp, color = AngPalette.Pink, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        state.loading -> "Updating…"
                        state.discovering -> "Finding shows for you…"
                        else -> "Hold select on anything to like it, save it, or hide it"
                    },
                    fontSize = 12.sp,
                    color = AngPalette.InkFaint,
                )
                Spacer(Modifier.width(18.dp))
                GlassPill("♥  Her List", onOpenList)
                onSwitchProfile?.let { switch ->
                    Spacer(Modifier.width(12.dp))
                    GlassPill("Switch", switch)
                }
            }
            Spacer(Modifier.height(20.dp))
          } }
          if (!apps.isNullOrEmpty()) item(key = "apps", contentType = "apps") { Column {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    items(apps.size, key = { apps[it].packageName }) { i ->
                        val app = apps[i]
                        FocusCard(onClick = { onApp(app) }, container = AngPalette.Glass, edge = AngPalette.Edge, corner = 18.dp, focusedScale = 1.07f, modifier = Modifier.size(150.dp, 84.dp)) {
                            val art = app.banner ?: app.icon
                            if (art != null) androidx.compose.foundation.Image(art, app.label, Modifier.fillMaxSize(),
                                contentScale = if (app.banner != null) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Inside)
                            else Text(app.label, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
          } }

          if (!state.loading && !state.discovering && continueWatching.isEmpty() && all.isEmpty()) item(key = "empty") {
                Text(
                    "Couldn't load shows right now. Check the internet connection.",
                    color = AngPalette.Ink,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }

          if (continueWatching.isNotEmpty()) item(key = "continue", contentType = "continue") { Column {
            ContinueShelf(continueWatching, onResume, focusFor("Continue watching"), onHoldWatching)
          } }
          item(key = "today", contentType = "today") { Column { AngTodayRow() } }
          shelves.forEach { (title, list, label) ->
            if (list.isNotEmpty()) item(key = title, contentType = "shelf") { Column {
                Shelf(title, list, onShow, focusFor(title), dated = title == "Coming soon", onLongClick = hold, label = label)
            } }
          }
        }
    }

    menuFor?.let { item ->
        TasteMenu(
            title = item.title,
            current = state.ratings[item.key],
            container = AngPalette.Sheet,
            accent = AngPalette.Pink,
            onPick = { rating -> onRate(item, rating); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
}

private val BadgeLabel: (AngItem) -> String? = { it.badge }
private val NoLabel: (AngItem) -> String? = { null }
private val DateLabel: (AngItem) -> String? = { a -> a.date?.let(::comingLabel) ?: "Date TBA" }

private fun comingLabel(date: LocalDate): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date)
    return when (days) {
        0L -> "Today"
        1L -> "Tomorrow"
        in 2L..6L -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        else -> dateFormat.format(date)
    }
}

@Composable
private fun Shelf(
    title: String,
    items: List<AngItem>,
    onShow: (AngItem) -> Unit,
    firstFocus: () -> Modifier,
    dated: Boolean = false,
    onLongClick: ((AngItem) -> Unit)? = null,
    label: (AngItem) -> String?,
) {
    if (items.isEmpty()) return
    Text(
        title,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = AngPalette.Ink.copy(alpha = 0.92f),
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
    )
    val focusFirst = firstFocus()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.height(268.dp),
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, _ -> "poster" }) { i, item ->
            PosterCard(
                item = item,
                label = label(item),
                dated = dated,
                modifier = if (i == 0) focusFirst else Modifier,
                onClick = { onShow(item) },
                onLongClick = onLongClick?.let { f -> { f(item) } },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** Landscape cards that resume playback, like the home screen's Continue Watching. */
@Composable
private fun ContinueShelf(
    items: List<WatchItem>,
    onResume: (WatchItem) -> Unit,
    firstFocus: () -> Modifier,
    onHold: (WatchItem) -> Unit,
) {
    if (items.isEmpty()) return
    Text(
        "Continue watching",
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = AngPalette.Ink.copy(alpha = 0.92f),
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
    )
    val focusFirst = firstFocus()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.height(186.dp),
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, _ -> "watching" }) { i, item ->
            Column(Modifier.width(220.dp).padding(top = 8.dp)) {
                FocusCard(
                    onClick = { onResume(item) },
                    onLongClick = if (item.key.startsWith(MANUAL_PREFIX)) ({ onHold(item) }) else null,
                    modifier = (if (i == 0) focusFirst else Modifier).size(220.dp, 124.dp),
                    container = AngPalette.Glass,
                    edge = AngPalette.Edge,
                    corner = 16.dp,
                ) {
                    if (item.imageUrl != null) {
                        AsyncImage(item.imageUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            item.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.Center).padding(10.dp),
                        )
                    }
                    item.progress?.let { p ->
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(2.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(p.coerceIn(0.02f, 1f))
                                    .background(AngPalette.Pink, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AngPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, fontSize = 11.sp, color = AngPalette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PosterCard(
    item: AngItem,
    label: String?,
    dated: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(134.dp).padding(top = 8.dp)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(134.dp, 196.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
            container = AngPalette.Glass,
            edge = AngPalette.Edge,
            corner = 16.dp,
        ) {
            if (item.imageUrl != null) {
                AsyncImage(item.imageUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }
            if (label != null) {
                Box(
                    Modifier
                        .align(if (dated) Alignment.BottomCenter else Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCC12181D), RoundedCornerShape(10.dp))
                        .border(1.dp, AngPalette.Edge, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dated) AngPalette.Yellow else Color.White,
                        maxLines = 1,
                    )
                }
            }
            RatingTag(item.ratingRef, focused, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AngPalette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.detail, fontSize = 11.sp, color = AngPalette.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning, Ang"
    in 12..16 -> "Good afternoon, Ang"
    else -> "Good evening, Ang"
}

/** A frosted pill button (Her List, Switch). */
@Composable
private fun GlassPill(text: String, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        container = AngPalette.Glass,
        focusedContainer = AngPalette.GlassFocused,
        edge = AngPalette.Edge,
        corner = 20.dp,
        focusedScale = 1.06f,
        modifier = Modifier.height(40.dp),
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        )
    }
}
