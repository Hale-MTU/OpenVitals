package tech.mmarca.openvitals.features.sleep

import tech.mmarca.openvitals.domain.preferences.SleepWindow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScheduleAxisTest {

    private val zone = ZoneId.of("UTC")
    private val anchorMinute = 22 * 60 // 22:00 sleep-window start

    private fun night(date: String, start: String, end: String) = SleepScheduleDay(
        date = LocalDate.parse(date),
        inBedStart = Instant.parse(start),
        inBedEnd = Instant.parse(end),
    )

    @Test fun `a 20h night is excluded from the range while a 7h night sets it`() {
        val plausible = night("2026-07-01", "2026-06-30T23:00:00Z", "2026-07-01T06:00:00Z")
        val implausible = night("2026-07-02", "2026-07-01T14:00:00Z", "2026-07-02T10:00:00Z")

        val axis = SleepScheduleAxis.range(listOf(plausible, implausible), zone, anchorMinute)

        assertNotNull(axis)
        // 23:00 -> 60 anchored minutes, 06:00 -> 480 anchored minutes; already whole hours.
        assertEquals(60.0, axis!!.min, 0.0)
        assertEquals(480.0, axis.max, 0.0)
    }

    @Test fun `range pads partial hours out to whole hours`() {
        val axis = SleepScheduleAxis.range(
            listOf(night("2026-07-01", "2026-06-30T23:12:00Z", "2026-07-01T06:47:00Z")),
            zone,
            anchorMinute,
        )

        assertNotNull(axis)
        assertEquals(60.0, axis!!.min, 0.0) // floor(72 / 60) * 60
        assertEquals(540.0, axis.max, 0.0) // ceil(527 / 60) * 60
    }

    @Test fun `no plausible nights yields a null axis`() {
        val onlyImplausible = listOf(
            night("2026-07-01", "2026-06-30T12:00:00Z", "2026-07-01T09:00:00Z"), // 21 h
            SleepScheduleDay(date = LocalDate.parse("2026-07-02"), inBedStart = null, inBedEnd = null),
        )

        assertNull(SleepScheduleAxis.range(onlyImplausible, zone, anchorMinute))
        assertNull(SleepScheduleAxis.range(emptyList(), zone, anchorMinute))
    }

    @Test fun `exactly 16h in bed is implausible`() {
        val sixteenHours = night("2026-07-01", "2026-06-30T22:00:00Z", "2026-07-01T14:00:00Z")

        assertNull(SleepScheduleAxis.range(listOf(sixteenHours), zone, anchorMinute))
    }

    @Test fun `a long but possible lie-in still counts`() {
        // The guard rejects the impossible, not the unusual: 14 h in bed must still set the scale.
        val lieIn = night("2026-07-05", "2026-07-04T20:00:00Z", "2026-07-05T10:00:00Z")

        val axis = SleepScheduleAxis.range(listOf(lieIn), zone, anchorMinute)

        assertNotNull(axis)
        // With the 22:00 anchor, 20:00 -> 1320 and 10:00 -> 1320 + 14 h.
        assertEquals(1320.0, axis!!.min, 0.0)
        assertEquals(1320.0 + 14 * 60, axis.max, 0.0)
    }

    // anchorMinute: the middle of the waking day.

    @Test fun `the anchor is the whole hour halfway through the waking day`() {
        // Default 18:00-10:00 window: awake 10:00-18:00, so the axis wraps at 14:00.
        assertEquals(14 * 60, SleepScheduleAxis.anchorMinute(SleepWindow.Default))
        // 23:00-07:00: awake 07:00-23:00, wrap at 15:00.
        assertEquals(15 * 60, SleepScheduleAxis.anchorMinute(SleepWindow(startHour = 23, endHour = 7)))
        // An odd waking stretch rounds to a whole hour, so tick labels stay on the hour.
        assertEquals(16 * 60, SleepScheduleAxis.anchorMinute(SleepWindow(startHour = 23, endHour = 8)))
        // A window that does not cross midnight still wraps opposite the sleep.
        assertEquals(2 * 60, SleepScheduleAxis.anchorMinute(SleepWindow(startHour = 13, endHour = 15)))
        // A window with no waking stretch at all wraps twelve hours from its start.
        assertEquals(10 * 60, SleepScheduleAxis.anchorMinute(SleepWindow(startHour = 22, endHour = 22)))
    }

    @Test fun `a bedtime before the window start stays above the other nights`() {
        // The reported week: a 23:00 window, a night that began at 22:04 and two that began after midnight.
        val window = SleepWindow(startHour = 23, endHour = 7)
        val anchor = SleepScheduleAxis.anchorMinute(window)
        val wednesday = night("2026-11-04", "2026-11-04T00:34:00Z", "2026-11-04T10:42:00Z")
        val thursday = night("2026-11-05", "2026-11-04T22:04:00Z", "2026-11-05T10:38:00Z")
        val friday = night("2026-11-06", "2026-11-06T00:49:00Z", "2026-11-06T10:30:00Z")

        val axis = SleepScheduleAxis.range(listOf(wednesday, thursday, friday), zone, anchor)

        assertNotNull(axis)
        // 22:00 to 11:00, not the 35 hours the 23:00 anchor produced.
        assertEquals(LocalTime.of(22, 0), SleepScheduleAxis.clockTime(axis!!.min.toInt(), anchor))
        assertEquals(LocalTime.of(11, 0), SleepScheduleAxis.clockTime(axis.max.toInt(), anchor))
        assertEquals(13.0 * 60, axis.span, 0.0)
        val thursdayStart = SleepScheduleAxis.anchoredMinutes(thursday.inBedStart!!, zone, anchor)
        val wednesdayStart = SleepScheduleAxis.anchoredMinutes(wednesday.inBedStart!!, zone, anchor)
        assertTrue("22:04 must sit above 00:34 on the axis", thursdayStart < wednesdayStart)
        // With the window start itself as the anchor the same week wrapped.
        val wrapped = SleepScheduleAxis.range(listOf(wednesday, thursday, friday), zone, 23 * 60)
        assertEquals(35.0 * 60, wrapped!!.span, 0.0)
    }

    // anchoredMinutes.

    @Test fun `the anchor itself is minute zero`() {
        val atAnchor = Instant.parse("2026-07-01T22:00:00Z")

        assertEquals(0.0, SleepScheduleAxis.anchoredMinutes(atAnchor, zone, anchorMinute), 0.0)
        // …and the same holds for an 18:00 sleep window, the Flutter default.
        val eighteen = Instant.parse("2026-07-01T18:00:00Z")
        assertEquals(0.0, SleepScheduleAxis.anchoredMinutes(eighteen, zone, 18 * 60), 0.0)
        // An evening bedtime sits early on the axis; a morning wake-up wraps past midnight.
        assertEquals(
            5.0 * 60,
            SleepScheduleAxis.anchoredMinutes(Instant.parse("2026-07-01T23:00:00Z"), zone, 18 * 60),
            0.0,
        )
        assertEquals(
            13.0 * 60,
            SleepScheduleAxis.anchoredMinutes(Instant.parse("2026-07-02T07:00:00Z"), zone, 18 * 60),
            0.0,
        )
    }

    @Test fun `anchoredMinutes is always inside a single day`() {
        (0 until 24).forEach { hour ->
            val value = SleepScheduleAxis.anchoredMinutes(
                Instant.parse("2026-07-05T%02d:00:00Z".format(hour)),
                zone,
                18 * 60,
            )
            assertTrue("hour $hour produced $value", value >= 0.0)
            assertTrue("hour $hour produced $value", value < SleepScheduleAxis.MINUTES_PER_DAY)
        }
    }

    // anchoredMinute to clock and back.

    @Test fun `clockTime round-trips the anchor and a wrapped morning`() {
        assertEquals(LocalTime.of(18, 0), SleepScheduleAxis.clockTime(0, 18 * 60))
        assertEquals(LocalTime.of(0, 0), SleepScheduleAxis.clockTime(6 * 60, 18 * 60))
        assertEquals(LocalTime.of(7, 0), SleepScheduleAxis.clockTime(13 * 60, 18 * 60))
    }

    @Test fun `anchoredClockMinute is the inverse of clockTime`() {
        listOf(0, 6 * 60, 18 * 60, 23 * 60 + 59).forEach { minuteOfDay ->
            val anchored = SleepScheduleAxis.anchoredClockMinute(minuteOfDay, 18 * 60).toInt()
            val clock = SleepScheduleAxis.clockTime(anchored, 18 * 60)
            assertEquals(minuteOfDay, clock.hour * 60 + clock.minute)
        }
    }

    @Test fun `tick step is hourly at an 8h span and 2-hourly above it`() {
        val eightHours = SleepScheduleAxis.Range(min = 0.0, max = 480.0)
        assertEquals(listOf(0, 60, 120, 180, 240, 300, 360, 420, 480), eightHours.tickMinutes())

        val overEightHours = SleepScheduleAxis.Range(min = 0.0, max = 540.0)
        assertEquals(listOf(0, 120, 240, 360, 480), overEightHours.tickMinutes())
    }

    @Test fun `normalized end minutes stay monotone across the anchor wrap`() {
        val start = Instant.parse("2026-06-30T23:30:00Z") // 90 anchored minutes
        val end = Instant.parse("2026-07-01T07:15:00Z") // 555 anchored minutes

        assertEquals(90.0, SleepScheduleAxis.anchoredMinutes(start, zone, anchorMinute), 0.0)
        assertEquals(555.0, SleepScheduleAxis.normalizedEndMinutes(start, end, zone, anchorMinute), 0.0)

        // An end that lands before the start on the anchored clock wraps forward a full day.
        val lateStart = Instant.parse("2026-07-01T02:00:00Z") // 240 anchored minutes
        val wrapEnd = Instant.parse("2026-07-01T23:00:00Z") // 60 anchored minutes
        assertEquals(
            60.0 + 24 * 60,
            SleepScheduleAxis.normalizedEndMinutes(lateStart, wrapEnd, zone, anchorMinute),
            0.0,
        )
    }
}
