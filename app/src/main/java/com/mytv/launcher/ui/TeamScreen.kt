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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mytv.launcher.TeamDetail
import com.mytv.launcher.sports.Game
import com.mytv.launcher.sports.GameState
import com.mytv.launcher.sports.Standings
import com.mytv.launcher.sports.Team
import com.mytv.launcher.sports.WatchOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFormat = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")

@Composable
fun TeamScreen(
    team: Team,
    detail: TeamDetail,
    onWatch: (Game, WatchOption?) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(detail.loading) {
        if (!detail.loading) runCatching { firstFocus.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(LocalBackground.current)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        // Left: header, live game, upcoming, recent
        Column(
            Modifier
                .weight(1.15f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(team.logoUrl, team.name, Modifier.size(64.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(team.name, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(
                        if (detail.live != null) "Playing now" else "No game on right now",
                        fontSize = 14.sp,
                        color = Palette.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            if (detail.loading) {
                Text("Loading…", color = Palette.InkSoft, fontSize = 16.sp)
                return@Column
            }

            var focusAssigned = false
            fun Modifier.firstFocusable(): Modifier =
                if (!focusAssigned) { focusAssigned = true; this.focusRequester(firstFocus) } else this

            detail.live?.let { live ->
                SectionTitle("Live")
                val options: List<WatchOption?> = detail.liveOptions.ifEmpty { listOf(null) }
                options.forEachIndexed { i, opt ->
                    GameCard(
                        team = team,
                        game = live,
                        trailing = opt?.let { "Watch on ${it.app.label}" } ?: "Find it on Google TV",
                        modifier = Modifier.padding(bottom = 10.dp).let { if (i == 0) it.firstFocusable() else it },
                        onClick = { onWatch(live, opt) },
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            SectionTitle("Upcoming")
            if (detail.upcoming.isEmpty()) {
                Text("No upcoming games found.", color = Palette.InkSoft, fontSize = 14.sp)
            }
            detail.upcoming.forEach { game ->
                GameCard(
                    team = team,
                    game = game,
                    trailing = game.broadcasts.take(2).joinToString(" · ").ifBlank { game.league.defaultNetworks.first() },
                    modifier = Modifier.padding(bottom = 10.dp).firstFocusable(),
                    onClick = {},
                )
            }

            if (detail.recent.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionTitle("Recent results")
                detail.recent.forEach { game ->
                    GameCard(
                        team = team,
                        game = game,
                        trailing = resultLabel(team, game),
                        modifier = Modifier.padding(bottom = 10.dp).firstFocusable(),
                        onClick = {},
                    )
                }
            }
        }

        // Right: standings
        Column(Modifier.weight(1f)) {
            val standings = detail.standings
            if (standings != null) {
                StandingsTable(team, standings)
            } else if (!detail.loading) {
                Text("Standings unavailable.", color = Palette.InkSoft, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun GameCard(team: Team, game: Game, trailing: String, modifier: Modifier, onClick: () -> Unit) {
    val opponent = game.opponentOf(team.espnId)
    val home = game.isHome(team.espnId)
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp), focusedScale = 1.03f) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(opponent.logoUrl, opponent.name, Modifier.size(36.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (home) "vs " else "@ ") + opponent.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val time = when (game.state) {
                    GameState.UPCOMING ->
                        whenFormat.format(Instant.ofEpochMilli(game.startMillis).atZone(ZoneId.systemDefault()))
                    else -> game.statusText
                }
                Text("${game.league.displayName} · $time", fontSize = 12.sp, color = Palette.InkSoft, maxLines = 1)
            }
            if (game.state == GameState.LIVE) {
                LiveBadge()
                Spacer(Modifier.width(10.dp))
            }
            Text(trailing, fontSize = 13.sp, color = Palette.Ink, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun resultLabel(team: Team, game: Game): String {
    val us = if (game.isHome(team.espnId)) game.home else game.away
    val them = game.opponentOf(team.espnId)
    val a = us.score?.toDoubleOrNull()
    val b = them.score?.toDoubleOrNull()
    val letter = when {
        a == null || b == null -> ""
        a > b -> "W "
        a < b -> "L "
        else -> "D "
    }
    return "$letter${us.score ?: "-"}–${them.score ?: "-"}"
}

@Composable
private fun StandingsTable(team: Team, standings: Standings) {
    SectionTitle(standings.title)
    val rowHeight = if (standings.rows.size > 16) 20.dp else 24.dp
    val font = if (standings.rows.size > 16) 12.sp else 13.sp
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.CardSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            Text("#", Modifier.width(24.dp), fontSize = font, color = Palette.InkSoft)
            Text("Team", Modifier.weight(1f), fontSize = font, color = Palette.InkSoft)
            standings.columns.forEach {
                Text(it, Modifier.width(40.dp), fontSize = font, color = Palette.InkSoft, textAlign = TextAlign.End)
            }
        }
        standings.rows.forEach { row ->
            val mine = row.teamId == team.espnId
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .background(if (mine) Color(team.color).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weight = if (mine) FontWeight.Bold else FontWeight.Normal
                Text("${row.rank}", Modifier.width(24.dp), fontSize = font, fontWeight = weight, color = Palette.Ink)
                Box(Modifier.weight(1f)) {
                    Text(row.name, fontSize = font, fontWeight = weight, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                row.values.forEach {
                    Text(it, Modifier.width(40.dp), fontSize = font, fontWeight = weight, color = Palette.Ink, textAlign = TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}
