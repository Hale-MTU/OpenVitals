package tech.mmarca.openvitals.features.imports.csv

import tech.mmarca.openvitals.core.privacy.PrivacyRedactor
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import tech.mmarca.openvitals.domain.model.SleepStage
import tech.mmarca.openvitals.domain.model.sleepDurationMsFromStages

/**
 * Sleep in the CSV importer. Like blood pressure, a sleep column is not a
 * metric: a row's sleep columns make one sleep session, from the row's date
 * and time to its "Woke up" column, or for its "Time in bed". Health Connect
 * keeps a session's span, its notes and its stages; a sleep app's quality
 * score, snoring, room noise or alertness has no field there, so those
 * columns stay unmapped. Pure, like the row converter.
 */

/** The Health Connect record class name. A segment of the clientRecordId. */
const val CSV_SLEEP_TARGET_TYPE = "SleepSessionRecord"

/** The write permission any sleep column needs. */
val CsvSleepWritePermission: String = HealthPermission.getWritePermission(SleepSessionRecord::class)

/** A session longer than this is a misread date, not a night. */
private val MaxSleepSessionSpan: Duration = Duration.ofHours(24)

/** What a sleep column holds. */
enum class CsvSleepPart {
    /** When the session ends, read with the date and time settings. Wins over [TIME_IN_BED] when both are filled. */
    WOKE_UP,

    /** How long the session lasts from the row's date and time, for a row whose [WOKE_UP] is blank or unmapped. */
    TIME_IN_BED,

    /** How long falling asleep took: awake in bed from the start of the session. */
    TIME_TO_FALL_ASLEEP,

    /** How long was spent asleep. The rest of the session counts as awake in bed. */
    TIME_ASLEEP,

    /** Free text, kept as the session's notes. */
    NOTES,
    ;

    /** Whether the column holds a duration, read in the unit the column picks. */
    val isDuration: Boolean get() = this == TIME_IN_BED || this == TIME_TO_FALL_ASLEEP || this == TIME_ASLEEP

    /** Whether the column says where the session ends. A sleep mapping needs one. */
    val isSpan: Boolean get() = this == WOKE_UP || this == TIME_IN_BED
}

/**
 * The units a duration column can be read in. Minutes first: a log kept by
 * hand counts minutes, and a header such as `Time asleep (seconds)` picks seconds.
 */
private val CsvSleepDurationInterpretations: List<CsvValueInterpretation> = listOf(
    CsvDirectValue(CsvUnit.MINUTES),
    CsvDirectValue(CsvUnit.SECONDS),
    CsvDirectValue(CsvUnit.HOURS),
)

/** The interpretations [part]'s column offers, or null for a time or a text. */
fun csvSleepInterpretations(part: CsvSleepPart): List<CsvValueInterpretation>? =
    CsvSleepDurationInterpretations.takeIf { part.isDuration }

/** [part]'s interpretation for a unit read off its header, or null. */
fun csvSleepInterpretationForUnit(part: CsvSleepPart, unit: CsvUnit): CsvValueInterpretation? =
    csvSleepInterpretations(part)?.firstOrNull { (it as? CsvDirectValue)?.unit == unit }

/** `7:32` or `7:32:10`: hours, minutes and optional seconds. */
private val ClockDurationRegex = Regex("""^(\d{1,2}):([0-5]\d)(?::([0-5]\d))?$""")

/**
 * A duration cell in milliseconds, or null. A cell written as a clock, `7:32`
 * or `7:32:10`, reads as hours and minutes whatever the unit, the way older
 * Sleep Cycle exports and spreadsheets write one; any other colon is not a
 * number. Everything else is a number in [unit].
 */
fun parseCsvDurationMillis(text: String, unit: CsvUnit): Long? {
    val trimmed = text.trim()
    ClockDurationRegex.matchEntire(trimmed)?.let { match ->
        val (hours, minutes, seconds) = match.destructured
        val totalSeconds = (hours.toLong() * 60 + minutes.toLong()) * 60 + (seconds.toLongOrNull() ?: 0L)
        return totalSeconds * 1_000
    }
    if (':' in trimmed) return null
    val value = parseCsvNumber(trimmed) ?: return null
    return convertCsvValueToCanonical(value, unit).roundToLong()
}

