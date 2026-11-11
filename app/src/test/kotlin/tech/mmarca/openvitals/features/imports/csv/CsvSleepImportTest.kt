package tech.mmarca.openvitals.features.imports.csv

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.SleepStage
import tech.mmarca.openvitals.domain.model.sleepDurationMsFromStages

private fun sleepAt(index: Int, part: CsvSleepPart, unit: CsvUnit? = null) = CsvColumnMapping(
    columnIndex = index,
    role = CsvColumnRole.SLEEP,
    sleepPart = part,
    interpretation = unit?.let(::CsvDirectValue),
)

/**
 * Sleep Cycle's export, cut to the columns Health Connect can hold:
 * `Went to bed;Woke up;Sleep quality;Time in bed (seconds);Time asleep (seconds);Time before sleep (seconds);Steps;Notes`.
 */
private fun sleepCycleMapping(
    sleep: List<CsvColumnMapping> = listOf(
        sleepAt(1, CsvSleepPart.WOKE_UP),
        sleepAt(4, CsvSleepPart.TIME_ASLEEP, CsvUnit.SECONDS),
        sleepAt(5, CsvSleepPart.TIME_TO_FALL_ASLEEP, CsvUnit.SECONDS),
        sleepAt(7, CsvSleepPart.NOTES),
    ),
    extra: List<CsvColumnMapping> = emptyList(),
) = CsvImportMapping(
    columns = listOf(CsvColumnMapping(columnIndex = 0, role = CsvColumnRole.TIMESTAMP)) + sleep + extra,
    dateTime = CsvDateTimeSettings(format = CsvDateTimeFormat.YEAR_FIRST, zone = CsvTimeZoneMode.UTC),
)

/** 23:10 to 07:04, 7 h 54 min in bed; 15 min to fall asleep, 7 h asleep. */
private val Night = listOf(
    "2026-11-01 23:10:00", "2026-11-02 07:04:00", "82%", "28440", "25200", "900", "8432", "Coffee:Stressful day",
)

private fun night(vararg replacements: Pair<Int, String>): CsvRow {
    val fields = Night.toMutableList()
    for ((index, text) in replacements) fields[index] = text
    return CsvRow(rowNumber = 2, fields = fields)
}

private fun CsvRowConversion.session(): SleepSessionRecord =
    records.single { it.recordType == SleepSessionRecord::class }.record as SleepSessionRecord

private fun CsvRowConversion.hasSession(): Boolean = records.any { it.recordType == SleepSessionRecord::class }

private fun SleepSessionRecord.stageList(): List<Triple<Instant, Instant, Int>> =
    stages.map { Triple(it.startTime, it.endTime, it.stage) }

/** What the sleep screen counts as asleep for this session. */
private fun SleepSessionRecord.asleepMillis(): Long = sleepDurationMsFromStages(
    stages.map { SleepStage(it.startTime, it.endTime, it.stage) },
    endTime.toEpochMilli() - startTime.toEpochMilli(),
)

private fun at(text: String): Instant = Instant.parse(text)

class CsvSleepImportTest {

