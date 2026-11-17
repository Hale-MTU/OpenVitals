package tech.mmarca.openvitals.sharing.source

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test
import tech.mmarca.healthcalculations.cycle.CycleInputs
import tech.mmarca.healthcalculations.cycle.CycleTrackingProfile
import tech.mmarca.healthcalculations.insights.dailyMaxHeartRate
import tech.mmarca.healthcalculations.model.CaffeineEntry
import tech.mmarca.healthcalculations.model.CycleJournalEntry
import tech.mmarca.healthcalculations.model.CycleRecordValues
import tech.mmarca.healthcalculations.model.HcgTestResult
import tech.mmarca.healthcalculations.model.SleepData
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.healthcalculations.preferences.CaffeinePreferences
import tech.mmarca.healthcalculations.preferences.SleepWindow
import tech.mmarca.healthcalculations.cycle.CycleSymptom
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin

class OpenVitalsRowsTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 1)

    @Test
    fun `the maximum heart rate prefers a stated one, then a trusted observation, then an estimate`() {
        val stated = maxHeartRateRow(day, observedMaxBpm = 182, measuredRestingBpm = 55, BodyProfile(maxHeartRateBpm = 190))
        val observed = maxHeartRateRow(day, observedMaxBpm = 182, measuredRestingBpm = 55, BodyProfile())
        val estimated = maxHeartRateRow(day, observedMaxBpm = 120, measuredRestingBpm = 55, BodyProfile(birthYear = 1986))

        assertThat(stated?.value).isEqualTo(190.0)
        assertThat(observed?.value).isEqualTo(182.0)
        assertThat(estimated?.value).isEqualTo(
            dailyMaxHeartRate(55, 120, ageYears = 40, sex = null, explicitMaxHeartRate = null)?.bpm?.toDouble(),
        )
        assertThat(estimated?.origin).isEqualTo(DataSharingOrigin.OPENVITALS)
    }

    @Test
    fun `a day without resting heart rate or a stated maximum has no maximum`() {
        assertThat(maxHeartRateRow(day, observedMaxBpm = 182, measuredRestingBpm = null, BodyProfile())).isNull()
    }

    @Test
    fun `body values use the last measurement on or before each day`() {
        val weights = listOf(Instant.parse("2026-09-29T08:00:00Z") to 80.0, Instant.parse("2026-10-02T08:00:00Z") to 70.0)
        val heights = listOf(Instant.parse("2026-01-01T08:00:00Z") to 200.0)

        val rows = bodyDerivedRows(weights, heights, emptyList(), null, BodyProfile(), day, day.plusDays(1), zone)
        val bmi = rows.filter { it.metric == "bmi" }.associate { it.date to it.value }

        assertThat(bmi[day]).isEqualTo(20.0)
        assertThat(bmi[day.plusDays(1)]).isEqualTo(17.5)
        assertThat(rows.map { it.metric }).doesNotContain("ffmi")
    }

    @Test
    fun `no caffeine logged means no curve`() {
        val start = Instant.parse("2026-10-01T06:00:00Z")

        assertThat(caffeineCurveRows(emptyList(), CaffeinePreferences(), start, start.plusSeconds(7_200))).isEmpty()
    }

    @Test
    fun `a coffee shows up in the curve after it is drunk`() {
        val drunk = Instant.parse("2026-10-01T06:00:00Z")
        val coffee = CaffeineEntry(
            id = "c",
            startTime = drunk,
            endTime = drunk.plusSeconds(300),
            caffeineMg = 95.0,
            name = null,
            source = "app",
            mealType = 0,
        )

        val rows = caffeineCurveRows(listOf(coffee), CaffeinePreferences(), drunk, drunk.plusSeconds(7_200))

        assertThat(rows).isNotEmpty()
        assertThat(rows.map { it.metric }.toSet()).containsExactly("caffeine_active")
        assertThat(rows.maxOf { it.value!! }).isGreaterThan(10.0)
    }

    @Test
    fun `a past day does not see a later period`() {
        val first = LocalDate.of(2026, 8, 1)
        val second = first.plusDays(28)
        val flow = (0L..4L).flatMap { listOf(first.plusDays(it), second.plusDays(it)) }
            .associateWith { CycleRecordValues.FLOW_MEDIUM }
        val inputs = CycleInputs(flowDays = flow, spottingDays = emptySet(), exclusions = emptyMap())

        val rows = cycleDerivedRows(
            inputs = inputs,
            start = second.minusDays(1),
            end = second,
            profileFor = { CycleTrackingProfile() },
            journal = emptyMap(),
            basalTemperatures = emptyList(),
            zone = zone,
        ).filter { it.metric == "cycle_day" }.associate { it.date to it.value }

        assertThat(rows[second.minusDays(1)]).isEqualTo(28.0)
        assertThat(rows[second]).isEqualTo(1.0)
    }

    @Test
    fun `journal rows never carry notes`() {
        val entry = CycleJournalEntry(
            date = day,
            moodLevel = 4,
            symptoms = setOf(CycleSymptom.HEADACHE, CycleSymptom.CRAMPS),
            notes = "private",
            hcgTest = HcgTestResult.NEGATIVE,
        )

        val rows = journalDailyRows(listOf(entry)).associateBy { it.metric }

        assertThat(rows.keys).containsExactly("mood", "symptoms", "hcg_test")
        assertThat(rows.getValue("symptoms").text).isEqualTo("cramps,headache")
        assertThat(rows.getValue("hcg_test").text).isEqualTo("negative")
        assertThat(rows.values.map { it.text }).doesNotContain("private")
    }

    @Test
    fun `a night scores and reports its midpoint`() {
        val night = SleepData(
            id = "n",
            startTime = Instant.parse("2026-09-30T23:00:00Z"),
            endTime = Instant.parse("2026-10-01T07:00:00Z"),
            durationMs = 8 * 3_600_000L,
            source = "app",
        )

        val rows = sleepScoreRows(
            sessions = listOf(night),
            hrvSamples = emptyList(),
            dailyHrv = emptyList(),
            start = day,
            end = day,
            sleepWindow = SleepWindow.Default,
            zone = zone,
            ageFor = { null },
            now = Instant.parse("2026-10-02T00:00:00Z"),
        ).associateBy { it.metric }

        assertThat(rows.keys).containsAtLeast("sleep_score", "sleep_efficiency", "waso", "sleep_midpoint")
        assertThat(rows.getValue("sleep_midpoint").value).isEqualTo(180.0)
        assertThat(rows.getValue("sleep_score").value!!).isGreaterThan(0.0)
    }
}
