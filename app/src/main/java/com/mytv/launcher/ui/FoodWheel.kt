@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.mytv.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** What's for dinner: the wheel's slices. */
object FoodWheel {
    data class Food(val emoji: String, val name: String)

    val foods = listOf(
        Food("🍣", "Sushi"),
        Food("🍕", "Pizza"),
        Food("🍜", "Thai"),
        Food("🌮", "Tacos"),
        Food("🥡", "Chinese"),
        Food("🍛", "Indian"),
        Food("🍔", "Burgers"),
        Food("🍥", "Ramen"),
        Food("🥩", "Korean BBQ"),
        Food("🍲", "Pho"),
        Food("🥙", "Mediterranean"),
        Food("🐟", "Poke"),
        Food("🍝", "Italian"),
        Food("🍗", "Wings"),
        Food("🥪", "Sandwiches"),
        Food("🥗", "Salad Bowls"),
        Food("🍖", "BBQ"),
        Food("🥞", "Breakfast"),
    )

    /** Slice colours: soft glass tints that go around the wheel. */
    val tints = listOf(
        Color(0xFFFF8FC1), Color(0xFF6FD6D1), Color(0xFFB9A6FF), Color(0xFFFFC98B),
        Color(0xFF8EC5FF), Color(0xFFFFA3A3),
    )

    /** The slice under the pointer (top) when the wheel is turned [rotation] degrees clockwise. */
    fun indexAt(rotation: Float, count: Int = foods.size): Int {
        val sweep = 360f / count
        val a = ((-rotation % 360f) + 360f) % 360f
        return (a / sweep).toInt().coerceIn(0, count - 1)
    }

    /**
     * Where to stop so [winner] lands under the pointer: at least [turns] full turns
     * past [from], [jitter] (0..1) across the slice so it doesn't always stop dead centre.
     */
    fun targetFor(from: Float, winner: Int, jitter: Float, turns: Int = 7, count: Int = foods.size): Float {
        val sweep = 360f / count
        val inSlice = winner * sweep + sweep * (0.2f + 0.6f * jitter.coerceIn(0f, 1f))
        val desired = ((360f - inSlice) % 360f + 360f) % 360f
        val now = ((from % 360f) + 360f) % 360f
        val extra = ((desired - now) % 360f + 360f) % 360f
        return from + turns * 360f + extra
    }
}

/** The wheel itself, drawn once and turned as a layer: spinning redraws nothing. */
@Composable
fun WheelFace(rotation: () -> Float, modifier: Modifier = Modifier, labels: Boolean = true) {
    val measurer = rememberTextMeasurer()
    val foods = FoodWheel.foods
    Canvas(modifier.graphicsLayer { rotationZ = rotation() }) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val sweep = 360f / foods.size
        foods.forEachIndexed { i, _ ->
            val tint = FoodWheel.tints[i % FoodWheel.tints.size]
            drawArc(
                brush = Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.18f), tint.copy(alpha = 0.55f)),
                    center = c, radius = r,
                ),
                startAngle = -90f + i * sweep,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2 * r, 2 * r),
            )
        }
        // Thin white spokes and rim.
        foods.indices.forEach { i ->
            val a = Math.toRadians((-90.0 + i * sweep))
            drawLine(
                Color.White.copy(alpha = 0.35f), c,
                Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat()),
                strokeWidth = 1.dp.toPx(),
            )
        }
        drawCircle(Color.White.copy(alpha = 0.55f), r - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        if (labels) {
            val style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            foods.forEachIndexed { i, f ->
                // Each label runs along its slice, from the hub outwards.
                val mid = -90f + i * sweep + sweep / 2f
                val layout = measurer.measure("${f.emoji} ${f.name}", style)
                rotate(mid, c) {
                    drawText(
                        layout,
                        topLeft = Offset(c.x + r - layout.size.width - 14.dp.toPx(), c.y - layout.size.height / 2f),
                    )
                }
            }
        }
        // Glass hub.
        drawCircle(Color(0xFF141C22), r * 0.17f, c)
        drawCircle(Color.White.copy(alpha = 0.12f), r * 0.17f, c)
        drawCircle(Color.White.copy(alpha = 0.5f), r * 0.17f, c, style = Stroke(1.5.dp.toPx()))
    }
}

/** The pointer at the top of the wheel. */
@Composable
private fun Pointer(modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp, 30.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
        }
        drawPath(p, Color.White)
        drawPath(p, AngPalette.Pink.copy(alpha = 0.35f))
    }
}

/** Full-screen: spin the wheel, see what's for dinner. */
@Composable
fun FoodWheelDialog(onDismiss: () -> Unit) {
    val angle = remember { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FoodWheel.Food?>(null) }
    val scope = rememberCoroutineScope()
    val spinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { spinFocus.requestFocus() } }

    fun spin() {
        if (spinning) return
        spinning = true
        result = null
        val rnd = kotlin.random.Random(System.nanoTime())
        val winner = rnd.nextInt(FoodWheel.foods.size)
        val target = FoodWheel.targetFor(angle.value, winner, rnd.nextFloat(), turns = 6 + rnd.nextInt(3))
        scope.launch {
            // Fast start, long graceful slow-down: like a real wheel.
            angle.animateTo(target, tween(5200, easing = CubicBezierEasing(0.15f, 0.85f, 0.25f, 1f)))
            result = FoodWheel.foods[FoodWheel.indexAt(angle.value)]
            spinning = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .glass(corner = 32.dp, fill = AngPalette.Sheet)
                .padding(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(430.dp), contentAlignment = Alignment.TopCenter) {
                WheelFace({ angle.value }, Modifier.fillMaxSize().padding(top = 14.dp))
                Pointer()
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("WHAT'S FOR DINNER", fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold, color = AngPalette.InkSoft)
                val r = result
                when {
                    r != null -> {
                        Text(r.emoji, fontSize = 64.sp)
                        Text(r.name, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = AngPalette.Ink)
                        Text("The wheel has spoken. No take-backs ♥", fontSize = 15.sp, color = AngPalette.InkSoft)
                    }
                    spinning -> Text("Spinning…", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AngPalette.Ink)
                    else -> {
                        Text("Can't decide?", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AngPalette.Ink)
                        Text("${FoodWheel.foods.size} options. One spin. Whatever it lands on, we order.", fontSize = 15.sp, color = AngPalette.InkSoft)
                    }
                }
                Spacer(Modifier.height(8.dp))
                FocusCard(
                    onClick = ::spin,
                    container = AngPalette.Pink.copy(alpha = 0.85f),
                    focusedContainer = AngPalette.Pink,
                    corner = 26.dp,
                    focusedScale = 1.05f,
                    modifier = Modifier.focusRequester(spinFocus).width(220.dp).height(52.dp),
                ) {
                    Text(
                        if (result == null) "Spin the wheel" else "Spin again",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                FocusCard(
                    onClick = onDismiss,
                    container = AngPalette.Glass,
                    focusedContainer = AngPalette.GlassFocused,
                    edge = AngPalette.Edge,
                    corner = 26.dp,
                    focusedScale = 1.05f,
                    modifier = Modifier.width(220.dp).height(48.dp),
                ) {
                    Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