/**
 * The row's sleep session, or null. A row whose sleep cells are all blank has
 * no session, which is not an error. A session costs the row's sleep, with
 * one diagnostic, when it has no end: Woke up and Time in bed blank or
 * unreadable, an end not after the start, or a span past a day. A bad time
 * to fall asleep or time asleep costs only the stages it would have made.
 * [start] is the row's resolved timestamp.
 */
internal fun convertCsvSleepRow(
    row: CsvRow,
    mapping: CsvImportMapping,
    start: CsvInstant,
    diagnostics: MutableList<CsvImportDiagnostic>,
): CsvConvertedRecord? {
    val columns = mapping.sleepColumns
    if (columns.isEmpty()) return null
    if (columns.all { row.cell(it.columnIndex) == null }) return null

    val end = row.sleepEnd(mapping, start, diagnostics) ?: return null
    val spanMillis = Duration.between(start.utc, end.utc).toMillis()
    // Falling asleep for the whole session would leave no sleep, which reads as asleep throughout.
    val fallAsleep = row.sleepDurationMillis(mapping, CsvSleepPart.TIME_TO_FALL_ASLEEP, spanMillis - 1, diagnostics)
    val asleep = row.sleepDurationMillis(mapping, CsvSleepPart.TIME_ASLEEP, spanMillis, diagnostics)

    return buildCsvSleepRecord(
        start = start,
        end = end,
        stages = csvSleepStages(start.utc, end.utc, fallAsleep, asleep),
        notes = mapping.sleepColumn(CsvSleepPart.NOTES)?.let { row.cell(it.columnIndex) },
    )
}

/**
 * Where the session ends: Woke up when it holds something, else the start
 * plus Time in bed. Neither, an end that will not read, one not after the
 * start, or a span past [MaxSleepSessionSpan]: null, with the reason added.
 */
private fun CsvRow.sleepEnd(
    mapping: CsvImportMapping,
    start: CsvInstant,
    diagnostics: MutableList<CsvImportDiagnostic>,
): CsvInstant? {
    fun reject(reason: CsvImportDiagnosticReason, column: CsvColumnMapping?, detail: String? = null): Nothing? {
        diagnostics += CsvImportDiagnostic(
            rowNumber = rowNumber,
            reason = reason,
            columnIndex = column?.columnIndex,
            detail = detail,
        )
        return null
    }

    val wokeUpColumn = mapping.sleepColumn(CsvSleepPart.WOKE_UP)
    val inBedColumn = mapping.sleepColumn(CsvSleepPart.TIME_IN_BED)
    val wokeUpText = wokeUpColumn?.let { cell(it.columnIndex) }
    val spanColumn: CsvColumnMapping?
    val end: CsvInstant
    if (wokeUpText != null) {
        spanColumn = wokeUpColumn
        end = resolveCsvInstant(wokeUpText, mapping.dateTime)
            ?: return reject(CsvImportDiagnosticReason.UNPARSABLE_END_TIMESTAMP, wokeUpColumn, PrivacyRedactor.shapeOf(wokeUpText))
    } else {
        spanColumn = inBedColumn
        val inBedText = inBedColumn?.let { cell(it.columnIndex) }
        if (inBedColumn == null || inBedText == null) {
            return reject(CsvImportDiagnosticReason.MISSING_END, wokeUpColumn ?: inBedColumn)
        }
        val unit = (inBedColumn.effectiveInterpretation as? CsvDirectValue)?.unit ?: CsvUnit.MINUTES
        val millis = parseCsvDurationMillis(inBedText, unit)
            ?: return reject(CsvImportDiagnosticReason.UNPARSABLE_NUMBER, inBedColumn, PrivacyRedactor.shapeOf(inBedText))
        end = CsvInstant(utc = start.utc.plusMillis(millis), offset = start.offset)
    }
    if (!end.utc.isAfter(start.utc)) return reject(CsvImportDiagnosticReason.END_NOT_AFTER_START, spanColumn)
    if (Duration.between(start.utc, end.utc) > MaxSleepSessionSpan) {
        return reject(CsvImportDiagnosticReason.OUT_OF_RANGE, spanColumn)
    }
    return end
}

/**
 * The row's [part] in milliseconds, or null: blank or zero is a gap, a cell
 * that is not a duration or lies outside 0..[maxMillis] is rejected.
 */
