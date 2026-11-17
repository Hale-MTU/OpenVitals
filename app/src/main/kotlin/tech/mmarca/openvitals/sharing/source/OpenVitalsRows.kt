package tech.mmarca.openvitals.sharing.source

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.healthcalculations.stats.medianOrNull
import tech.mmarca.healthcalculations.stats.timeBucketedAverageOrNull
import tech.mmarca.healthcalculations.cycle.BbtReading
import tech.mmarca.healthcalculations.cycle.CurrentCyclePhase
import tech.mmarca.healthcalculations.cycle.CycleInputs
import tech.mmarca.healthcalculations.cycle.CycleTrackingProfile
import tech.mmarca.healthcalculations.cycle.ThermalShiftCalculator
import tech.mmarca.healthcalculations.cycle.ThermalShiftResult
import tech.mmarca.healthcalculations.cycle.averagePeriodLengthDays
import tech.mmarca.healthcalculations.cycle.statisticsAsOf
import tech.mmarca.healthcalculations.insights.CaffeineInsightCalculator
import tech.mmarca.healthcalculations.insights.SleepScoreConfidence
import tech.mmarca.healthcalculations.insights.adjustedFatFreeMassIndex
import tech.mmarca.healthcalculations.insights.basalMetabolicRateKcal
import tech.mmarca.healthcalculations.insights.bodyMassIndex
import tech.mmarca.healthcalculations.insights.calculateSleepScoreForDate
import tech.mmarca.healthcalculations.insights.overnightHrvInput
import tech.mmarca.healthcalculations.insights.dailyMaxHeartRate
import tech.mmarca.healthcalculations.model.BasalBodyTemperatureEntry
import tech.mmarca.healthcalculations.model.CaffeineEntry
import tech.mmarca.healthcalculations.model.CycleJournalEntry
import tech.mmarca.healthcalculations.model.DailyHrv
import tech.mmarca.healthcalculations.model.HeartRateSummary
import tech.mmarca.healthcalculations.model.HrvSample
import tech.mmarca.healthcalculations.model.SleepData
import tech.mmarca.healthcalculations.model.sleepRangeStartFor
import tech.mmarca.healthcalculations.model.sleepSessionsForRange
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.healthcalculations.preferences.CaffeinePreferences
import tech.mmarca.healthcalculations.preferences.SleepWindow
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin

/*
 * Pure builders for what OpenVitals stores or works out itself. Each takes
 * what the reads fetched and returns rows, so the JVM tests reach them.
 */

private val Own = DataSharingOrigin.OPENVITALS

private fun days(start: LocalDate, end: LocalDate): Sequence<LocalDate> =
    generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }

// Sleep

/**
 * Each day's sleep score as the dashboard shows it: the nights of the week
 * before, the time-weighted HRV from the night window's start to the day's
 * end, and the median daily HRV of the 28 days before as the baseline.
 */
