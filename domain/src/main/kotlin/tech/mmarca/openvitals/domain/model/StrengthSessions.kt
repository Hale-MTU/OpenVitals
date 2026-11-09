package tech.mmarca.openvitals.domain.model

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Joins the places a strength session lives.
 *
 * - The training app's session in Health Connect: title and times, from any app.
 * - That app's export, imported: the exercises, sets and loads; matched by start minute.
 * - A watch's session of the same workout: folded into the first, so a workout recorded by
 *   two apps is one session.
 *
 * An app's log is often filled in after the workout, so an imported workout that overlaps no
 * session takes the strength session of the same day, whose times are the real ones.
 */
object StrengthSessions {

    private const val MATCH_TOLERANCE_MINUTES = 3L
    private const val MIN_OVERLAP_SHARE = 0.4
    private const val MIN_SAME_DAY_MINUTES = 15L

    /** The Health Connect types this screen is about. */
    val strengthTypes: Set<Int> = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    )

    /** Types a watch used for weights before it had a strength mode. Matched only by time. */
    private val looseTypes: Set<Int> = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
    )

    fun build(
        healthConnect: List<ExerciseData>,
        imported: List<ImportedWorkout>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StrengthSession> {
        val candidates = healthConnect
            .filter { it.exerciseType in strengthTypes || it.exerciseType in looseTypes }
            .map { Candidate(it, zone) }
            .sortedBy { it.start }
        val used = mutableSetOf<String>()

        val fromImport = imported.sortedBy { it.start }.map { workout ->
            // The session the app itself wrote: same start minute, whatever its type.
            val own = candidates
                .filter { it.data.id !in used }
                .filter { abs(Duration.between(it.start, workout.start).toMinutes()) <= MATCH_TOLERANCE_MINUTES }
                .minByOrNull { abs(Duration.between(it.start, workout.start).toMinutes()) }
            own?.let { used += it.data.id }
            val strength = candidates.filter { it.data.id !in used && it.data.exerciseType in strengthTypes }
            // A watch's record of the same workout: overlapping, or the day's strength session.
            val watch = (own ?: Candidate(workout, zone)).let { anchor ->
                bestOverlap(anchor.start, anchor.end, strength)
                    ?: bestOverlap(anchor.start, anchor.end, candidates.filter { it.data.id !in used && it.data.exerciseType in looseTypes })
                    ?: sameDay(anchor.start, strength)
            }
            watch?.let { used += it.data.id }
            val timed = watch ?: own
            StrengthSession(
                key = workout.key,
                title = own?.data?.title?.takeIf { it.isNotBlank() } ?: workout.title,
                date = workout.start.toLocalDate(),
                start = timed?.start ?: workout.start,
                end = timed?.end ?: workout.end,
                exerciseType = own?.data?.exerciseType ?: watch?.data?.exerciseType,
                healthConnectId = own?.data?.id ?: watch?.data?.id,
                sources = listOfNotNull(own?.data?.source, watch?.data?.source).distinct(),
                exercises = workout.exercises,
            )
        }

        // What no import explains: strength sessions on their own, two apps' records folded into one.
        val rest = candidates.filter { it.data.id !in used && it.data.exerciseType in strengthTypes }
        val standalone = mutableListOf<StrengthSession>()
        for (candidate in rest) {
            if (candidate.data.id in used) continue
            used += candidate.data.id
            val twin = bestOverlap(candidate.start, candidate.end, rest.filter { it.data.id !in used })
            twin?.let { used += it.data.id }
            val titled = listOfNotNull(candidate, twin).firstOrNull { !it.data.title.isNullOrBlank() }
            standalone += StrengthSession(
                key = "hc-" + candidate.data.id,
                title = titled?.data?.title.orEmpty(),
                date = candidate.start.toLocalDate(),
                start = minOf(candidate.start, twin?.start ?: candidate.start),
                end = maxOf(candidate.end, twin?.end ?: candidate.end),
                exerciseType = candidate.data.exerciseType,
                healthConnectId = candidate.data.id,
                sources = listOfNotNull(candidate.data.source, twin?.data?.source).distinct(),
                exercises = emptyList(),
            )
        }
        return (fromImport + standalone).sortedByDescending { it.start }
    }

    private class Candidate(val data: ExerciseData, val start: LocalDateTime, val end: LocalDateTime) {
        constructor(data: ExerciseData, zone: ZoneId) : this(
            data,
            LocalDateTime.ofInstant(data.startTime, zone),
            LocalDateTime.ofInstant(data.endTime, zone),
        )

        /** An imported workout as an anchor for the watch search; never part of the result. */
        constructor(workout: ImportedWorkout, zone: ZoneId) : this(
            ExerciseData(
                id = "import-" + workout.key,
                title = workout.title,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
                startTime = workout.start.atZone(zone).toInstant(),
                endTime = (workout.end ?: workout.start).atZone(zone).toInstant(),
                durationMs = 0,
                source = "",
            ),
            workout.start,
            workout.end ?: workout.start,
        )
    }

    private fun bestOverlap(start: LocalDateTime, end: LocalDateTime, candidates: List<Candidate>): Candidate? {
        if (!end.isAfter(start)) return null
        val length = Duration.between(start, end).toMinutes().coerceAtLeast(1)
        return candidates
            .map { candidate ->
                val overlapStart = maxOf(start, candidate.start)
                val overlapEnd = minOf(end, candidate.end)
                val overlap = Duration.between(overlapStart, overlapEnd).toMinutes()
                val shorter = minOf(length, Duration.between(candidate.start, candidate.end).toMinutes().coerceAtLeast(1))
                candidate to overlap.toDouble() / shorter
            }
            .filter { (_, share) -> share >= MIN_OVERLAP_SHARE }
            .maxByOrNull { (_, share) -> share }
            ?.first
    }

    /** The strength session of the same day, the closest in time when there are more. */
    private fun sameDay(start: LocalDateTime, candidates: List<Candidate>): Candidate? =
        candidates
            .filter { it.start.toLocalDate() == start.toLocalDate() }
            .filter { Duration.between(it.start, it.end).toMinutes() >= MIN_SAME_DAY_MINUTES }
            .minByOrNull { abs(Duration.between(it.start, start).toMinutes()) }

    /** Days since the last session on or before [today]; null without one. */
    fun restDays(sessions: List<StrengthSession>, today: LocalDate): Int? =
        sessions.map { it.date }.filter { !it.isAfter(today) }.maxOrNull()
            ?.let { (ChronoUnit.DAYS.between(it, today)).coerceAtLeast(0).toInt() }

    /** Exercises with loads, most practised first: the choices of the progress chart. */
    fun progressExercises(sessions: List<StrengthSession>): List<String> =
        sessions.flatMap { session ->
            session.exercises
                .filter { !it.isAssisted && it.sets.any { set -> set.estimatedOneRepMax != null } }
                .map { it.name }
                .distinct()
        }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** The best estimated one-rep max of each day, oldest first. */
    fun progress(sessions: List<StrengthSession>, exercise: String): List<StrengthProgressPoint> =
        sessions
            .mapNotNull { session ->
                val best = session.exercises
                    .filter { it.name == exercise && !it.isAssisted }
                    .flatMap { it.sets }
                    .mapNotNull { it.estimatedOneRepMax }
                    .maxOrNull()
                best?.let { StrengthProgressPoint(session.date, it) }
            }
            .groupBy { it.date }
            .map { (_, points) -> points.maxBy { it.oneRepMaxKg } }
            .sortedBy { it.date }
}
