package tech.mmarca.openvitals.sharing.source

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import tech.mmarca.healthcalculations.model.DatePeriod
import tech.mmarca.openvitals.data.repository.BodyEnergyTimelineStore
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.openvitals.data.repository.contract.ActivitySplitPreferences
import tech.mmarca.openvitals.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openvitals.data.repository.contract.BodyRepository
import tech.mmarca.openvitals.data.repository.contract.CaffeineModelPreferences
import tech.mmarca.openvitals.data.repository.contract.CaffeineRepository
import tech.mmarca.openvitals.data.repository.contract.CycleJournalRepository
import tech.mmarca.openvitals.data.repository.contract.CyclePreferences
import tech.mmarca.openvitals.data.repository.contract.CycleRepository
import tech.mmarca.openvitals.data.repository.contract.HeartRepository
import tech.mmarca.openvitals.data.repository.contract.loadObservedMaxHeartRate
import tech.mmarca.openvitals.data.repository.contract.PillIntakeRepository
import tech.mmarca.openvitals.data.repository.contract.SleepWindowPreferences
import tech.mmarca.healthcalculations.insights.buildActivitySplits
import tech.mmarca.healthcalculations.insights.calculateHeartRateRecovery
import tech.mmarca.healthcalculations.insights.heartRateRecoveryWindowFor
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseRouteStatus
import tech.mmarca.healthcalculations.model.movingDurationMs
import tech.mmarca.openvitals.domain.preferences.ActivitySplitDistance
import tech.mmarca.openvitals.healthconnect.HealthConnectManager
import tech.mmarca.openvitals.sharing.contract.DataSharingGroup
import tech.mmarca.openvitals.sharing.contract.DataSharingOrigin

/**
 * What OpenVitals stores or works out itself. Room data (Body Energy, the
 * journal, pills) needs no Health Connect permission; a value built from
 * Health Connect data names what it requires.
 */
