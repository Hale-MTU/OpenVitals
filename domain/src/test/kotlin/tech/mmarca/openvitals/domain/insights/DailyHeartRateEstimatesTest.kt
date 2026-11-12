package tech.mmarca.openvitals.domain.insights

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openvitals.domain.model.HeartRateSample
import tech.mmarca.openvitals.domain.preferences.BiologicalSex

class DailyHeartRateEstimatesTest {

    @Test
    fun `an entered maximum above resting wins`() {
        assertEquals(
            DailyMaxHeartRate(185, isMeasured = true),
            dailyMaxHeartRate(60, observedMaxHeartRate = 195, ageYears = 40, sex = null, explicitMaxHeartRate = 185),
        )
    }

    @Test
    fun `an entered maximum at or below resting is ignored`() {
        assertEquals(
            DailyMaxHeartRate(180, isMeasured = false),
            dailyMaxHeartRate(60, observedMaxHeartRate = 120, ageYears = 40, sex = null, explicitMaxHeartRate = 60),
        )
    }

    @Test
    fun `a believable observed maximum beats the age`() {
        assertEquals(
            DailyMaxHeartRate(190, isMeasured = true),
            dailyMaxHeartRate(60, observedMaxHeartRate = 190, ageYears = 40, sex = null, explicitMaxHeartRate = null),
        )
    }

    @Test
    fun `an easy day falls back to the age formula`() {
        // 140 is below 150, so the age formula decides: 208 - 0.7 x 40 = 180.
        assertEquals(
            DailyMaxHeartRate(180, isMeasured = false),
            dailyMaxHeartRate(60, observedMaxHeartRate = 140, ageYears = 40, sex = null, explicitMaxHeartRate = null),
        )
        // Women: 206 - 0.88 x 40 = 170.8, rounded to 171.
        assertEquals(
            171L,
            dailyMaxHeartRate(60, 140, ageYears = 40, sex = BiologicalSex.FEMALE, explicitMaxHeartRate = null)?.bpm,
        )
    }

    @Test
    fun `without an age the higher of observed plus 10 and resting plus 70 is used`() {
        assertEquals(150L, dailyMaxHeartRate(60, 140, ageYears = null, sex = null, explicitMaxHeartRate = null)?.bpm)
        assertEquals(140L, dailyMaxHeartRate(70, 100, ageYears = null, sex = null, explicitMaxHeartRate = null)?.bpm)
    }

    @Test
    fun `nothing entered and nothing observed gives no maximum`() {
        assertNull(dailyMaxHeartRate(60, observedMaxHeartRate = null, ageYears = 40, sex = null, explicitMaxHeartRate = null))
    }

    @Test
    fun `the resting estimate is the 10th percentile kept between 40 and 100`() {
        fun samples(vararg bpm: Long) = bpm.mapIndexed { i, value ->
            HeartRateSample(Instant.ofEpochSecond(i * 60L), value, "test")
        }
        // 11 samples: index round(10 x 0.1) = 1, the second lowest.
        assertEquals(60L, samples(90, 52, 70, 110, 60, 80, 75, 65, 85, 95, 100).estimatedRestingHeartRate())
        assertEquals(40L, samples(30, 35).estimatedRestingHeartRate())
        assertNull(emptyList<HeartRateSample>().estimatedRestingHeartRate())
    }

    @Test
    fun `overlap is the shared minutes, zero when apart`() {
        val t = { minutes: Long -> Instant.ofEpochSecond(minutes * 60) }
        assertEquals(30.0, overlapMinutes(t(0), t(60), t(30), t(90)), 0.0)
        assertEquals(0.0, overlapMinutes(t(0), t(30), t(30), t(60)), 0.0)
    }
}
