package tech.mmarca.openvitals.openwatch

import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkSex
import tech.mmarca.openvitals.domain.preferences.BiologicalSex
import tech.mmarca.openvitals.domain.preferences.BodyProfile

internal fun BodyProfile.toLink(updatedAtMillis: Long): OpenLinkProfile = OpenLinkProfile(
    birthYear = birthYear,
    sex = when (sex) {
        BiologicalSex.FEMALE -> OpenLinkSex.FEMALE
        BiologicalSex.MALE -> OpenLinkSex.MALE
        null -> null
    },
    heightCm = heightCm,
    weightKg = weightKg,
    restingHeartRateBpm = restingHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    updatedAtMillis = updatedAtMillis,
)

internal fun BodyProfile.isUnset(): Boolean =
    birthYear == null && weightKg == null && heightCm == null && restingHeartRateBpm == null &&
        maxHeartRateBpm == null && sex == null

internal fun OpenLinkProfile.toBody(): BodyProfile = BodyProfile(
    birthYear = birthYear,
    weightKg = weightKg,
    heightCm = heightCm,
    restingHeartRateBpm = restingHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    sex = when (sex) {
        OpenLinkSex.FEMALE -> BiologicalSex.FEMALE
        OpenLinkSex.MALE -> BiologicalSex.MALE
        null -> null
    },
)

/**
 * The profile's side of the link, last writer wins: the time of this side's last change
 * and the signature of the profile it belongs to. Pure, so the rules are tested on the JVM.
 */
internal class ProfileStamp(
    var updatedAtMillis: Long = 0L,
    var signature: String? = null,
) {
    /**
     * A local profile: a change if its signature is new. Returns what to push, or null.
     * The first profile ever seen predates the link: it is stamped 1 (0 if empty), so a
     * real profile on either side beats an empty one and any later edit beats both.
     */
    fun onLocal(profile: BodyProfile, now: Long): OpenLinkProfile? {
        val next = profile.signature()
        if (next == signature) return null
        val firstSight = signature == null
        updatedAtMillis = when {
            !firstSight -> now
            profile.isUnset() -> 0L
            else -> 1L
        }
        signature = next
        return profile.toLink(updatedAtMillis).takeIf { updatedAtMillis > 0 }
    }

    /**
     * [profile] as OpenWatch should hold it, with the time of its last change, or null for
     * one this side has not stamped (or an empty one). Pushed on every start and on "Sync
     * now", not only on a change: OpenWatch keeps it only if it is newer, so a repeat is
     * harmless, and a push queued before the link was allowed does not outlive the process.
     */
    fun current(profile: BodyProfile): OpenLinkProfile? =
        profile.toLink(updatedAtMillis).takeIf { updatedAtMillis > 0 && profile.signature() == signature }

    /** OpenWatch's profile: the body profile to adopt if it is newer, else null. */
    fun onRemote(profile: OpenLinkProfile): BodyProfile? {
        if (profile.updatedAtMillis <= updatedAtMillis) return null
        val body = profile.toBody()
        updatedAtMillis = profile.updatedAtMillis
        signature = body.signature()
        return body
    }
}
