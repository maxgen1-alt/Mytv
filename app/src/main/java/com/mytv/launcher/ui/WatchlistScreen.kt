@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.mytv.launcher.MyState
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.Rating
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("MMM d")
private val Accent = Color(0xFFF5C518)

/** A poster for your rows: rating tag top-right (on focus, or always), badge top-left. */
@Composable
fun TitleCard(
    item: AngItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysShowRating: Boolean = false,
    dateLabel: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(126.dp).padding(top = 8.dp)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(126.dp, 189.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        ) {
            if (item.imageUrl != null) {
                AsyncImage(item.imageUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }
            item.badge?.let { Chip(it, Palette.Ink, Palette.Base, Modifier.align(Alignment.TopStart).padding(8.dp)) }
            dateLabel?.let { Chip(it, Accent, Color.Black, Modifier.align(Alignment.BottomCenter).padding(8.dp)) }
            RatingTag(item.ratingRef, focused, Modifier.align(Alignment.TopEnd).padding(8.dp), always = alwaysShowRating)
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.Ink)
        Text(item.detail, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.InkSoft)
    }
}

@Composable
private fun Chip(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Box(modifier.background(bg, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

private enum class Tab(val title: String, val empty: String) {
    WATCHING("Watching", "Hold select on any show and choose \"Watching now\". It shows here and in Continue Watching."),
    LATER("Watch Later", "Hold select on any show and choose \"Watch later\" to save it here."),
    WATCHED("Watched", "Hold select on any show or movie on the home screen and choose \"Already watched\" or \"Like it\". It lands here."),
    FOR_YOU("For You", "Mark a few shows you've watched and recommendations show up here."),
    NEW_SEASONS("New Seasons", "New seasons of shows you watched or liked appear here when they come out."),
    COMING_SOON("Coming Soon", "Next seasons of your shows, and new shows in the genres you watch most."),
}

/** My Watchlist: what you watched, what to watch next, and what's coming. */
@Composable
fun WatchlistScreen(state: MyState, onOpen: (AngItem) -> Unit, onRate: (AngItem, Rating?) -> Unit) {
    var tab by remember { mutableStateOf(Tab.WATCHING) }
    var menuFor by remember { mutableStateOf<AngItem?>(null) }
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

    val g = state.guide
    val items = when (tab) {
        Tab.WATCHING -> g.watching
        Tab.LATER -> g.watchLater
        Tab.WATCHED -> g.watched
        Tab.FOR_YOU -> g.forYou
        Tab.NEW_SEASONS -> g.newSeasons
        Tab.COMING_SOON -> g.comingSoon
    }

    Column(Modifier.fillMaxSize().background(LocalBackground.current).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My Watchlist", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Spacer(Modifier.weight(1f))
            if (state.loading) Text("Updating…", fontSize = 13.sp, color = Palette.InkSoft)
            else Text("Hold select on a title to change it", fontSize = 13.sp, color = Palette.InkSoft)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tab.entries.forEachIndexed { i, t ->
                val selected = t == tab
                FocusCard(
                    onClick = { tab = t },
                    container = if (selected) Palette.Ink else Palette.CardSoft,
                    corner = 20.dp,
                    focusedScale = 1.06f,
                    modifier = (if (i == 0) Modifier.focusRequester(firstTab) else Modifier)
                        .height(40.dp)
                        // Moving onto a tab opens it, like Apple TV.
                        .onFocusChanged { if (it.isFocused || it.hasFocus) tab = t },
                ) {
                    Text(
                        t.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) Palette.Base else Palette.Ink,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (items.isEmpty()) {
            Text(
                if (state.loading) "Loading…" else tab.empty,
                fontSize = 15.sp,
                color = Palette.InkSoft,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(126.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    TitleCard(
                        item = item,
                        onClick = { onOpen(item) },
                        onLongClick = { menuFor = item },
                        alwaysShowRating = tab == Tab.FOR_YOU,
                        dateLabel = when (tab) {
                            Tab.COMING_SOON -> item.date?.let(::shortDate) ?: "Date TBA"
                            Tab.NEW_SEASONS -> item.date?.let(::shortDate)
                            else -> null
                        },
                    )
                }
            }
        }
    }

    menuFor?.let { item ->
        TasteMenu(
            title = item.title,
            current = state.ratings[item.key],
            container = Palette.Card,
            accent = Color(0xFFE5383B),
            onPick = { r -> onRate(item, r); menuFor = null },
            onDismiss = { menuFor = null },
            likeHint = "More like this",
            watchedHint = "Recommendations get better",
        )
    }
}

private fun shortDate(d: LocalDate): String = if (d == LocalDate.now()) "Today" else dateFormat.format(d)
