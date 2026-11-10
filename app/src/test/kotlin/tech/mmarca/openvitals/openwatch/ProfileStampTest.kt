package tech.mmarca.openvitals.openwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkSex
import tech.mmarca.openvitals.domain.preferences.BiologicalSex
import tech.mmarca.openvitals.domain.preferences.BodyProfile

class ProfileStampTest {

    private val profile = BodyProfile(birthYear = 1990, heightCm = 180.0, weightKg = 75.0, sex = BiologicalSex.MALE)

    @Test
    fun `the first profile predates the link, so OpenWatch's edits win over it`() {
        val stamp = ProfileStamp()

        val first = stamp.onLocal(profile, now = 50_000)

        assertEquals(1L, first!!.updatedAtMillis)
        assertNotNull(stamp.onRemote(OpenLinkProfile(birthYear = 1991, updatedAtMillis = 2)))
    }

    @Test
    fun `an empty profile is never pushed and never beats a real one`() {
        val stamp = ProfileStamp()

        assertNull(stamp.onLocal(BodyProfile(), now = 50_000))
        val adopted = stamp.onRemote(OpenLinkProfile(birthYear = 1985, sex = OpenLinkSex.FEMALE, updatedAtMillis = 1))

        assertEquals(1985, adopted!!.birthYear)
        assertEquals(BiologicalSex.FEMALE, adopted.sex)
    }

    @Test
    fun `an edit here is pushed with its time, and its echo is not pushed again`() {
        val stamp = ProfileStamp()
        stamp.onLocal(profile, now = 1_000)
        val edited = profile.copy(weightKg = 74.0)

        val pushed = stamp.onLocal(edited, now = 9_000)

        assertEquals(9_000L, pushed!!.updatedAtMillis)
        assertNull(stamp.onLocal(edited, now = 10_000))
        // OpenWatch's copy of the same edit, or anything older, changes nothing.
        assertNull(stamp.onRemote(pushed))
        assertNull(stamp.onRemote(pushed.copy(weightKg = 70.0, updatedAtMillis = 8_000)))
    }

    @Test
    fun `an adopted profile is not pushed back`() {
        val stamp = ProfileStamp()
        stamp.onLocal(profile, now = 1_000)

        val adopted = stamp.onRemote(OpenLinkProfile(birthYear = 1990, heightCm = 182.0, updatedAtMillis = 20_000))!!

        assertNull(stamp.onLocal(adopted, now = 21_000))
    }

    @Test
    fun `a profile already stamped in an earlier run is offered again as it stands, an edited or empty one is not`() {
        val earlierRun = ProfileStamp().apply { onLocal(profile, now = 50_000) }
        val stamp = ProfileStamp(earlierRun.updatedAtMillis, earlierRun.signature)

        assertNull(stamp.onLocal(profile, now = 90_000))
        assertEquals(1L, stamp.current(profile)!!.updatedAtMillis)
        assertNull(stamp.current(profile.copy(weightKg = 80.0)))
        assertNull(ProfileStamp().apply { onLocal(BodyProfile(), now = 1) }.current(BodyProfile()))
    }
}
