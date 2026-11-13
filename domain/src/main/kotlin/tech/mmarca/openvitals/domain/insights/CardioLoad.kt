package tech.mmarca.openvitals.domain.insights

import tech.mmarca.openvitals.domain.model.DailySteps
import tech.mmarca.openvitals.domain.model.HeartRateSample
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.roundToInt
import tech.mmarca.openvitals.domain.preferences.BiologicalSex

private const val MinimumTrimpMinutes = 5.0
private const val GoodHeartRateCoverageMinutes = 10.0
private const val GoodHeartRateCoverageRatio = 0.6
private const val ActiveHeartRateReserveThreshold = 0.3
private const val MinimumMovementFallbackLoad = 0.25

enum class CardioLoadConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NO_DATA,
}

enum class CardioLoadMethod {
    TRIMP_ACTIVITY_WINDOWS,
    TRIMP_ELEVATED_HEART_RATE,
    MOVEMENT_FALLBACK,
    NO_DATA,
}

data class CardioLoadEstimate(
    val score: Int = 0,
    val confidence: CardioLoadConfidence = CardioLoadConfidence.NO_DATA,
    val method: CardioLoadMethod = CardioLoadMethod.NO_DATA,
    val trimpScore: Double? = null,
    val coveredMinutes: Double = 0.0,
    val expectedMinutes: Double = 0.0,
    val restingHeartRateBpm: Long? = null,
    val restingHeartRateObserved: Boolean = false,
    val maxHeartRateBpm: Long? = null,
    val maxHeartRateObserved: Boolean = false,
    val heartRateSampleCount: Int = 0,
    val activityWindowCount: Int = 0,
    val activityWindowMinutes: Double = 0.0,
    val movementFallbackScore: Int = 0,
) {
    /** Scored from heart rate, as opposed to the movement fallback's steps. */
    val usedTrimp: Boolean
        get() = score > 0 && (
            method == CardioLoadMethod.TRIMP_ACTIVITY_WINDOWS ||
                method == CardioLoadMethod.TRIMP_ELEVATED_HEART_RATE
            )

    companion object {
        val NoData = CardioLoadEstimate()
    }
}

data class CardioLoadTimeWindow(
    val start: Instant,
    val end: Instant,
) {
    val durationMinutes: Double
        get() = Duration.between(start, end).seconds.coerceAtLeast(0L).toDouble() / 60.0
}

private data class TrimpResult(
    val score: Double,
    val coveredMinutes: Double,
    val expectedMinutes: Double,
) {
    val hasGoodCoverage: Boolean
        get() = coveredMinutes >= GoodHeartRateCoverageMinutes &&
            (expectedMinutes <= 0.0 || coveredMinutes / expectedMinutes >= GoodHeartRateCoverageRatio)
}

fun calculateCardioLoad(
    steps: DailySteps?,
    samples: List<HeartRateSample>,
    restingHeartRate: Long?,
    baselineRestingHeartRate: Long?,
    observedMaxHeartRate: Long?,
    activityWindows: List<CardioLoadTimeWindow>,
    ageYears: Int? = null,
    explicitMaxHeartRate: Long? = null,
    sex: BiologicalSex? = null,
): CardioLoadEstimate {
    val fallback = movementFallbackCardioLoad(steps)
    val resting = restingHeartRate ?: baselineRestingHeartRate ?: samples.estimatedRestingHeartRate()
    val highestObserved = listOfNotNull(observedMaxHeartRate, samples.maxOfOrNull { it.beatsPerMinute }).maxOrNull()
    val maxHeartRate = resting?.let {
        dailyMaxHeartRate(
            restingHeartRate = it,
            observedMaxHeartRate = highestObserved,
            ageYears = ageYears,
            sex = sex,
            explicitMaxHeartRate = explicitMaxHeartRate,
        )
    }
    val trimp = if (resting != null && maxHeartRate != null) {
        calculateTrimp(
            samples = samples,
            restingHeartRate = resting,
            maxHeartRate = maxHeartRate.bpm,
            activityWindows = activityWindows,
            sex = sex,
        )
    } else {
        null
    }

    val activityWindowMinutes = activityWindows.sumOf { it.durationMinutes }
    if (trimp != null && trimp.coveredMinutes >= MinimumTrimpMinutes && trimp.score > 0.0) {
        val confidence = when {
            trimp.hasGoodCoverage && restingHeartRate != null && maxHeartRate?.isMeasured == true ->
                CardioLoadConfidence.HIGH
            trimp.hasGoodCoverage -> CardioLoadConfidence.MEDIUM
            else -> CardioLoadConfidence.LOW
        }
        return CardioLoadEstimate(
            score = trimp.score.roundToInt().coerceAtLeast(1),
            confidence = confidence,
            method = if (activityWindows.isNotEmpty()) {
                CardioLoadMethod.TRIMP_ACTIVITY_WINDOWS
            } else {
                CardioLoadMethod.TRIMP_ELEVATED_HEART_RATE
            },
            trimpScore = trimp.score,
            coveredMinutes = trimp.coveredMinutes,
            expectedMinutes = trimp.expectedMinutes,
            restingHeartRateBpm = resting,
            restingHeartRateObserved = restingHeartRate != null,
            maxHeartRateBpm = maxHeartRate?.bpm,
            maxHeartRateObserved = maxHeartRate?.isMeasured == true,
            heartRateSampleCount = samples.size,
            activityWindowCount = activityWindows.size,
            activityWindowMinutes = activityWindowMinutes,
            movementFallbackScore = fallback,
        )
    }

    return when {
        fallback > 0 -> CardioLoadEstimate(
            score = fallback,
            confidence = CardioLoadConfidence.LOW,
            method = CardioLoadMethod.MOVEMENT_FALLBACK,
            trimpScore = trimp?.score,
            coveredMinutes = trimp?.coveredMinutes ?: 0.0,
            expectedMinutes = activityWindowMinutes.takeIf { it > 0.0 }
                ?: trimp?.expectedMinutes
                ?: 0.0,
            restingHeartRateBpm = resting,
            restingHeartRateObserved = restingHeartRate != null,
            maxHeartRateBpm = maxHeartRate?.bpm,
            maxHeartRateObserved = maxHeartRate?.isMeasured == true,
            heartRateSampleCount = samples.size,
            activityWindowCount = activityWindows.size,
            activityWindowMinutes = activityWindowMinutes,
            movementFallbackScore = fallback,
        )
        else -> CardioLoadEstimate.NoData
    }
}

