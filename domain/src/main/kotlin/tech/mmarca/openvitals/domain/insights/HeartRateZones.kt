package tech.mmarca.openvitals.domain.insights

import java.time.Duration
import kotlin.math.ceil
import tech.mmarca.openvitals.domain.model.HeartRateSample
import tech.mmarca.openvitals.domain.preferences.HeartZoneThresholds

const val GarberIntensityClassesUrl = "https://doi.org/10.1249/MSS.0b013e318213fefb"

/**
 * The five heart rate zones: the American College of Sports Medicine's
 * intensity classes very light, light, moderate, vigorous and near-maximal.
 * Garber et al., "Quantity and quality of exercise for developing and
 * maintaining cardiorespiratory, musculoskeletal, and neuromotor fitness in
 * apparently healthy adults", ACSM position stand, Med Sci Sports Exerc
 * 2011;43(7):1334-1359, doi:10.1249/MSS.0b013e318213fefb, table 4. With a
 * resting rate the zones are shares of the heart rate reserve (Karvonen),
 * without one shares of the maximum. Very light has no lower bound in the
 * table; here it starts at rest, or at nothing.
 *
 * Without limits of the user's own, the bounds and the choice of basis are the
 * same as OpenWatch's `HeartRateZones`, so one heart rate lands in the same
 * zone in both apps. Body Energy keeps its own estimate of the activity drain
 * (it splits vigorous at 75 percent) and does not use these.
 */
object HeartRateZones {
    const val COUNT = 5
    private const val ROUNDING_TOLERANCE = 1e-9

    /** Lower bounds as a share of the maximum (ACSM %HRmax), zone 1 first. */
    val MAX_SHARE_BOUNDS: List<Double> = listOf(0.0, 0.57, 0.64, 0.77, 0.96)

    /** Lower bounds as a share of the reserve (ACSM %HRR), zone 1 first. */
    val RESERVE_SHARE_BOUNDS: List<Double> = listOf(0.0, 0.30, 0.40, 0.60, 0.90)

    /**
     * A reading further from the next one than this counts for no time at all:
     * the strap or watch was off. The same limit as cardio load, intensity
     * minutes and workout calories ([MaxHeartRateSampleGapMinutes]) and as
     * OpenWatch. An app choice, no published source.
     */
    val MaxSampleGap: Duration = Duration.ofMinutes(MaxHeartRateSampleGapMinutes.toLong())

    data class Basis(val maxBpm: Int, val restingBpm: Int? = null) {
        private val resting: Int? get() = restingBpm?.takeIf { it < maxBpm }

        /** The lowest whole bpm in each zone, zone 1 first. */
        val boundsBpm: List<Int>
            get() {
                val floor = resting?.toDouble() ?: 0.0
                val shares = if (resting != null) RESERVE_SHARE_BOUNDS else MAX_SHARE_BOUNDS
                return shares.map { ceil(floor + it * (maxBpm - floor) - ROUNDING_TOLERANCE).toInt() }
            }
    }

    /**
     * The zones a heart rate is placed on: the lowest bpm of each, zone 1 first.
     * [maxBpm] is the maximum they were derived from, null for the user's own
     * limits.
     */
    data class Bounds(val lowerBpm: List<Int>, val maxBpm: Int?)

    /** The user's own limits when set (Body Energy calibration), else the zones of [basis]. */
    fun boundsOf(manual: HeartZoneThresholds?, basis: Basis?): Bounds? = when {
        manual != null -> Bounds(
            listOf(manual.zone1LowerBpm, manual.zone2LowerBpm, manual.zone3LowerBpm, manual.zone4LowerBpm, manual.zone5LowerBpm),
            maxBpm = null,
        )
        basis != null -> Bounds(basis.boundsBpm, basis.maxBpm)
        else -> null
    }

    /** The zone index, 0 to 4; null below zone 1. */
    fun zoneOf(bpm: Long, lowerBpm: List<Int>): Int? = lowerBpm.indexOfLast { bpm >= it }.takeIf { it >= 0 }

    /**
     * The time spent in each zone, zone 1 first: each reading counts until the
     * next one, at most [MaxSampleGap]. The last reading counts for no time.
     */
    fun timeInZones(samples: List<HeartRateSample>, lowerBpm: List<Int>): List<Duration> {
        val millis = LongArray(COUNT)
        val ordered = samples.sortedBy { it.time }
        for (index in 0 until ordered.size - 1) {
            val zone = zoneOf(ordered[index].beatsPerMinute, lowerBpm) ?: continue
            val span = Duration.between(ordered[index].time, ordered[index + 1].time)
            if (span <= MaxSampleGap) millis[zone] += span.toMillis()
        }
        return millis.map(Duration::ofMillis)
    }
}
