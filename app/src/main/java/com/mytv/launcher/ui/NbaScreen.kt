@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mytv.launcher.NbaState
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.Side
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val NBA_LOGO = "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png"

/** Prime Video's blue, so Play reads as "opens Prime Video". */
private val PrimeBlue = Color(0xFF0F79AF)

private val tipOff = DateTimeFormatter.ofPattern("h:mm a")
private val dayTitle = DateTimeFormatter.ofPattern("EEEE, MMMM d")

@Composable
fun NbaScreen(
    state: NbaState,
    onDay: (Int) -> Unit,
    onPlay: (Game) -> Unit,
) {
    val firstPlay = remember { FocusRequester() }
    val todayTab = remember { FocusRequester() }
    LaunchedEffect(state.loading, state.dayOffset) {
        if (!state.loading) runCatching { if (state.games.isNotEmpty()) firstPlay.requestFocus() else todayTab.requestFocus() }
    }
    val zone = ZoneId.systemDefault()

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalBackground.current)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(NBA_LOGO, "NBA", Modifier.size(56.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("NBA", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                val live = state.games.count { it.state == GameState.LIVE }
                Text(
                    LocalDate.now(zone).plusDays(state.dayOffset.toLong()).format(dayTitle) +
                        (if (live > 0) " · $live live" else "") + " · Play opens League Pass on Prime Video",
                    fontSize = 14.sp,
                    color = Palette.InkSoft,
                )
            }
            listOf(-1 to "Yesterday", 0 to "Today", 1 to "Tomorrow").forEach { (offset, label) ->
                val selected = offset == state.dayOffset
                FocusCard(
                    onClick = { onDay(offset) },
                    container = if (selected) Palette.Card else Palette.CardSoft,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .height(40.dp)
                        .then(if (offset == 0) Modifier.focusRequester(todayTab) else Modifier),
                    focusedScale = 1.05f,
                ) {
                    Text(
                        label,
                        Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Palette.Ink else Palette.InkSoft,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        if (state.games.isEmpty()) {
            Text(
                if (state.loading) "Loading…" else "No NBA games this day.",
                color = Palette.InkSoft,
                fontSize = 16.sp,
            )
            return@Column
        }

        // Two games per row: a full slate (up to 15 games) fits in a few screens.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            state.games.chunked(2).forEachIndexed { r, pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    pair.forEachIndexed { c, game ->
                        GameRow(
                            game = game,
                            status = statusText(game, zone),
                            playModifier = if (r == 0 && c == 0) Modifier.focusRequester(firstPlay) else Modifier,
                            onPlay = { onPlay(game) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** One game: both teams with their scores, the clock or tip-off, and a Play button next to it. */
@Composable
private fun GameRow(game: Game, status: String, playModifier: Modifier, onPlay: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .height(96.dp)
            .background(Palette.CardSoft, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val final = game.state == GameState.FINISHED
            val a = game.away.score?.toDoubleOrNull()
            val h = game.home.score?.toDoubleOrNull()
            TeamLine(game.away, game.state != GameState.UPCOMING, winner = final && a != null && h != null && a > h)
            TeamLine(game.home, game.state != GameState.UPCOMING, winner = final && a != null && h != null && h > a)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(110.dp), horizontalAlignment = Alignment.End) {
            if (game.state == GameState.LIVE) {
                LiveBadge()
                Spacer(Modifier.height(4.dp))
            }
            Text(
                status,
                fontSize = 13.sp,
                fontWeight = if (game.state == GameState.LIVE) FontWeight.Bold else FontWeight.Medium,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (game.broadcasts.isNotEmpty()) {
                Text(
                    game.broadcasts.take(2).joinToString(" · "),
                    fontSize = 11.sp,
                    color = Palette.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        FocusCard(onClick = onPlay, modifier = playModifier.size(width = 112.dp, height = 56.dp), focusedScale = 1.1f, container = PrimeBlue) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▶  Play", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text("Prime Video", fontSize = 10.sp, color = Palette.Ink)
            }
        }
    }
}

@Composable
private fun TeamLine(side: Side, showScore: Boolean, winner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(side.logoUrl, side.name, Modifier.size(30.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            Text(
                side.name,
                fontSize = 16.sp,
                fontWeight = if (winner) FontWeight.Bold else FontWeight.SemiBold,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showScore) {
            Text(side.score ?: "0", fontSize = 20.sp, fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium, color = Palette.Ink)
        }
    }
}

/** "Q3 5:42" while it's on, "7:30 PM" before, "Final" after. */
private fun statusText(game: Game, zone: ZoneId): String = when (game.state) {
    GameState.UPCOMING -> Instant.ofEpochMilli(game.startMillis).atZone(zone).format(tipOff)
    GameState.LIVE -> game.statusText.ifBlank { "Live" }
    GameState.FINISHED -> game.statusText.ifBlank { "Final" }
}