    @Test
    fun `a Sleep Cycle night is one session, awake while falling asleep, asleep for its time asleep, awake for the rest`() {
        val conversion = convertCsvRow(night(), sleepCycleMapping())

        assertTrue(conversion.diagnostics.isEmpty())
        val session = conversion.session()
        assertEquals(at("2026-11-01T23:10:00Z"), session.startTime)
        assertEquals(at("2026-11-02T07:04:00Z"), session.endTime)
        assertEquals(
            listOf(
                Triple(at("2026-11-01T23:10:00Z"), at("2026-11-01T23:25:00Z"), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                Triple(at("2026-11-01T23:25:00Z"), at("2026-11-02T06:25:00Z"), SleepSessionRecord.STAGE_TYPE_SLEEPING),
                Triple(at("2026-11-02T06:25:00Z"), at("2026-11-02T07:04:00Z"), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
            ),
            session.stageList(),
        )
        assertEquals(25_200_000L, session.asleepMillis())
        assertEquals("Coffee:Stressful day", session.notes)
        assertNull(session.title)
        assertEquals(buildCsvClientRecordId(CSV_SLEEP_TARGET_TYPE, session.startTime), session.metadata.clientRecordId)
    }

    @Test
    fun `the confirm preview shows time in bed and time asleep as the sleep screen will count them`() {
        val bedAndWake = sleepCycleMapping(sleep = listOf(sleepAt(1, CsvSleepPart.WOKE_UP)))

        assertEquals(
            listOf(CsvSleepPreview(inBedMillis = 28_440_000L, asleepMillis = 25_200_000L)),
            previewSleepSessions(listOf(Night), sleepCycleMapping()),
        )
        // Unstaged, the night counts as asleep throughout, as any app's would.
        assertEquals(
            listOf(CsvSleepPreview(inBedMillis = 28_440_000L, asleepMillis = 28_440_000L)),
            previewSleepSessions(listOf(Night), bedAndWake),
        )
        assertEquals("7h 54m", csvSleepDurationText(28_440_000L))
        assertEquals("0h 05m", csvSleepDurationText(300_000L))
    }

    @Test
    fun `without a Woke up the session lasts its Time in bed, and a filled Woke up wins over it`() {
        val mapping = sleepCycleMapping(
            sleep = listOf(sleepAt(1, CsvSleepPart.WOKE_UP), sleepAt(3, CsvSleepPart.TIME_IN_BED, CsvUnit.SECONDS)),
        )

        assertEquals(at("2026-11-02T07:04:00Z"), convertCsvRow(night(1 to ""), mapping).session().endTime)
        assertEquals(at("2026-11-02T06:00:00Z"), convertCsvRow(night(1 to "2026-11-02 06:00:00"), mapping).session().endTime)
    }

    @Test
    fun `a duration written as a clock reads as hours and minutes whatever the unit, anything else in the column's unit`() {
        assertEquals(27_120_000L, parseCsvDurationMillis("7:32", CsvUnit.SECONDS))
        assertEquals(930_000L, parseCsvDurationMillis(" 0:15:30 ", CsvUnit.HOURS))
        assertEquals(27_000_000L, parseCsvDurationMillis("450", CsvUnit.MINUTES))
        assertEquals(5_400_000L, parseCsvDurationMillis("1,5", CsvUnit.HOURS))
        assertEquals(28_440_000L, parseCsvDurationMillis("28440", CsvUnit.SECONDS))
        assertNull(parseCsvDurationMillis("7:32:10:05", CsvUnit.MINUTES))
        assertNull(parseCsvDurationMillis("7:75", CsvUnit.MINUTES))
    }

    @Test
    fun `a header naming seconds, minutes or hours picks that unit`() {
        assertEquals(CsvUnit.SECONDS, detectCsvUnitInHeader("Time asleep (seconds)"))
        assertEquals(CsvUnit.MINUTES, detectCsvUnitInHeader("Minutes asleep (min)"))
        assertEquals(CsvUnit.HOURS, detectCsvUnitInHeader("Sleep (h)"))
        assertEquals(
            CsvDirectValue(CsvUnit.SECONDS),
            csvSleepInterpretationForUnit(CsvSleepPart.TIME_ASLEEP, CsvUnit.SECONDS),
        )
        assertNull(csvSleepInterpretationForUnit(CsvSleepPart.WOKE_UP, CsvUnit.SECONDS))
        assertEquals(CsvDirectValue(CsvUnit.MINUTES), sleepAt(4, CsvSleepPart.TIME_ASLEEP).effectiveInterpretation)
    }

    @Test
    fun `the time to fall asleep alone leaves the night asleep from then to the end`() {
        val mapping = sleepCycleMapping(
            sleep = listOf(sleepAt(1, CsvSleepPart.WOKE_UP), sleepAt(5, CsvSleepPart.TIME_TO_FALL_ASLEEP, CsvUnit.SECONDS)),
        )

        assertEquals(
            listOf(
                Triple(at("2026-11-01T23:10:00Z"), at("2026-11-01T23:25:00Z"), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                Triple(at("2026-11-01T23:25:00Z"), at("2026-11-02T07:04:00Z"), SleepSessionRecord.STAGE_TYPE_SLEEPING),
            ),
            convertCsvRow(night(), mapping).session().stageList(),
        )
    }

    @Test
    fun `time asleep past what the night leaves after falling asleep stops at the end`() {
        val session = convertCsvRow(night(5 to "3600", 4 to "28440"), sleepCycleMapping()).session()

        assertEquals(
            listOf(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_SLEEPING),
            session.stages.map { it.stage },
        )
        assertEquals(session.endTime, session.stages.last().endTime)
    }

    @Test
    fun `a zero duration is a gap, and a night of blank sleep cells is no session and no error`() {
        val zeros = convertCsvRow(night(4 to "0", 5 to "0"), sleepCycleMapping())
        assertTrue(zeros.diagnostics.isEmpty())
        assertTrue(zeros.session().stages.isEmpty())

        val blank = convertCsvRow(night(1 to "", 4 to "", 5 to "", 7 to ""), sleepCycleMapping())
        assertTrue(blank.records.isEmpty())
        assertTrue(blank.diagnostics.isEmpty())
    }

    @Test
    fun `an end that is unreadable, not after the start or past a day costs the session only`() {
        val steps = CsvColumnMapping(
            columnIndex = 6,
            role = CsvColumnRole.METRIC,
            metric = CsvImportMetric.STEPS,
            interpretation = CsvDirectValue(CsvUnit.COUNT),
        )
        val mapping = sleepCycleMapping(extra = listOf(steps))
        val cases = listOf(
            "tomorrow" to CsvImportDiagnosticReason.UNPARSABLE_END_TIMESTAMP,
            "2026-11-01 22:00:00" to CsvImportDiagnosticReason.END_NOT_AFTER_START,
            "2026-11-03 07:04:00" to CsvImportDiagnosticReason.OUT_OF_RANGE,
        )

        for ((wokeUp, reason) in cases) {
            val conversion = convertCsvRow(night(1 to wokeUp), mapping)

            assertEquals(wokeUp, listOf(reason), conversion.diagnostics.map { it.reason })
            assertEquals(1, conversion.diagnostics.single().columnIndex)
            assertFalse(conversion.hasSession())
            assertTrue(conversion.records.any { it.recordType == StepsRecord::class })
        }
    }

    @Test
    fun `a night with notes but neither a Woke up nor a Time in bed is missing its end`() {
        val conversion = convertCsvRow(night(1 to ""), sleepCycleMapping())

        assertEquals(listOf(CsvImportDiagnosticReason.MISSING_END), conversion.diagnostics.map { it.reason })
        assertEquals(1, conversion.diagnostics.single().columnIndex)
        assertTrue(conversion.records.isEmpty())
    }

    @Test
    fun `a duration that will not read or does not fit the night costs its stages, never the session`() {
        val cases = listOf(
            night(4 to "lots") to CsvImportDiagnosticReason.UNPARSABLE_NUMBER,
            night(4 to "99999") to CsvImportDiagnosticReason.OUT_OF_RANGE,
            night(5 to "28440") to CsvImportDiagnosticReason.OUT_OF_RANGE,
        )

        for ((row, reason) in cases) {
            val conversion = convertCsvRow(row, sleepCycleMapping())

            assertEquals(listOf(reason), conversion.diagnostics.map { it.reason })
            assertEquals(at("2026-11-02T07:04:00Z"), conversion.session().endTime)
        }
    }

    @Test
    fun `a row cut off before the end column is too short, one cut before the notes still imports`() {
        val cutBeforeEnd = CsvRow(rowNumber = 2, fields = Night.take(1))
        val cutBeforeNotes = CsvRow(rowNumber = 2, fields = Night.take(6))

        assertEquals(
            listOf(CsvImportDiagnosticReason.WRONG_FIELD_COUNT),
            convertCsvRow(cutBeforeEnd, sleepCycleMapping()).diagnostics.map { it.reason },
        )
        assertNull(convertCsvRow(cutBeforeNotes, sleepCycleMapping()).session().notes)
    }

    @Test
    fun `a sleep column needs a Woke up or a Time in bed, each part once, and Woke up reads like a date`() {
        val sample = listOf(Night)
        val noEnd = sleepCycleMapping(sleep = listOf(sleepAt(4, CsvSleepPart.TIME_ASLEEP), sleepAt(7, CsvSleepPart.NOTES)))
        val twoNotes = sleepCycleMapping(
            sleep = listOf(sleepAt(1, CsvSleepPart.WOKE_UP), sleepAt(2, CsvSleepPart.NOTES), sleepAt(7, CsvSleepPart.NOTES)),
        )
        val inBedOnly = sleepCycleMapping(sleep = listOf(sleepAt(3, CsvSleepPart.TIME_IN_BED, CsvUnit.SECONDS)))
        val wokeUpUnread = sleepCycleMapping(sleep = listOf(sleepAt(2, CsvSleepPart.WOKE_UP)))

        assertEquals(listOf(CsvMappingIssue.SLEEP_NEEDS_END), validateCsvMapping(noEnd, sample))
        assertEquals(listOf(CsvMappingIssue.DUPLICATE_METRIC), validateCsvMapping(twoNotes, sample))
        assertTrue(validateCsvMapping(inBedOnly, sample).isEmpty())
        assertTrue(validateCsvMapping(sleepCycleMapping(), sample).isEmpty())
        assertEquals(
            listOf(CsvMappingIssue.TIMESTAMP_FORMAT_MATCHES_NO_SAMPLE_ROW),
            validateCsvMapping(wokeUpUnread, sample),
        )
    }

    @Test
    fun `a night's columns count as one record a row and ask for the sleep permission alone`() {
        val mapping = sleepCycleMapping()

        assertTrue(mapping.importsAnything)
        assertEquals(1, mapping.recordsPerRow)
        assertEquals(setOf(CsvSleepWritePermission), mapping.requiredWritePermissions)
    }
}