internal fun sleepScoreRows(
    sessions: List<SleepData>,
    hrvSamples: List<HrvSample>,
    dailyHrv: List<DailyHrv>,
    start: LocalDate,
    end: LocalDate,
    sleepWindow: SleepWindow,
    zone: ZoneId,
    ageFor: (LocalDate) -> Int?,
    now: Instant,
): List<DailyRow> {
    val hrvByDay = dailyHrv.filter { it.rmssdMs > 0.0 }.associate { it.date to it.rmssdMs }
    val sortedHrv = hrvSamples.sortedBy { it.time }
    return days(start, end).flatMap { day ->
        val nightStart = sleepRangeStartFor(day, sleepWindow).atZone(zone).toInstant()
        val nightEnd = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
        val nightHrv = sortedHrv.between(nightStart, nightEnd) { it.time }
            .timeBucketedAverageOrNull(time = { it.time }, value = { it.rmssdMs })
        val baseline = (1L..BaselineDays).mapNotNull { hrvByDay[day.minusDays(it)] }.medianOrNull()
        val score = calculateSleepScoreForDate(
            selectedDate = day,
            sessions = sessions,
            sleepWindow = sleepWindow,
            zone = zone,
            ageYears = ageFor(day),
            overnightHrv = overnightHrvInput(nightHrv, baseline),
        )
        if (score.confidence == SleepScoreConfidence.NO_DATA) return@flatMap emptySequence()
        val night = sleepSessionsForRange(sessions, day, sleepWindow, zone)
        val midpoint = night.takeIf { it.isNotEmpty() }?.let { sessionsOfNight ->
            val inBed = sessionsOfNight.minOf { it.startTime }
            val outOfBed = sessionsOfNight.maxOf { it.endTime }
            val local = inBed.plus(Duration.between(inBed, outOfBed).dividedBy(2)).atZone(zone)
            (local.hour * 60 + local.minute).toDouble()
        }
        sequenceOf(
            DailyRow(day, "sleep_score", score.score.toDouble(), origin = Own),
            DailyRow(day, "sleep_efficiency", score.sleepEfficiencyPercent, origin = Own),
            DailyRow(day, "waso", score.wakeAfterSleepOnsetMinutes, origin = Own),
        ) + listOfNotNull(midpoint?.let { DailyRow(day, "sleep_midpoint", it, origin = Own) })
    }.toList()
}

private const val BaselineDays = 28L

/** The items of a list sorted by [time] that fall in `[from, to)`. */
private inline fun <T> List<T>.between(from: Instant, to: Instant, crossinline time: (T) -> Instant): List<T> {
    if (!from.isBefore(to)) return emptyList()
    val first = binarySearch { if (time(it).isBefore(from)) -1 else 1 }.let { -(it + 1) }
    val last = binarySearch { if (time(it).isBefore(to)) -1 else 1 }.let { -(it + 1) }
    return subList(first, last)
}

// Body

/**
 * BMI, FFMI and the BMR estimate as of each day: the last weight, height and
 * body fat measured on or before it. A day before any weight gets nothing.
 */
internal fun bodyDerivedRows(
    weights: List<Pair<Instant, Double>>,
    heightsCm: List<Pair<Instant, Double>>,
    bodyFats: List<Pair<Instant, Double>>,
    fallbackHeightCm: Double?,
    profile: BodyProfile,
    start: LocalDate,
    end: LocalDate,
    zone: ZoneId,
): List<DailyRow> {
    fun asOf(values: List<Pair<Instant, Double>>): (LocalDate) -> Double? {
        val byDay = values.sortedBy { it.first }.associate { it.first.atZone(zone).toLocalDate() to it.second }.toSortedMap()
        return { day -> byDay.headMap(day.plusDays(1)).let { if (it.isEmpty()) null else it[it.lastKey()] } }
    }
    val weightOn = asOf(weights)
    val heightOn = asOf(heightsCm)
    val fatOn = asOf(bodyFats)
    return days(start, end).flatMap { day ->
        val weight = weightOn(day)
        val height = heightOn(day) ?: fallbackHeightCm
        val bmr = profile.copy(weightKg = weight ?: profile.weightKg, heightCm = height ?: profile.heightCm)
            .normalized(day)
            .basalMetabolicRateKcal(day)
        listOfNotNull(
            bodyMassIndex(weight, height)?.let { DailyRow(day, "bmi", it, origin = Own) },
            adjustedFatFreeMassIndex(weight, height, fatOn(day))?.let { DailyRow(day, "ffmi", it, origin = Own) },
            bmr?.let { DailyRow(day, "bmr_estimate", it, origin = Own) },
        ).asSequence()
    }.toList()
}

// Heart

/**
 * The maximum heart rate the load estimates use on [day]: a stated one, else
 * the highest of the year before when it clears the trust bar, else an
 * estimate from age and sex. Nothing without a resting heart rate, unless a
 * maximum is stated.
 */
