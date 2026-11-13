package com.mytv.launcher.ang

import com.mytv.launcher.ui.FoodWheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AngExtrasTest {
    @Test
    fun everyQuestionIsWellFormed() {
        assertTrue(MedQuestions.all.size >= 40)
        for (q in MedQuestions.all) {
            assertEquals(q.stem, 5, q.choices.size)
            assertTrue(q.stem, q.answer in q.choices.indices)
            assertEquals("duplicate choices: ${q.stem}", q.choices.size, q.choices.toSet().size)
            assertTrue(q.explanation.isNotBlank())
        }
        assertEquals(MedQuestions.all.size, MedQuestions.all.map { it.stem }.toSet().size)
    }

    @Test
    fun aNewQuestionEachDayAndNoRepeatsUntilTheBankRunsOut() {
        val start = LocalDate.of(2026, 11, 13)
        val n = MedQuestions.all.size
        val seen = (0 until n).map { MedQuestions.forDay(start.plusDays(it.toLong())) }.toSet()
        assertEquals(n, seen.size)
        assertEquals(MedQuestions.forDay(start), MedQuestions.forDay(start))
    }

    @Test
    fun wheelHas15To20Options() {
        assertTrue(FoodWheel.foods.size in 15..20)
        assertEquals(FoodWheel.foods.size, FoodWheel.foods.map { it.name }.toSet().size)
    }

    @Test
    fun wheelStopsOnTheChosenSlice() {
        var from = 0f
        for (winner in FoodWheel.foods.indices) {
            for (jitter in listOf(0f, 0.5f, 1f)) {
                val target = FoodWheel.targetFor(from, winner, jitter)
                assertTrue(target - from >= 7 * 360f)
                assertEquals(winner, FoodWheel.indexAt(target))
                from = target
            }
        }
    }
}