internal class OpenVitalsReads @Inject constructor(
    private val hc: HealthConnectManager,
    private val heartRepository: HeartRepository,
    private val bodyRepository: BodyRepository,
    private val activityRepository: ActivityRepository,
    private val cycleRepository: CycleRepository,
    private val cycleJournalRepository: CycleJournalRepository,
    private val pillIntakeRepository: PillIntakeRepository,
    private val caffeineRepository: CaffeineRepository,
    private val bodyEnergyStore: BodyEnergyTimelineStore,
    private val sleepWindowPreferences: SleepWindowPreferences,
    private val bodyProfilePreferences: BodyProfilePreferences,
    private val cyclePreferences: CyclePreferences,
    private val caffeinePreferences: CaffeineModelPreferences,
    private val splitPreferences: ActivitySplitPreferences,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun daily(group: DataSharingGroup, start: LocalDate, end: LocalDate, granted: Set<String>): List<SourceRead<DailyRow>> =
        when (group) {
            DataSharingGroup.SLEEP -> listOf(sleepScores(start, end, granted))
            DataSharingGroup.HEART -> listOf(maxHeartRate(end, granted))
            DataSharingGroup.BODY -> listOf(bodyDerived(start, end, granted))
            DataSharingGroup.RECOVERY -> listOf(bodyEnergyDays(start, end))
            DataSharingGroup.CYCLE -> listOf(
                SourceRead(JournalKeys, emptySet()) { journalDailyRows(cycleJournalRepository.entries(start, end)) },
                SourceRead(setOf("pill_taken"), emptySet()) { pillDailyRows(pillIntakeRepository.takenDays(start, end)) },
                cycleDerived(start, end),
            )
            else -> emptyList()
        }

    fun samples(group: DataSharingGroup, start: Instant, end: Instant): List<SourceRead<SampleRow>> = when (group) {
        DataSharingGroup.RECOVERY -> listOf(bodyEnergyBuckets(start, end))
        DataSharingGroup.NUTRITION -> listOf(
            SourceRead(setOf("caffeine_active"), setOf(readPermission(NutritionRecord::class))) {
                val entries = caffeineRepository.loadCaffeineData(
                    DatePeriod(start.atZone(zone).toLocalDate(), end.atZone(zone).toLocalDate()),
                ).entries
                caffeineCurveRows(entries, caffeinePreferences.caffeinePreferences(), start, end)
            },
        )
        else -> emptyList()
    }

    /** A workout's figures that OpenVitals works out: moving time, maxima and the recovery drop. */
    fun workoutSummary(id: String, granted: Set<String>): SourceRead<SummaryRow> =
        SourceRead(WorkoutExtraKeys, setOf(readPermission(ExerciseSessionRecord::class))) {
            val workout = activityRepository.loadWorkout(id) ?: return@SourceRead emptyList()
            val movingMs = workout.movingDurationMs()
            val distance = workout.totalDistanceMeters
            listOfNotNull(
                SummaryRow("moving_time", movingMs / MillisPerMinute),
                distance?.takeIf { movingMs > 0 }?.let { SummaryRow("moving_speed_avg", it / (movingMs / 1_000.0)) },
                maxSpeed(workout, granted)?.let { SummaryRow("speed_max", it) },
                maxHeartRate(workout, granted)?.let { SummaryRow("heart_rate_max", it) },
                recoveryDrop(workout, granted)?.let { SummaryRow("hrr_60s", it) },
            )
        }

    /** Splits as the workout screen cuts them: device laps, else the route, else speed, else an estimate. */
    fun splits(id: String, granted: Set<String>): SourceRead<SplitRow> =
        SourceRead(emptySet(), setOf(readPermission(ExerciseSessionRecord::class))) {
            val workout = activityRepository.loadWorkout(id) ?: return@SourceRead emptyList()
            val route = workout.route.takeIf {
                it.status == ExerciseRouteStatus.DATA && HealthPermission.PERMISSION_READ_EXERCISE_ROUTES in granted
            }?.points.orEmpty()
            val speed = if (readPermission(SpeedRecord::class) in granted) {
                activityRepository.loadSpeedSamples(workout.startTime, workout.endTime)
            } else {
                emptyList()
            }
            val heartRate = if (readPermission(HeartRateRecord::class) in granted) {
                heartRepository.loadHeartRateSamples(workout.startTime, workout.endTime)
            } else {
                emptyList()
            }
            val splits = buildActivitySplits(
                workout = workout,
                routePoints = route,
                speedSamples = speed,
                heartRateSamples = heartRate,
                splitDistanceMeters = ActivitySplitDistance.normalize(splitPreferences.activitySplitDistanceMeters),
            )
            splits.splits.map { split ->
                SplitRow(
                    index = split.index,
                    distanceMeters = split.distanceMeters,
                    durationSeconds = split.elapsedMs / 1_000.0,
                    paceSecondsPerKm = split.paceSecondsPerMeter?.let { it * 1_000.0 },
                    elevationGainMeters = split.elevationGainMeters,
                    heartRateAvg = split.averageHeartRateBpm,
                    source = splits.source.name.lowercase(),
                )
            }
        }

    private fun sleepScores(start: LocalDate, end: LocalDate, granted: Set<String>) = SourceRead(
        setOf("sleep_score", "sleep_efficiency", "waso", "sleep_midpoint"),
        setOf(readPermission(SleepSessionRecord::class)),
    ) {
        val window = sleepWindowPreferences.sleepWindow
        val profile = bodyProfilePreferences.bodyProfile()
        // The score looks at the week before; HRV only when OpenVitals may read it.
        val sessions = hc.readSleepData(start.minusDays(SleepLookbackDays), end, window).sessions
        val readsHrv = readPermission(HeartRateVariabilityRmssdRecord::class) in granted
        val hrvSamples = if (readsHrv) {
            hc.readHrvSamples(start.minusDays(1).atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant())
        } else {
            emptyList()
        }
        val dailyHrv = if (readsHrv) hc.readDailyHRV(start.minusDays(HrvBaselineDays), end.minusDays(1)) else emptyList()
        sleepScoreRows(sessions, hrvSamples, dailyHrv, start, end, window, zone, profile::ageYears, Instant.now())
    }

    /** The days the app last computed, read as stored. */
    private fun bodyEnergyDays(start: LocalDate, end: LocalDate) = SourceRead(
        setOf("body_energy_start", "body_energy_end", "body_energy_charged", "body_energy_drained"),
        emptySet(),
    ) {
        bodyEnergyStore.sharedDaysBetween(start, end).flatMap { day ->
            listOf(
                DailyRow(day.date, "body_energy_start", day.startScore, origin = DataSharingOrigin.OPENVITALS),
                DailyRow(day.date, "body_energy_end", day.endScore, origin = DataSharingOrigin.OPENVITALS),
                DailyRow(day.date, "body_energy_charged", day.charged, origin = DataSharingOrigin.OPENVITALS),
                DailyRow(day.date, "body_energy_drained", day.drained, origin = DataSharingOrigin.OPENVITALS),
            )
        }
    }

    private fun bodyEnergyBuckets(start: Instant, end: Instant) = SourceRead(setOf("body_energy"), emptySet()) {
        bodyEnergyStore.sharedScoresBetween(start, end, zone).map { (time, score) ->
            SampleRow(time, null, "body_energy", score, origin = DataSharingOrigin.OPENVITALS)
        }
    }

    /**
     * One row, for the range's last day: the observed maximum looks a year back, and a row for
     * every day of a long range would read a year of heart rate for each.
     */
    private fun maxHeartRate(end: LocalDate, granted: Set<String>) = SourceRead(
        setOf("max_heart_rate"),
        setOf(readPermission(HeartRateRecord::class)),
    ) {
        val day = minOf(end, LocalDate.now(zone))
        val observed = heartRepository.loadObservedMaxHeartRate(day.plusDays(1).atStartOfDay(zone).toInstant())
        val resting = if (readPermission(RestingHeartRateRecord::class) in granted) {
            heartRepository.loadDailyRestingHR(day, day).firstOrNull { it.date == day }?.bpm
        } else {
            null
        }
        listOfNotNull(maxHeartRateRow(day, observed, resting, bodyProfilePreferences.bodyProfile()))
    }

    private fun bodyDerived(start: LocalDate, end: LocalDate, granted: Set<String>) = SourceRead(
        setOf("bmi", "ffmi", "bmr_estimate"),
        emptySet(),
    ) {
        // The last weight before the range counts, so read a year back; heights change rarely.
        val weights = if (readPermission(WeightRecord::class) in granted) {
            bodyRepository.loadWeightEntries(start.minusYears(1), end).map { it.time to it.weightKg }
        } else {
            emptyList()
        }
        val readsHeight = readPermission(HeightRecord::class) in granted
        val heights = if (readsHeight) {
            bodyRepository.loadHeightEntries(start.minusYears(1), end).map { it.time to it.heightCm }
        } else {
            emptyList()
        }
        val fallbackHeight = if (readsHeight && heights.isEmpty()) bodyRepository.loadLatestHeight() else null
        val fats = if (readPermission(BodyFatRecord::class) in granted) {
            bodyRepository.loadBodyFatEntries(start.minusYears(1), end).map { it.time to it.percent }
        } else {
            emptyList()
        }
        bodyDerivedRows(weights, heights, fats, fallbackHeight, bodyProfilePreferences.bodyProfile(), start, end, zone)
    }

    private fun cycleDerived(start: LocalDate, end: LocalDate) = SourceRead(
        CycleDerivedKeys,
        setOf(readPermission(MenstruationFlowRecord::class)),
    ) {
        val inputs = cycleRepository.loadCycleInputs(end, historyFrom = start) ?: return@SourceRead emptyList()
        // A cycle can start well before the range; its temperatures and journal count from its start.
        val cycleLookback = start.minusDays(CycleReachDays)
        val journal = cycleJournalRepository.entries(cycleLookback, end).associateBy { it.date }
        val temperatures = cycleRepository.loadCycleData(cycleLookback, end).basalBodyTemperature
        val bodyProfile = bodyProfilePreferences.bodyProfile()
        val tracking = cyclePreferences.cycleTrackingProfile()
        cycleDerivedRows(inputs, start, end, { day -> tracking.resolved(bodyProfile, day) }, journal, temperatures, zone)
    }

    private suspend fun maxSpeed(workout: ExerciseData, granted: Set<String>): Double? =
        if (readPermission(SpeedRecord::class) in granted) {
            activityRepository.loadSpeedSamples(workout.startTime, workout.endTime).maxOfOrNull { it.metersPerSecond }
        } else {
            null
        }

    /** An exact maximum: the chart's samples are thinned for long workouts. */
    private suspend fun maxHeartRate(workout: ExerciseData, granted: Set<String>): Double? =
        if (readPermission(HeartRateRecord::class) in granted) {
            heartRepository.loadMaxHeartRate(workout.startTime, workout.endTime)?.toDouble()
        } else {
            null
        }

    /** As the workout screen measures it: the profile's resting and maximum, and the year's observed maximum. */
    private suspend fun recoveryDrop(workout: ExerciseData, granted: Set<String>): Double? {
        if (readPermission(HeartRateRecord::class) !in granted) return null
        val window = heartRateRecoveryWindowFor(workout) ?: return null
        val profile = bodyProfilePreferences.bodyProfile()
        return calculateHeartRateRecovery(
            recoveryStart = window.recoveryStart,
            samples = heartRepository.loadHeartRateSamples(window.readStart, window.readEnd),
            restingHeartRateBpm = profile.restingHeartRateBpm,
            ageYears = profile.ageYears(),
            observedMaxHeartRateBpm = heartRepository.loadObservedMaxHeartRate(workout.endTime)?.toInt(),
            explicitMaxHeartRateBpm = profile.maxHeartRateBpm,
            sex = profile.sex,
        ).headlineDropBpm?.toDouble()
    }

    private companion object {
        const val MillisPerMinute = 60_000.0
        const val SleepLookbackDays = 6L
        const val HrvBaselineDays = 28L

        /** Longer than any cycle the estimate accepts (90 days). */
        const val CycleReachDays = 100L

        val JournalKeys = setOf(
            "mood", "energy", "pain", "symptoms", "journal_cervical_sensation", "hcg_test", "bbt_disturbances",
        )
        val CycleDerivedKeys = setOf(
            "cycle_day", "cycle_phase", "next_period_start", "next_period_end", "cycle_length_avg",
            "period_length_avg", "thermal_shift",
        )
        val WorkoutExtraKeys = setOf("moving_time", "moving_speed_avg", "speed_max", "heart_rate_max", "hrr_60s")
    }
}
