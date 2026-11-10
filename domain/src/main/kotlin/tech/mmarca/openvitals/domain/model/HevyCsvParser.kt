package tech.mmarca.openvitals.domain.model

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Reads Hevy's "Export workouts" CSV: one row per set, the workout repeated on every row.
 *
 * Dates come as the phone's language wrote them ("2 nov 2026, 19:15"), in English
 * ("Nov 2, 2026, 7:15 PM") or as ISO. Month names are matched against the short and long
 * month names of the default locale and of English, so an export made under another app
 * language still reads.
 */
object HevyCsvParser {

    class Result(
        val workouts: List<ImportedWorkout>,
        val setCount: Int,
        val skippedRows: Int,
    )

    /** The file is readable but is not a Hevy workout export, or holds no workout. */
    class FormatException(message: String) : IllegalArgumentException(message)

    fun parse(text: String, locale: Locale = Locale.getDefault()): Result {
        val rows = parseCsv(text.removePrefix("﻿"))
        if (rows.size < 2) throw FormatException("empty")
        val header = rows.first().map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name)
        val title = col("title")
        val start = col("start_time")
        val end = col("end_time")
        val exercise = col("exercise_title")
        if (title < 0 || start < 0 || exercise < 0) throw FormatException("not_hevy")
        val setType = col("set_type")
        val weight = col("weight_kg")
        val reps = col("reps")
        val distance = col("distance_km")
        val duration = col("duration_seconds")
        val rpe = col("rpe")
        val months = monthNames(locale)

        class Draft(val title: String, val start: LocalDateTime, val end: LocalDateTime?) {
            val exercises = LinkedHashMap<String, MutableList<ImportedSet>>()
        }

        val drafts = LinkedHashMap<LocalDateTime, Draft>()
        var skipped = 0
        var sets = 0
        for (row in rows.drop(1)) {
            fun cell(index: Int): String = if (index < 0) "" else row.getOrNull(index)?.trim().orEmpty()
            val startTime = parseDateTime(cell(start), months)
            val name = cell(exercise)
            if (startTime == null || name.isEmpty()) {
                skipped++
                continue
            }
            val draft = drafts.getOrPut(startTime) { Draft(cell(title), startTime, parseDateTime(cell(end), months)) }
            val type = cell(setType).takeIf { it.isNotEmpty() && it != "normal" }
            draft.exercises.getOrPut(name) { mutableListOf() } += ImportedSet(
                kg = number(cell(weight)),
                reps = number(cell(reps))?.toInt(),
                km = number(cell(distance)),
                seconds = number(cell(duration))?.toInt(),
                rpe = number(cell(rpe)),
                type = type,
            )
            sets++
        }
        if (drafts.isEmpty()) throw FormatException("no_workouts")
        val workouts = drafts.values
            .map { draft ->
                ImportedWorkout(
                    title = draft.title,
                    start = draft.start,
                    end = draft.end,
                    exercises = draft.exercises.map { (name, list) -> ImportedExercise(name, list) },
                )
            }
            .sortedBy { it.start }
        return Result(workouts, sets, skipped)
    }

    private fun number(raw: String): Double? =
        raw.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Lower-cased month names, short and long, of the locale and of English, to the month number. */
    internal fun monthNames(locale: Locale): Map<String, Int> {
        val names = mutableMapOf<String, Int>()
        for (month in java.time.Month.entries) {
            for (candidate in listOf(locale, Locale.ENGLISH)) {
                for (style in listOf(TextStyle.SHORT, TextStyle.FULL, TextStyle.SHORT_STANDALONE, TextStyle.FULL_STANDALONE)) {
                    val name = month.getDisplayName(style, candidate).lowercase(candidate).trimEnd('.')
                    if (name.isNotEmpty()) names.putIfAbsent(name, month.value)
                }
            }
        }
        return names
    }

    private val isoPattern = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{1,2}):(\d{2})""")
    private val dayFirstPattern = Regex("""^(\d{1,2})\.?\s+(\p{L}+)\.?\s+(\d{4}),?\s+(\d{1,2})[:.](\d{2})""")
    private val monthFirstPattern = Regex("""^(\p{L}+)\.?\s+(\d{1,2}),?\s+(\d{4}),?\s+(\d{1,2}):(\d{2})\s*([AaPp]\.?[Mm]\.?)?""")
    private val numericPattern = Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4}),?\s+(\d{1,2})[:.](\d{2})""")

    internal fun parseDateTime(raw: String, months: Map<String, Int>): LocalDateTime? {
        val text = raw.trim()
        isoPattern.find(text)?.destructured?.let { (y, mo, d, h, mi) ->
            return build(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
        }
        numericPattern.find(text)?.destructured?.let { (d, mo, y, h, mi) ->
            return build(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
        }
        dayFirstPattern.find(text)?.destructured?.let { (d, mon, y, h, mi) ->
            val month = monthOf(mon, months) ?: return null
            return build(y.toInt(), month, d.toInt(), h.toInt(), mi.toInt())
        }
        monthFirstPattern.find(text)?.let { match ->
            val (mon, d, y, h, mi) = match.destructured
            val month = monthOf(mon, months) ?: return null
            var hour = h.toInt()
            val meridiem = match.groupValues[6].lowercase().replace(".", "")
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            return build(y.toInt(), month, d.toInt(), hour, mi.toInt())
        }
        return null
    }

    private fun monthOf(name: String, months: Map<String, Int>): Int? {
        val key = name.lowercase().trimEnd('.')
        return months[key] ?: months.entries.firstOrNull { (known, _) -> known.startsWith(key) && key.length >= 3 }?.value
    }

    private fun build(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime? =
        runCatching { LocalDateTime.of(year, month, day, hour, minute) }.getOrNull()

    /** RFC 4180: quoted fields may hold commas, newlines and doubled quotes. */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\n', '\r' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(field.toString())
                        field.setLength(0)
                        if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
