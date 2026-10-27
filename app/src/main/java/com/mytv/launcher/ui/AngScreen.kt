@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.background
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

/** Love Island colours. */
object AngPalette {
    val Teal = Color(0xFF14C3C0)
    val DeepTeal = Color(0xFF0A8F9E)
    val Pink = Color(0xFFFF4FA3)
    val Yellow = Color(0xFFFFD447)
    /** Text on the teal background: black reads far better than white. */
    val Ink = Color(0xFF0D0D0D)
    val InkSoft = Color(0xFF1F3A40)
    val Background = Brush.verticalGradient(listOf(Teal, DeepTeal))
    val Tile = Brush.linearGradient(listOf(Pink, Teal))
}

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

    Box(Modifier.fillMaxSize().background(AngPalette.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 32.dp),
        ) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
                Text("Ang's TV", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AngPalette.Ink)
                Spacer(Modifier.width(12.dp))
                Text("♥", fontSize = 28.sp, color = AngPalette.Pink, modifier = Modifier.padding(bottom = 2.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        state.loading -> "Updating…"
                        state.discovering -> "Finding shows for you…"
                        else -> "Hold select on anything to like it, dislike it, or mark it watched"
                    },
                    fontSize = 13.sp,
                    color = AngPalette.InkSoft,
                )
                Spacer(Modifier.width(16.dp))
                FocusCard(onClick = onOpenList, container = AngPalette.Pink, corner = 18.dp, focusedScale = 1.06f, modifier = Modifier.height(36.dp)) {
                    Text(
                        "♥ Her List",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            if (!state.loading && !state.discovering && continueWatching.isEmpty() && all.isEmpty()) {
                Text(
                    "Couldn't load shows right now. Check the internet connection.",
                    color = AngPalette.Ink,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }

            var focusUsed = false
            fun firstFocus(): Modifier =
                if (!focusUsed) { focusUsed = true; Modifier.focusRequester(first) } else Modifier
            val hold: (AngItem) -> Unit = { menuFor = it }

            ContinueShelf(continueWatching, onResume, ::firstFocus) { wi ->
                state.watchingNow.firstOrNull { MANUAL_PREFIX + it.key == wi.key }?.let { menuFor = it }
            }
            Shelf("Watch later", state.watchLater, onShow, ::firstFocus, onLongClick = hold) { null }
            Shelf("Watch now", guide.watchNow, onShow, ::firstFocus, onLongClick = hold) { it.badge }
            Shelf("Easy Watch", guide.easyWatch, onShow, ::firstFocus, onLongClick = hold) { it.badge }
            Shelf("New shows you might like", guide.newShows, onShow, ::firstFocus, onLongClick = hold) { it.badge }
            Shelf("Shows you might have missed", guide.missed, onShow, ::firstFocus, onLongClick = hold) { it.badge }
            Shelf("Movies for you", guide.movies, onShow, ::firstFocus, onLongClick = hold) { it.badge }
            Shelf("Coming soon", guide.upcoming, onShow, ::firstFocus, dated = true, onLongClick = hold) { it.date?.let(::comingLabel) ?: "Date TBA" }
        }
    }

    menuFor?.let { item ->
        TasteMenu(
            title = item.title,
            current = state.ratings[item.key],
            container = AngPalette.DeepTeal,
            accent = AngPalette.Pink,
            onPick = { rating -> onRate(item, rating); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
}

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
        color = AngPalette.Ink,
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
    )
    val focusFirst = firstFocus()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.height(268.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items, key = { it.key }) { item ->
            PosterCard(
                item = item,
                label = label(item),
                dated = dated,
                modifier = if (item == items.first()) focusFirst else Modifier,
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
        color = AngPalette.Ink,
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
    )
    val focusFirst = firstFocus()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.height(186.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(items, key = { it.key }) { item ->
            Column(Modifier.width(220.dp).padding(top = 8.dp)) {
                FocusCard(
                    onClick = { onResume(item) },
                    onLongClick = if (item.key.startsWith(MANUAL_PREFIX)) ({ onHold(item) }) else null,
                    modifier = (if (item == items.first()) focusFirst else Modifier).size(220.dp, 124.dp),
                    container = AngPalette.DeepTeal,
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
                                .background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
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
            container = AngPalette.DeepTeal,
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
                        .background(if (dated) AngPalette.Yellow else AngPalette.Pink, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dated) Color(0xFF3A2A00) else Color.White,
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
