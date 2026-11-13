package tech.mmarca.openvitals.domain.insights

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import tech.mmarca.openvitals.domain.model.HeartRateSample
import tech.mmarca.openvitals.domain.preferences.BiologicalSex
import tech.mmarca.openvitals.domain.preferences.BodyProfile
import tech.mmarca.openvitals.domain.preferences.HeartZoneThresholds

class HeartRateZonesTest {
    @Test
    fun `with a resting rate the zones are shares of the reserve`() {
        val basis = HeartRateZones.Basis(maxBpm = 190, restingBpm = 50)
        assertEquals(listOf(50, 92, 106, 134, 176), basis.boundsBpm)
        assertEquals(0, HeartRateZones.zoneOf(91, basis.boundsBpm))
        assertEquals(1, HeartRateZones.zoneOf(92, basis.boundsBpm))
        assertEquals(4, HeartRateZones.zoneOf(176, basis.boundsBpm))
    }

    @Test
    fun `without a resting rate the zones are shares of the maximum`() {
        val basis = HeartRateZones.Basis(maxBpm = 200)
        assertEquals(listOf(0, 114, 128, 154, 192), basis.boundsBpm)
        assertEquals(0, HeartRateZones.zoneOf(113, basis.boundsBpm))
        assertEquals(1, HeartRateZones.zoneOf(114, basis.boundsBpm))
        assertEquals(3, HeartRateZones.zoneOf(191, basis.boundsBpm))
        assertEquals(4, HeartRateZones.zoneOf(192, basis.boundsBpm))
    }

    @Test
    fun `a resting rate at or above the maximum is ignored`() {
        val basis = HeartRateZones.Basis(maxBpm = 100, restingBpm = 120)
        assertEquals(listOf(0, 57, 64, 77, 96), basis.boundsBpm)
    }

    @Test
    fun `the user's own limits win over a basis`() {
        val manual = HeartZoneThresholds(100, 120, 140, 160, 180)
        val bounds = HeartRateZones.boundsOf(manual, HeartRateZones.Basis(190, 50))
        assertEquals(HeartRateZones.Bounds(listOf(100, 120, 140, 160, 180), maxBpm = null), bounds)
        assertEquals(2, HeartRateZones.zoneOf(150, bounds!!.lowerBpm))
    }

    @Test
    fun `without own limits the bounds come from the basis, and without either there are none`() {
        val basis = HeartRateZones.Basis(200)
        assertEquals(HeartRateZones.Bounds(basis.boundsBpm, 200), HeartRateZones.boundsOf(null, basis))
        assertNull(HeartRateZones.boundsOf(null, null))
    }

    @Test
    fun `a rate below the resting rate is below zone 1`() {
        assertNull(HeartRateZones.zoneOf(40, HeartRateZones.Basis(190, 50).boundsBpm))
    }

    @Test
    fun `time in zones counts each reading until the next and skips gaps`() {
        val basis = HeartRateZones.Basis(maxBpm = 200)
        val start = Instant.parse("2026-11-12T10:00:00Z")
        fun at(seconds: Long, bpm: Long) = HeartRateSample(start.plusSeconds(seconds), bpm, "test")
        val time = HeartRateZones.timeInZones(
            // 60 s to 400 s is more than five minutes: a gap, it counts for nothing.
            listOf(at(30, 130), at(0, 120), at(60, 192), at(400, 192), at(410, 100)),
            basis.boundsBpm,
        )
        assertEquals(
            listOf(Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(10)),
            time,
        )
    }
}

class HeartRateZoneBasisTest {
    private val today = LocalDate.of(2026, 11, 12)

    @Test
    fun `an entered maximum wins`() {
        val profile = BodyProfile(birthYear = 1990, maxHeartRateBpm = 185, restingHeartRateBpm = 55)
        assertEquals(HeartRateZones.Basis(185, 55), HeartRateZoneBasis.of(profile, today, observedMaxBpm = 199))
    }

    @Test
    fun `a believable observed maximum beats the formula`() {
        val profile = BodyProfile(birthYear = 1990, restingHeartRateBpm = 55)
        assertEquals(190, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 190)?.maxBpm)
    }

    @Test
    fun `an observed maximum without a resting rate is not believed`() {
        val profile = BodyProfile(birthYear = 1990, sex = BiologicalSex.MALE)
        assertEquals(183, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 199)?.maxBpm)
    }

    @Test
    fun `an observed maximum less than 60 above rest is not believed`() {
        val profile = BodyProfile(birthYear = 1990, restingHeartRateBpm = 100)
        assertEquals(183, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 155)?.maxBpm)
    }

    @Test
    fun `the formula depends on sex`() {
        val man = BodyProfile(birthYear = 1986, sex = BiologicalSex.MALE)
        val woman = man.copy(sex = BiologicalSex.FEMALE)
        assertEquals(180, HeartRateZoneBasis.of(man, today)?.maxBpm)
        assertEquals(171, HeartRateZoneBasis.of(woman, today)?.maxBpm)
    }

    @Test
    fun `a night's resting rate does not move the zones`() {
        val profile = BodyProfile(birthYear = 1990)
        assertNull(HeartRateZoneBasis.of(profile, today, nightRestingBpm = 52)?.restingBpm)
    }

    @Test
    fun `no maximum, no age and no observation gives no basis`() {
        assertNull(HeartRateZoneBasis.of(BodyProfile(), today))
    }
}
