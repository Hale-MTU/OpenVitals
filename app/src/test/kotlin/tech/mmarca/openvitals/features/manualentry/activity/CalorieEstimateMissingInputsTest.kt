package tech.mmarca.openvitals.features.manualentry.activity

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.insights.BmrInput

class CalorieEstimateMissingInputsTest {

    private val running = DefaultActivityEntryTypes.first { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING }

    @Test
    fun `a complete profile misses nothing`() {
        assertTrue(calorieEstimateMissingInputs(running, TestCalorieContext).isEmpty())
    }

    @Test
    fun `every field the estimate or the resting rate reads is named`() {
        val empty = TestCalorieContext.copy(
            profile = TestCalorieContext.profile.copy(birthYear = null, weightKg = null, heightCm = null, sex = null),
        )

        assertEquals(
            setOf(BmrInput.SEX, BmrInput.BIRTH_YEAR, BmrInput.WEIGHT, BmrInput.HEIGHT),
            calorieEstimateMissingInputs(running, empty),
        )
    }

    @Test
    fun `a profile with a weight still names the other fields`() {
        val partial = TestCalorieContext.copy(profile = TestCalorieContext.profile.copy(heightCm = null))

        assertEquals(setOf(BmrInput.HEIGHT), calorieEstimateMissingInputs(running, partial))
    }
}
