package tech.mmarca.openvitals.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A workout imported from a training app's export (Hevy's CSV today).
 *
 * Health Connect receives only the session from the app (title, times), never the
 * exercises, so sets, reps and loads come from the export and are kept in Room.
 */
data class ImportedWorkout(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val exercises: List<ImportedExercise>,
) {
    /** The export writes one row per set; a workout is keyed by its start minute. */
    val key: String get() = start.toWorkoutKey()
}

/** The Room key of a workout: its local start, to the minute, as ISO text. */
fun LocalDateTime.toWorkoutKey(): String = withSecond(0).withNano(0).toString()

data class ImportedExercise(
    val name: String,
    val sets: List<ImportedSet>,
) {
    /** Assisted machines log the assistance: more kilograms is the easier set. */
    val isAssisted: Boolean
        get() = AssistedPattern.containsMatchIn(name)

    /** Time or distance, never reps: a treadmill or rower row in a strength export. */
    val isCardio: Boolean
        get() = sets.isNotEmpty() && sets.all { it.reps == null && (it.seconds != null || it.km != null) }

    /** Working sets: the ones with a load or reps, so a cardio row counts none. */
    val workingSetCount: Int get() = sets.count { it.reps != null || it.kg != null }

    private companion object {
        val AssistedPattern = Regex("assist", RegexOption.IGNORE_CASE)
    }
}

data class ImportedSet(
    val kg: Double? = null,
    val reps: Int? = null,
    val km: Double? = null,
    val seconds: Int? = null,
    val rpe: Double? = null,
    /** The export's set type when it is not a working set: warm-up, drop set, failure. */
    val type: String? = null,
) {
    /** Epley's estimated one-rep max, the curve the progress chart draws. */
    val estimatedOneRepMax: Double?
        get() = if (kg != null && kg > 0 && reps != null && reps > 0) kg * (1 + reps / 30.0) else null
}

/**
 * One strength session as the screen shows it: the Health Connect session, the imported
 * sets matched to it, and any second app's record of the same workout folded in.
 */
data class StrengthSession(
    val key: String,
    val title: String,
    val date: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    /** The Health Connect type, when a session was read from there. */
    val exerciseType: Int?,
    /** The Health Connect id of the session, when there is one: the way to its detail. */
    val healthConnectId: String?,
    /** The apps that recorded it, Health Connect package names. */
    val sources: List<String>,
    val exercises: List<ImportedExercise>,
) {
    val durationMinutes: Long?
        get() = end?.let { java.time.Duration.between(start, it).toMinutes() }?.takeIf { it > 0 }

    val hasSets: Boolean get() = exercises.any { it.sets.isNotEmpty() }

    val setCount: Int get() = exercises.sumOf { it.workingSetCount }

    /** Kilograms lifted: load × reps over every set, assisted machines left out. */
    val volumeKg: Double
        get() = exercises.filterNot { it.isAssisted }.sumOf { exercise ->
            exercise.sets.sumOf { (it.kg ?: 0.0) * (it.reps ?: 0) }
        }
}

data class StrengthProgressPoint(
    val date: LocalDate,
    val oneRepMaxKg: Double,
)