private fun calculateTrimp(
    samples: List<HeartRateSample>,
    restingHeartRate: Long,
    maxHeartRate: Long,
    activityWindows: List<CardioLoadTimeWindow>,
    sex: BiologicalSex?,
): TrimpResult? {
    val sortedSamples = samples
        .sortedBy { it.time }
        .distinctBy { it.time }
    if (sortedSamples.size < 2 || maxHeartRate <= restingHeartRate) return null

    var score = 0.0
    var coveredMinutes = 0.0
    val expectedMinutes = activityWindows
        .sumOf { it.durationMinutes }
        .takeIf { activityWindows.isNotEmpty() }

    sortedSamples.zipWithNext().forEach { (start, end) ->
        val interval = CardioLoadTimeWindow(start.time, end.time)
        val rawMinutes = interval.durationMinutes
        if (rawMinutes <= 0.0 || rawMinutes > MaxHeartRateSampleGapMinutes) return@forEach

        val intervalMinutes = if (activityWindows.isNotEmpty()) {
            activityWindows.sumOf { interval.overlapMinutes(it) }
        } else {
            rawMinutes
        }
        if (intervalMinutes <= 0.0) return@forEach

        val averageBpm = (start.beatsPerMinute + end.beatsPerMinute) / 2.0
        val heartRateReserve = ((averageBpm - restingHeartRate) / (maxHeartRate - restingHeartRate).toDouble())
            .coerceIn(0.0, 1.0)
        if (activityWindows.isEmpty() && heartRateReserve < ActiveHeartRateReserveThreshold) {
            return@forEach
        }

        coveredMinutes += intervalMinutes
        score += intervalMinutes * heartRateReserve * banisterWeighting(sex, heartRateReserve)
    }

    if (coveredMinutes <= 0.0) return null
    return TrimpResult(
        score = score,
        coveredMinutes = coveredMinutes,
        expectedMinutes = expectedMinutes ?: coveredMinutes,
    )
}

/**
 * Banister's TRIMP weighting, `a · e^(b · ΔHR)` with ΔHR the fraction of heart
 * rate reserve, which tracks how blood lactate climbs with effort: a = 0.64,
 * b = 1.92 for men, a = 0.86, b = 1.67 for women. Morton, Fitz-Clarke and
 * Banister, "Modeling human performance in running", J Appl Physiol
 * 1990;69(3):1171–1177, doi:10.1152/jappl.1990.69.3.1171; the constants as
 * restated in doi:10.2147/CIA.S211568. Without a recorded sex, the mean of
 * the men's and women's weighting: an app choice, the paper gives none.
 */
const val BanisterTrimpUrl = "https://pmc.ncbi.nlm.nih.gov/articles/PMC6561225/"
const val TrainingLoadReviewUrl = "https://pmc.ncbi.nlm.nih.gov/articles/PMC4213373/"

private fun banisterWeighting(sex: BiologicalSex?, heartRateReserve: Double): Double {
    val men = 0.64 * exp(1.92 * heartRateReserve)
    val women = 0.86 * exp(1.67 * heartRateReserve)
    return when (sex) {
        BiologicalSex.MALE -> men
        BiologicalSex.FEMALE -> women
        null -> (men + women) / 2.0
    }
}

/** Days without usable heart rate: the highest of steps, distance and active energy, each divided by a fixed divisor. Divisors: app heuristic, no published source. */
private fun movementFallbackCardioLoad(steps: DailySteps?): Int {
    steps ?: return 0
    val rawLoad = maxOf(
        steps.steps.toDouble() / 3_000.0,
        steps.distanceMeters / 1_500.0,
        steps.activeCaloriesKcal.orZero() / 75.0,
    )
    return if (rawLoad >= MinimumMovementFallbackLoad) {
        rawLoad.roundToInt().coerceAtLeast(1)
    } else {
        0
    }
}

internal fun CardioLoadTimeWindow.overlapMinutes(other: CardioLoadTimeWindow): Double =
    overlapMinutes(start, end, other.start, other.end)

private fun Double?.orZero(): Double = this ?: 0.0
