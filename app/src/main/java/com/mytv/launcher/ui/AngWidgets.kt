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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.mytv.launcher.ang.MedQuestion
import com.mytv.launcher.ang.MedQuestionStore
import com.mytv.launcher.ang.MedQuestions
import java.time.LocalDate

private val Letters = listOf("A", "B", "C", "D", "E", "F")

/** Her two glass widgets: the Medical Question of the Day and the dinner wheel. */
@Composable
fun AngTodayRow() {
    val context = LocalContext.current
    val store = remember { MedQuestionStore(context) }
    val today = remember { LocalDate.now() }
    val question = remember(today) { MedQuestions.forDay(today) }
    var stats by remember { mutableStateOf(store.stats(today)) }
    var showQuestion by remember { mutableStateOf(false) }
    var showWheel by remember { mutableStateOf(false) }

    Text(
        "Today",
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = AngPalette.Ink,
        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
    )
    Row(Modifier.padding(horizontal = 48.dp).padding(top = 6.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Question of the Day
        FocusCard(
            onClick = { showQuestion = true },
            container = AngPalette.Glass,
            focusedContainer = AngPalette.GlassFocused,
            edge = AngPalette.Edge,
            corner = 24.dp,
            focusedScale = 1.04f,
            modifier = Modifier.size(560.dp, 164.dp),
        ) {
            Box(Modifier.fillMaxSize().background(AngPalette.Sheen))
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🩺", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("QUESTION OF THE DAY", fontSize = 11.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold, color = AngPalette.Teal)
                    Spacer(Modifier.width(10.dp))
                    Chip(question.topic)
                    Spacer(Modifier.weight(1f))
                    if (stats.streak > 0) Text("🔥 ${stats.streak}-day streak", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AngPalette.Yellow)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    question.stem,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = AngPalette.Ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                val picked = stats.picked
                Text(
                    when {
                        picked == null -> "Select to answer  ›"
                        picked == question.answer -> "✓ Nailed it · ${stats.correct}/${stats.answered} correct so far"
                        else -> "Answer: ${Letters[question.answer]} · ${stats.correct}/${stats.answered} correct so far"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        picked == null -> AngPalette.InkSoft
                        picked == question.answer -> AngPalette.Right
                        else -> AngPalette.Pink
                    },
                )
            }
        }

        // Dinner wheel
        FocusCard(
            onClick = { showWheel = true },
            container = AngPalette.Glass,
            focusedContainer = AngPalette.GlassFocused,
            edge = AngPalette.Edge,
            corner = 24.dp,
            focusedScale = 1.04f,
            modifier = Modifier.size(330.dp, 164.dp),
        ) {
            Box(Modifier.fillMaxSize().background(AngPalette.Sheen))
            Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                WheelFace({ -10f }, Modifier.size(124.dp), labels = false)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("WHAT'S FOR DINNER", fontSize = 11.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold, color = AngPalette.Pink)
                    Spacer(Modifier.height(6.dp))
                    Text("Spin the wheel", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AngPalette.Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("${FoodWheel.foods.size} options, zero arguing", fontSize = 12.sp, color = AngPalette.InkSoft)
                }
            }
        }
    }

    if (showQuestion) {
        MedQuestionDialog(
            question = question,
            picked = stats.picked,
            stats = stats,
            onPick = { i -> stats = store.answer(today, i, i == question.answer) },
            onDismiss = { showQuestion = false },
        )
    }
    if (showWheel) FoodWheelDialog(onDismiss = { showWheel = false })
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AngPalette.Ink)
    }
}

/** The question, five choices, then the answer and the teaching point. */
@Composable
private fun MedQuestionDialog(
    question: MedQuestion,
    picked: Int?,
    stats: MedQuestionStore.Stats,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstChoice = remember { FocusRequester() }
    val done = remember { FocusRequester() }
    val answered = picked != null
    LaunchedEffect(answered) { runCatching { if (answered) done.requestFocus() else firstChoice.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .width(900.dp)
                .glass(corner = 32.dp, fill = AngPalette.Sheet)
                .padding(32.dp),
        ) {
            // Left: the vignette, then (once answered) the explanation.
            Column(Modifier.width(420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🩺  QUESTION OF THE DAY", fontSize = 12.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Bold, color = AngPalette.Teal)
                    Spacer(Modifier.width(10.dp))
                    Chip(question.topic)
                }
                Spacer(Modifier.height(14.dp))
                Text(question.stem, fontSize = 15.sp, lineHeight = 21.sp, color = AngPalette.Ink)
                if (picked != null) {
                    Spacer(Modifier.height(18.dp))
                    val right = picked == question.answer
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glass(corner = 18.dp, fill = (if (right) AngPalette.Right else AngPalette.Wrong).copy(alpha = 0.10f))
                            .padding(16.dp),
                    ) {
                        Text(
                            if (right) "Correct ♥" else "Not quite: it's ${Letters[question.answer]}",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (right) AngPalette.Right else AngPalette.Wrong,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(question.explanation, fontSize = 13.sp, lineHeight = 18.sp, color = AngPalette.Ink.copy(alpha = 0.88f))
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            // Right: the choices.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                question.choices.forEachIndexed { i, choice ->
                    val isAnswer = i == question.answer
                    val tint = when {
                        picked == null -> null
                        isAnswer -> AngPalette.Right
                        i == picked -> AngPalette.Wrong
                        else -> null
                    }
                    FocusCard(
                        onClick = { if (picked == null) onPick(i) },
                        container = tint?.copy(alpha = 0.22f) ?: AngPalette.Glass,
                        focusedContainer = tint?.copy(alpha = 0.32f) ?: AngPalette.GlassFocused,
                        edge = AngPalette.Edge,
                        corner = 16.dp,
                        focusedScale = 1.03f,
                        modifier = (if (i == 0) Modifier.focusRequester(firstChoice) else Modifier).fillMaxWidth().height(58.dp),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(28.dp).background(tint ?: Color.White.copy(alpha = 0.14f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(Letters[i], fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(choice, fontSize = 14.sp, lineHeight = 18.sp, color = AngPalette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FocusCard(
                        onClick = onDismiss,
                        container = AngPalette.Pink.copy(alpha = if (answered) 0.85f else 0.0f),
                        focusedContainer = AngPalette.Pink,
                        edge = if (answered) null else AngPalette.Edge,
                        corner = 24.dp,
                        focusedScale = 1.05f,
                        modifier = Modifier.focusRequester(done).width(150.dp).height(46.dp),
                    ) {
                        Text(if (answered) "Done" else "Later", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                    Spacer(Modifier.width(16.dp))
                    if (stats.answered > 0) {
                        Text(
                            "${stats.correct}/${stats.answered} correct" + if (stats.streak > 0) "  ·  🔥 ${stats.streak}" else "",
                            fontSize = 13.sp, color = AngPalette.InkSoft,
                        )
                    }
                }
            }
        }
    }
}
