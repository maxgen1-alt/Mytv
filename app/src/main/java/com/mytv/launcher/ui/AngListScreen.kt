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
import com.mytv.launcher.AngState
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.Rating

private enum class HerTab(val title: String, val rating: Rating, val empty: String) {
    WATCHING("Watching", Rating.WATCHING, "Shows she marked \"Watching now\" show up here and in her Continue watching."),
    LIKED("Liked", Rating.LIKE, "Shows she liked: followed for new seasons, and more like them get suggested."),
    WATCHED("Watched", Rating.WATCHED, "Shows she marked \"Already watched\"."),
    DISLIKED("Disliked", Rating.DISLIKE, "Shows she disliked: hidden, and fewer like them get suggested."),
    WONT_WATCH("Won't watch", Rating.WONT_WATCH, "Shows she hid with \"Won't watch\"."),
}

/**
 * Her List: everything she rated in Ang's TV, by rating. Hold select on a
 * show to move it to another list (or clear it); select opens it.
 */
@Composable
fun AngListScreen(state: AngState, onOpen: (AngItem) -> Unit, onRate: (AngItem, Rating?) -> Unit) {
    var tab by remember { mutableStateOf(HerTab.WATCHING) }
    var menuFor by remember { mutableStateOf<AngItem?>(null) }
    val firstTab = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }

    val items = state.rated
        .filter { it.rating == tab.rating }
        .sortedByDescending { it.date }
        .map { it.toItem() }

    Column(Modifier.fillMaxSize().background(AngPalette.Background).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Her List", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("♥", fontSize = 26.sp, color = AngPalette.Pink)
            Spacer(Modifier.weight(1f))
            Text("Hold select on a show to move it to another list", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HerTab.entries.forEachIndexed { i, t ->
                val selected = t == tab
                val count = state.rated.count { it.rating == t.rating }
                FocusCard(
                    onClick = { tab = t },
                    container = if (selected) AngPalette.Pink else Color.White.copy(alpha = 0.16f),
                    corner = 20.dp,
                    focusedScale = 1.06f,
                    modifier = (if (i == 0) Modifier.focusRequester(firstTab) else Modifier)
                        .height(40.dp)
                        // Moving onto a tab opens it.
                        .onFocusChanged { if (it.isFocused || it.hasFocus) tab = t },
                ) {
                    Text(
                        if (count > 0) "${t.title}  $count" else t.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (items.isEmpty()) {
            Text(tab.empty, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(126.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    HerPoster(item, onClick = { onOpen(item) }, onHold = { menuFor = item })
                }
            }
        }
    }

    menuFor?.let { item ->
        TasteMenu(
            title = item.title,
            current = state.ratings[item.key],
            container = AngPalette.DeepTeal,
            accent = AngPalette.Pink,
            onPick = { r -> onRate(item, r); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun HerPoster(item: AngItem, onClick: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(126.dp).padding(top = 8.dp)) {
        FocusCard(
            onClick = onClick,
            onLongClick = onHold,
            container = AngPalette.DeepTeal,
            modifier = Modifier.size(126.dp, 189.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        ) {
            if (item.imageUrl != null) {
                AsyncImage(item.imageUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    item.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }
            RatingTag(item.ratingRef, focused, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
        Text(item.detail, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.8f))
    }
}
