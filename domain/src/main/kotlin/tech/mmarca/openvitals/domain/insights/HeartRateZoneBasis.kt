package tech.mmarca.openvitals.domain.insights

import java.time.LocalDate
import tech.mmarca.openvitals.domain.preferences.BodyProfile

/**
 * The maximum and the resting heart rate the [HeartRateZones] rest on.
 *
 * Maximum: the one the user entered (a measured maximum is what the ACSM
 * prefers over any formula); else [observedMaxBpm] when it is a real maximum
 * ([isObservedMaxHeartRateTrustworthy], which needs a resting rate); else the
 * age-predicted one ([ageMaxHeartRate]). Without any of them there is no basis.
 *
 * Resting: the one the user entered. [nightRestingBpm] only decides whether an
 * observed maximum is believable; it does not move the zones, the same as in
 * OpenWatch's `HeartRateZoneBasis`.
 */
object HeartRateZoneBasis {
    fun of(
        profile: BodyProfile,
        today: LocalDate = LocalDate.now(),
        observedMaxBpm: Int? = null,
        nightRestingBpm: Int? = null,
    ): HeartRateZones.Basis? {
        val normalized = profile.normalized(today)
        val resting = normalized.restingHeartRateBpm ?: nightRestingBpm
        val observed = observedMaxBpm?.takeIf { max ->
            resting != null && isObservedMaxHeartRateTrustworthy(max, resting)
        }
        val predicted = normalized.ageYears(today)?.let { age -> ageMaxHeartRate(age, normalized.sex).toInt() }
        val max = normalized.maxHeartRateBpm ?: observed ?: predicted ?: return null
        return HeartRateZones.Basis(max, normalized.restingHeartRateBpm)
    }
}