private fun CsvRow.sleepDurationMillis(
    mapping: CsvImportMapping,
    part: CsvSleepPart,
    maxMillis: Long,
    diagnostics: MutableList<CsvImportDiagnostic>,
): Long? {
    val column = mapping.sleepColumn(part) ?: return null
    val text = cell(column.columnIndex) ?: return null
    val unit = (column.effectiveInterpretation as? CsvDirectValue)?.unit ?: return null
    val millis = parseCsvDurationMillis(text, unit)
    if (millis == null) {
        diagnostics += CsvImportDiagnostic(
            rowNumber = rowNumber,
            reason = CsvImportDiagnosticReason.UNPARSABLE_NUMBER,
            columnIndex = column.columnIndex,
            detail = PrivacyRedactor.shapeOf(text),
        )
        return null
    }
    if (millis < 0L || millis > maxMillis) {
        diagnostics += CsvImportDiagnostic(
            rowNumber = rowNumber,
            reason = CsvImportDiagnosticReason.OUT_OF_RANGE,
            columnIndex = column.columnIndex,
        )
        return null
    }
    return millis.takeIf { it > 0L }
}

/**
 * The session's stages from the two durations a sleep app exports. The time
 * to fall asleep is awake in bed from the start; sleep follows for the time
 * asleep, or to the end without one, and stops at the end. What is left is
 * awake in bed at the end, because the file does not say when in the night
 * it fell; the totals are the file's either way. With neither duration there
 * are no stages, and the session counts as asleep throughout, like an
 * unstaged session from any app.
 */
fun csvSleepStages(
    start: Instant,
    end: Instant,
    fallAsleepMillis: Long?,
    asleepMillis: Long?,
): List<SleepSessionRecord.Stage> {
    if (fallAsleepMillis == null && asleepMillis == null) return emptyList()
    val onset = start.plusMillis(fallAsleepMillis ?: 0L)
    val wake = asleepMillis?.let { minOf(onset.plusMillis(it), end) } ?: end
    return buildList {
        if (onset.isAfter(start)) add(SleepSessionRecord.Stage(start, onset, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
        if (wake.isAfter(onset)) add(SleepSessionRecord.Stage(onset, wake, SleepSessionRecord.STAGE_TYPE_SLEEPING))
        if (end.isAfter(wake)) add(SleepSessionRecord.Stage(wake, end, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
    }
}

/** The session from [start] to [end], already validated, with [stages] inside it. Untitled: the app names a night itself. */
fun buildCsvSleepRecord(
    start: CsvInstant,
    end: CsvInstant,
    stages: List<SleepSessionRecord.Stage>,
    notes: String?,
): CsvConvertedRecord {
    val clientRecordId = buildCsvClientRecordId(CSV_SLEEP_TARGET_TYPE, start.utc)
    return CsvConvertedRecord(
        metric = null,
        targetType = CSV_SLEEP_TARGET_TYPE,
        recordType = SleepSessionRecord::class,
        clientRecordId = clientRecordId,
        instant = start.utc,
        canonicalValue = null,
        record = SleepSessionRecord(
            startTime = start.utc,
            startZoneOffset = start.offset,
            endTime = end.utc,
            endZoneOffset = end.offset,
            metadata = csvMetadata(clientRecordId),
            title = null,
            notes = notes,
            stages = stages,
        ),
    )
}

/** One session as the sleep screen will count it. */
data class CsvSleepPreview(val inBedMillis: Long, val asleepMillis: Long)

/**
 * Time in bed and time asleep for each session the sampled [rows] would be
 * written as, from the real conversion, asleep counted the way the sleep
 * screen counts it, so the preview shows what lands.
 */
fun previewSleepSessions(
    rows: List<List<String>>,
    mapping: CsvImportMapping,
): List<CsvSleepPreview> =
    previewRecords(rows, mapping)
        .mapNotNull { it.record as? SleepSessionRecord }
        .map { session ->
            val inBed = Duration.between(session.startTime, session.endTime).toMillis()
            val stages = session.stages.map { SleepStage(it.startTime, it.endTime, it.stage) }
            CsvSleepPreview(inBedMillis = inBed, asleepMillis = sleepDurationMsFromStages(stages, inBed))
        }

/** "7h 05m", the way the sleep screen writes a night. */
fun csvSleepDurationText(millis: Long): String {
    val hours = millis / 3_600_000
    val minutes = (millis % 3_600_000) / 60_000
    return "${hours}h ${minutes.toString().padStart(2, '0')}m"
}
