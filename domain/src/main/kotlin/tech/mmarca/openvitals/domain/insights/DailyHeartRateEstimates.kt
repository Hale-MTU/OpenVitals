package tech.mmarca.openvitals.domain.insights

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import tech.mmarca.openvitals.domain.model.HeartRateSample
import tech.mmarca.openvitals.domain.preferences.BiologicalSex

/*
 * The resting and maximum heart rate that cardio load, intensity minutes and
 * Body Energy use when no better value is known. One copy, so the three agree.
 */

/** A maximum heart rate. [isMeasured] is true for one the user entered or a believable observed one. */
internal data class DailyMaxHeartRate(val bpm: Long, val isMeasured: Boolean)

/**
 * The maximum heart rate for a day's estimates, in this order:
 * 1. the one the user entered, when it is above [restingHeartRate];
 * 2. [observedMaxHeartRate], when [isObservedMaxHeartRateTrustworthy];
 * 3. the age-predicted one, [ageMaxHeartRate];
 * 4. the observed maximum plus 10, or resting plus 70, whichever is higher.
 *    App heuristic, no published source.
 *
 * Null when there is neither an entered nor an observed maximum.
 */
internal fun dailyMaxHeartRate(
    restingHeartRate: Long,
    observedMaxHeartRate: Long?,
    ageYears: Int?,
    sex: BiologicalSex?,
    explicitMaxHeartRate: Long?,
): DailyMaxHeartRate? {
    if (explicitMaxHeartRate != null && explicitMaxHeartRate > restingHeartRate) {
        return DailyMaxHeartRate(bpm = explicitMaxHeartRate, isMeasured = true)
    }
    val observed = observedMaxHeartRate ?: return null
    if (isObservedMaxHeartRateTrustworthy(observed.toInt(), restingHeartRate.toInt())) {
        return DailyMaxHeartRate(bpm = observed, isMeasured = true)
    }
    val estimated = ageYears?.let { ageMaxHeartRate(it, sex) }
        ?: maxOf(observed + 10L, restingHeartRate + 70L)
    return DailyMaxHeartRate(bpm = estimated, isMeasured = false)
}

/**
 * A resting heart rate from the day's samples, for days without a measured one:
 * the 10th percentile, kept between 40 and 100 bpm. App heuristic, no
 * published source. Null without samples.
 */
internal fun List<HeartRateSample>.estimatedRestingHeartRate(): Long? {
    if (isEmpty()) return null
    val sorted = map { it.beatsPerMinute }.sorted()
    val index = (sorted.lastIndex * 0.1).roundToInt().coerceIn(sorted.indices)
    return sorted[index].coerceIn(40L, 100L)
}

/** The minutes two time spans share; 0 when they do not overlap. */
internal fun overlapMinutes(
    firstStart: Instant,
    firstEnd: Instant,
    secondStart: Instant,
    secondEnd: Instant,
): Double {
    val overlapStart = maxOf(firstStart, secondStart)
    val overlapEnd = minOf(firstEnd, secondEnd)
    if (!overlapEnd.isAfter(overlapStart)) return 0.0
    return Duration.between(overlapStart, overlapEnd).seconds.toDouble() / 60.0
}

/**
 * Two heart rate readings further apart than this are a gap: the time between
 * them counts for nothing. App choice, no published source.
 */
internal const val MaxHeartRateSampleGapMinutes = 5.0

/**
 * The mean heart rate over time: each pair of neighbouring readings counts
 * with its mean for the time between them, unless that is more than
 * [MaxHeartRateSampleGapMinutes]. Readings at uneven intervals therefore do not
 * skew it, which a plain average of the readings does. Keytel's equation
 * ([KeytelHeartRate]) is linear in heart rate, so this mean gives the same
 * energy as adding it up reading by reading.
 *
 * The plain average when no pair counts (one reading, or only gaps); null without readings.
 */
fun timeWeightedMeanHeartRate(samples: List<Pair<Instant, Long>>): Double? {
    if (samples.isEmpty()) return null
    val ordered = samples.sortedBy { it.first }
    var weighted = 0.0
    var minutes = 0.0
    for ((first, second) in ordered.zipWithNext()) {
        val span = Duration.between(first.first, second.first).toMillis() / 60_000.0
        if (span <= 0.0 || span > MaxHeartRateSampleGapMinutes) continue
        weighted += (first.second + second.second) / 2.0 * span
        minutes += span
    }
    return if (minutes > 0.0) weighted / minutes else ordered.map { it.second }.average()
}