internal fun maxHeartRateRow(
    day: LocalDate,
    observedMaxBpm: Long?,
    measuredRestingBpm: Long?,
    profile: BodyProfile,
): DailyRow? {
    val normalized = profile.normalized(day)
    val explicit = normalized.maxHeartRateBpm?.toLong()
    val resting = normalized.restingHeartRateBpm?.toLong() ?: measuredRestingBpm
    if (resting == null && explicit == null) return null
    return dailyMaxHeartRate(
        restingHeartRate = resting ?: 0L,
        observedMaxHeartRate = observedMaxBpm,
        ageYears = normalized.ageYears(day),
        sex = normalized.sex,
        explicitMaxHeartRate = explicit,
    )?.let { DailyRow(day, "max_heart_rate", it.bpm.toDouble(), origin = Own) }
}

// Nutrition

/** Active caffeine every 15 minutes in `[start, end)`. Moments with none left are skipped. */
internal fun caffeineCurveRows(
    entries: List<CaffeineEntry>,
    preferences: CaffeinePreferences,
    start: Instant,
    end: Instant,
): List<SampleRow> {
    if (entries.isEmpty()) return emptyList()
    val normalized = preferences.normalized()
    return generateSequence(start) { it.plus(CaffeineStep) }
        .takeWhile { it.isBefore(end) }
        .mapNotNull { at ->
            CaffeineInsightCalculator.activeCaffeineMg(entries, at, normalized)
                .takeIf { it >= MinimumActiveCaffeineMg }
                ?.let { SampleRow(at, null, "caffeine_active", it, origin = Own) }
        }
        .toList()
}

private val CaffeineStep: Duration = Duration.ofMinutes(15)
private const val MinimumActiveCaffeineMg = 0.5

// Cycle

/**
 * The cycle as it stood on each day: nothing recorded later counts. The thermal
 * shift is the first high day of a rise confirmed by then.
 */
internal fun cycleDerivedRows(
    inputs: CycleInputs,
    start: LocalDate,
    end: LocalDate,
    profileFor: (LocalDate) -> CycleTrackingProfile,
    journal: Map<LocalDate, CycleJournalEntry>,
    basalTemperatures: List<BasalBodyTemperatureEntry>,
    zone: ZoneId,
): List<DailyRow> {
    val temperatureByDay = basalTemperatures
        .groupBy { it.time.atZone(zone).toLocalDate() }
        .mapValues { (_, entries) -> entries.maxBy { it.time }.temperatureCelsius }
        .toSortedMap()
    return days(start, end).flatMap { day ->
        val stats = inputs.statisticsAsOf(day, profileFor(day), journal[day]?.bleedingNone == true)
        val estimate = stats.estimate.estimateOrNull
        val periodLength = stats.averagePeriodLengthDays
        val shift = stats.currentCycle?.let { cycle ->
            val readings = temperatureByDay.subMap(cycle.startDate, day.plusDays(1)).map { (date, celsius) ->
                BbtReading(date, celsius, disturbed = journal[date]?.bbtDisturbances.orEmpty().isNotEmpty())
            }
            (ThermalShiftCalculator.evaluateCycle(cycle.startDate, readings) as? ThermalShiftResult.Confirmed)?.firstHighDay
        }
        listOfNotNull(
            stats.currentCycleDay?.let { DailyRow(day, "cycle_day", it.toDouble(), origin = Own) },
            (stats.currentPhase as? CurrentCyclePhase.Available)?.let {
                DailyRow(day, "cycle_phase", text = it.phase.name.lowercase(), origin = Own)
            },
            estimate?.let { DailyRow(day, "next_period_start", text = it.earliestDate.toString(), origin = Own) },
            estimate?.let { DailyRow(day, "next_period_end", text = it.latestDate.toString(), origin = Own) },
            stats.averageCycleLengthDays?.let { DailyRow(day, "cycle_length_avg", it, origin = Own) },
            periodLength?.let { DailyRow(day, "period_length_avg", it, origin = Own) },
            shift?.let { DailyRow(day, "thermal_shift", text = it.toString(), origin = Own) },
        ).asSequence()
    }.toList()
}

