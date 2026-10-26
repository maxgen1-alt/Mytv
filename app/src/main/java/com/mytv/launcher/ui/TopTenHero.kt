@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mytv.launcher.topten.TopTenService
import com.mytv.launcher.topten.TopTitle
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HeroHeight = 196.dp
private val PosterWidth = 72.dp
private val PosterHeight = 108.dp

/**
 * The area above the apps row: "Top 10 on Netflix" with posters and the #1
 * title's artwork behind it, changing as you move across the apps. Apps
 * without a Top 10 (YouTube, YouTube TV...) get a quiet greeting instead.
 */
@Composable
fun TopTenHero(service: TopTenService?, titles: List<TopTitle>, configured: Boolean) {
    val key = if (service != null && titles.isNotEmpty()) service else null
    Crossfade(targetState = key, animationSpec = tween(350), label = "topten") { shown ->
        Box(Modifier.fillMaxWidth().height(HeroHeight)) {
            if (shown == null) {
                Greeting(hint = if (service != null && !configured) "Add a TMDB key to see the Top 10 here" else null)
            } else {
                TopTen(shown, titles)
            }
        }
    }
}

@Composable
private fun TopTen(service: TopTenService, titles: List<TopTitle>) {
    Box(Modifier.fillMaxSize()) {
        // #1's artwork on the right, fading into the background.
        titles.first().backdropUrl?.let { url ->
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.62f)) {
                AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(Palette.Sky, Palette.Sky.copy(alpha = 0.55f), Color.Transparent)),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.6f to Color.Transparent, 1f to Palette.Sky),
                    ),
                )
            }
        }

        Column(Modifier.padding(horizontal = 48.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Top 10", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text(
                    "  on ${service.displayName}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.InkSoft,
                )
            }
            Text(
                titles.first().title,
                fontSize = 13.sp,
                color = Palette.InkSoft,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                titles.take(10).forEachIndexed { i, t -> RankedPoster(i + 1, t) }
            }
        }
    }
}

@Composable
private fun RankedPoster(rank: Int, title: TopTitle) {
    Box(Modifier.size(PosterWidth, PosterHeight + 8.dp)) {
        AsyncImage(
            model = title.posterUrl,
            contentDescription = title.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(PosterWidth, PosterHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.CardSoft),
        )
        Text(
            "$rank",
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-6).dp, y = 6.dp),
            style = TextStyle(
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                shadow = Shadow(color = Palette.Ink.copy(alpha = 0.8f), blurRadius = 8f),
            ),
        )
    }
}

@Composable
private fun Greeting(hint: String?) {
    val now = LocalDateTime.now()
    val part = when (now.toLocalTime()) {
        in LocalTime.of(5, 0)..LocalTime.of(11, 59) -> "Good morning"
        in LocalTime.of(12, 0)..LocalTime.of(16, 59) -> "Good afternoon"
        else -> "Good evening"
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(part, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            DateTimeFormatter.ofPattern("EEEE, MMMM d").format(now),
            fontSize = 16.sp,
            color = Palette.InkSoft,
        )
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, fontSize = 12.sp, color = Palette.InkSoft)
        }
    }
}
