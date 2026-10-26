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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.watching.WatchItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Love Island colours. */
object AngPalette {
    val Teal = Color(0xFF14C3C0)
    val DeepTeal = Color(0xFF0A8F9E)
    val Pink = Color(0xFFFF4FA3)
    val Yellow = Color(0xFFFFD447)
    val Background = Brush.verticalGradient(listOf(Teal, DeepTeal))
    val Tile = Brush.linearGradient(listOf(Pink, Teal))
}

private val dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

@Composable
fun AngScreen(
    state: AngState,
    onShow: (AngItem) -> Unit,
    onMarkWatched: (AngItem) -> Unit,
    onResume: (WatchItem) -> Unit,
) {
    val first = remember { FocusRequester() }
    val guide = state.guide
    val firstKey = state.continueWatching.firstOrNull()?.key
        ?: (guide.watchNow + guide.newShows + guide.upcoming).firstOrNull()?.key
    LaunchedEffect(firstKey) { if (firstKey != null) runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(AngPalette.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 32.dp),
        ) {
            Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.Bottom) {
                Text("Ang's TV", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("♥", fontSize = 28.sp, color = AngPalette.Pink, modifier = Modifier.padding(bottom = 2.dp))
                Spacer(Modifier.weight(1f))
                if (state.loading) Text("Updating…", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.height(20.dp))

            if (!state.loading && state.continueWatching.isEmpty() && guide.watchNow.isEmpty() && guide.newShows.isEmpty() && guide.upcoming.isEmpty()) {
                Text(
                    "Couldn't load shows right now. Check the internet connection.",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }

            var focusUsed = false
            fun firstFocus(): Modifier =
                if (!focusUsed) { focusUsed = true; Modifier.focusRequester(first) } else Modifier

            ContinueShelf(state.continueWatching, onResume, ::firstFocus)
            Shelf("Watch now", guide.watchNow, onShow, ::firstFocus, onLongClick = onMarkWatched) { it.badge }
            Shelf("New shows you might like", guide.newShows, onShow, ::firstFocus, onLongClick = onMarkWatched) { it.badge }
            Shelf("Coming soon", guide.upcoming, onShow, ::firstFocus, dated = true) { it.date?.let(::comingLabel) }

            if (guide.watchNow.isNotEmpty() || guide.newShows.isNotEmpty()) {
                Text(
                    "Tip: hold select on a show to mark it as watched.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            if (state.discovering) {
                Text(
                    "Looking for new shows…",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                )
            }
        }
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
        color = Color.White,
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
private fun ContinueShelf(items: List<WatchItem>, onResume: (WatchItem) -> Unit, firstFocus: () -> Modifier) {
    if (items.isEmpty()) return
    Text(
        "Continue watching",
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
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
                Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Column(Modifier.width(134.dp).padding(top = 8.dp)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(134.dp, 196.dp),
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
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.detail, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
