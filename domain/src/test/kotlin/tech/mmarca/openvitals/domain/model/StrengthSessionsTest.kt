package tech.mmarca.openvitals.domain.model

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

object StrengthTestData {
    /** Two workouts as Hevy exports them: the first under an Italian phone, the second under English. */
    val CSV = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Panca Piana (Multipower)",,"",0,"normal",35,12,,,
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Panca Piana (Multipower)",,"",1,"normal",40,10,,,
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Trazione Supina (Assistita)",,"",0,"normal",35,10,,,
        "Upper Body","2 nov 2026, 19:15","2 nov 2026, 20:15","","Tapis Roulant",,"",0,"normal",,,1.5,911,
        "Gambe","Oct 28, 2026, 7:57 PM","Oct 28, 2026, 8:52 PM","","Leg Extension (Macchina)",,"",0,"warmup",63,12,,,
    """.trimIndent()

    fun session(
        id: String,
        source: String,
        start: Instant,
        end: Instant,
        type: Int = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        title: String? = null,
    ) = ExerciseData(
        id = id,
        title = title,
        exerciseType = type,
        startTime = start,
        endTime = end,
        durationMs = end.toEpochMilli() - start.toEpochMilli(),
        source = source,
    )
}

class HevyCsvParserTest {

    @Test
    fun `parses Italian and English dates and groups sets by workout and exercise`() {
        val result = HevyCsvParser.parse(StrengthTestData.CSV, Locale.ITALIAN)

        assertEquals(2, result.workouts.size)
        assertEquals(5, result.setCount)
        assertEquals(0, result.skippedRows)
        val upper = result.workouts.last()
        assertEquals(LocalDateTime.of(2026, 11, 2, 19, 15), upper.start)
        assertEquals(LocalDateTime.of(2026, 11, 2, 20, 15), upper.end)
        assertEquals(3, upper.exercises.size)
        assertEquals(2, upper.exercises.first().sets.size)
        assertTrue(upper.exercises[1].isAssisted)
        assertTrue(upper.exercises[2].isCardio)
        val legs = result.workouts.first()
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 57), legs.start)
        assertEquals("warmup", legs.exercises.single().sets.single().type)
    }

    @Test
    fun `reads the dates of other locales and of ISO exports`() {
        val months = HevyCsvParser.monthNames(Locale.GERMAN)
        assertEquals(LocalDateTime.of(2026, 3, 5, 7, 5), HevyCsvParser.parseDateTime("5. März 2026, 07:05", months))
        assertEquals(LocalDateTime.of(2026, 12, 24, 18, 30), HevyCsvParser.parseDateTime("2026-12-24T18:30:00", months))
        assertEquals(LocalDateTime.of(2026, 1, 9, 6, 0), HevyCsvParser.parseDateTime("09.01.2026, 6:00", months))
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 15), HevyCsvParser.parseDateTime("Nov 2, 2026, 12:15 AM", months))
        assertNull(HevyCsvParser.parseDateTime("yesterday", months))
    }

    @Test(expected = HevyCsvParser.FormatException::class)
    fun `a CSV without the Hevy columns is refused`() {
        HevyCsvParser.parse("date,weight\n2026-01-01,80\n")
    }

    @Test
    fun `quoted fields keep commas and doubled quotes`() {
        val rows = HevyCsvParser.parseCsv("a,\"b, c\",\"say \"\"hi\"\"\"\r\n1,2,3\n")
        assertEquals(listOf(listOf("a", "b, c", "say \"hi\""), listOf("1", "2", "3")), rows)
    }
}

class StrengthSessionsTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val imported = HevyCsvParser.parse(StrengthTestData.CSV, Locale.ITALIAN).workouts
    private fun at(h: Int, m: Int, day: Int = 2): Instant = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant()

    @Test
    fun `an app's own session takes the imported sets and a watch's overlapping record folds in`() {
        val hevy = StrengthTestData.session("1", "com.hevy", at(19, 16), at(20, 15), title = "Upper Body")
        val watch = StrengthTestData.session("2", "com.garmin", at(19, 10), at(20, 20))
        val sessions = StrengthSessions.build(listOf(hevy, watch), imported, zone)

        assertEquals(2, sessions.size)
        val upper = sessions.first()
        assertEquals("Upper Body", upper.title)
        assertEquals(3, upper.exercises.size)
        assertEquals("1", upper.healthConnectId)
        assertEquals(listOf("com.hevy", "com.garmin"), upper.sources)
        // The watch has the real times.
        assertEquals(LocalDateTime.of(2026, 11, 2, 19, 10), upper.start)
        assertEquals(70L, upper.durationMinutes)
        // Assisted pull-ups do not count: 35×12 + 40×10.
        assertEquals(820.0, upper.volumeKg, 0.01)
        assertEquals(3, upper.setCount)
        // The legs workout has no Health Connect session: shown from the import alone.
        val legs = sessions.last()
        assertNull(legs.healthConnectId)
        assertEquals("Gambe", legs.title)
    }

    @Test
    fun `an import filled in later joins the strength session of the same day`() {
        val walk = StrengthTestData.session("w", "com.garmin", at(13, 50), at(13, 56), type = ExerciseSessionRecord.EXERCISE_TYPE_WALKING)
        val weights = StrengthTestData.session("s", "com.garmin", at(17, 36), at(18, 56))
        val upper = StrengthSessions.build(listOf(walk, weights), imported, zone).first()

        assertEquals("s", upper.healthConnectId)
        assertEquals(LocalDateTime.of(2026, 11, 2, 17, 36), upper.start)
        assertEquals(80L, upper.durationMinutes)
        assertEquals(LocalDate.of(2026, 11, 2), upper.date)
    }

    @Test
    fun `other sessions of the day are neither taken for the weights nor shown`() {
        val weights = StrengthTestData.session("s", "com.garmin", at(17, 36), at(18, 56))
        // A yoga class overlapping the time the import was filled in must not be taken for the weights.
        val yoga = StrengthTestData.session("y", "com.garmin", at(19, 20), at(20, 20), type = ExerciseSessionRecord.EXERCISE_TYPE_YOGA, title = "Evening yoga")
        val sessions = StrengthSessions.build(listOf(weights, yoga), imported, zone).filter { it.date == LocalDate.of(2026, 11, 2) }

        assertEquals(listOf("s"), sessions.map { it.healthConnectId })
    }

    @Test
    fun `two apps' records of one workout without an import are one session`() {
        val watch = StrengthTestData.session("a", "com.garmin", at(7, 0, day = 5), at(8, 0, day = 5))
        val phone = StrengthTestData.session("b", "com.other", at(7, 5, day = 5), at(7, 55, day = 5), title = "Push day")
        val calisthenics = StrengthTestData.session("c", "com.garmin", at(18, 0, day = 6), at(18, 30, day = 6), type = ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS)
        val run = StrengthTestData.session("r", "com.garmin", at(9, 0, day = 6), at(9, 40, day = 6), type = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        val sessions = StrengthSessions.build(listOf(watch, phone, calisthenics, run), emptyList(), zone)

        assertEquals(listOf("c", "a"), sessions.map { it.healthConnectId })
        val merged = sessions.last()
        assertEquals("Push day", merged.title)
        assertEquals(listOf("com.garmin", "com.other"), merged.sources)
        assertEquals(60L, merged.durationMinutes)
        assertTrue(sessions.none { it.hasSets })
    }

    @Test
    fun `progress is the best estimated one-rep max per day and rest days count from the last session`() {
        val sessions = StrengthSessions.build(emptyList(), imported, zone)

        assertEquals(listOf("Leg Extension (Macchina)", "Panca Piana (Multipower)"), StrengthSessions.progressExercises(sessions))
        val points = StrengthSessions.progress(sessions, "Panca Piana (Multipower)")
        assertEquals(1, points.size)
        assertEquals(40 * (1 + 10 / 30.0), points.single().oneRepMaxKg, 0.01)
        assertEquals(2, StrengthSessions.restDays(sessions, LocalDate.of(2026, 11, 4)))
        assertNull(StrengthSessions.restDays(emptyList(), LocalDate.of(2026, 11, 4)))
    }
}
