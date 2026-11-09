package tech.mmarca.openvitals.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.data.local.strength.StrengthSetDao
import tech.mmarca.openvitals.data.local.strength.StrengthSetEntity
import tech.mmarca.openvitals.data.repository.contract.ActivityRepository
import tech.mmarca.openvitals.data.repository.contract.StrengthImportSummary
import tech.mmarca.openvitals.data.repository.contract.StrengthRepository
import tech.mmarca.openvitals.domain.query.StrengthPeriodData
import tech.mmarca.openvitals.domain.model.HevyCsvParser
import tech.mmarca.openvitals.domain.model.ImportedExercise
import tech.mmarca.openvitals.domain.model.ImportedSet
import tech.mmarca.openvitals.domain.model.ImportedWorkout
import tech.mmarca.openvitals.domain.model.StrengthSessions
import tech.mmarca.openvitals.domain.model.toWorkoutKey

/**
 * Strength sessions are Health Connect's exercise sessions of the strength types, read through
 * [ActivityRepository] so the permission check and the backoff stay in one place, joined with
 * the sets of `strength_sets`, the one table for what Health Connect cannot hold.
 */
@Singleton
class StrengthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityRepository: ActivityRepository,
    private val dao: StrengthSetDao,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : StrengthRepository {

    override suspend fun loadStrengthPeriod(query: PeriodLoadQuery): StrengthPeriodData {
        val period = query.windows.current
        val sessions = activityRepository.loadWorkouts(period.start, period.end)
        val imported = withContext(dispatchers.io) {
            dao.setsBetween(
                start = period.start.atStartOfDay().toWorkoutKey(),
                endExclusive = period.end.plusDays(1).atStartOfDay().toWorkoutKey(),
            ).toWorkouts()
        }
        val count = withContext(dispatchers.io) { dao.workoutCount() }
        return StrengthPeriodData(
            sessions = withContext(dispatchers.default) { StrengthSessions.build(sessions, imported) },
            importedWorkoutCount = count,
        )
    }

    override suspend fun importHevyCsv(uri: Uri): StrengthImportSummary = withContext(dispatchers.io) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: throw FileNotFoundException("no stream")
        val parsed = HevyCsvParser.parse(text)
        val known = dao.workoutStarts().toSet()
        val added = parsed.workouts.count { it.key !in known }
        dao.replaceWorkouts(parsed.workouts.flatMap { it.toEntities() })
        StrengthImportSummary(workouts = parsed.workouts.size, sets = parsed.setCount, added = added)
    }

    private fun ImportedWorkout.toEntities(): List<StrengthSetEntity> =
        exercises.flatMapIndexed { exerciseIndex, exercise ->
            exercise.sets.mapIndexed { setIndex, set ->
                StrengthSetEntity(
                    workoutStart = key,
                    workoutEnd = end?.toWorkoutKey(),
                    workoutTitle = title,
                    exerciseIndex = exerciseIndex,
                    exerciseName = exercise.name,
                    setIndex = setIndex,
                    setType = set.type,
                    weightKg = set.kg,
                    reps = set.reps,
                    distanceKm = set.km,
                    durationSeconds = set.seconds,
                    rpe = set.rpe,
                )
            }
        }

    private fun List<StrengthSetEntity>.toWorkouts(): List<ImportedWorkout> =
        groupBy { it.workoutStart }.mapNotNull { (startText, rows) ->
            val start = runCatching { LocalDateTime.parse(startText) }.getOrNull() ?: return@mapNotNull null
            val first = rows.first()
            ImportedWorkout(
                title = first.workoutTitle,
                start = start,
                end = first.workoutEnd?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                exercises = rows.groupBy { it.exerciseIndex }.toSortedMap().values.map { sets ->
                    ImportedExercise(
                        name = sets.first().exerciseName,
                        sets = sets.sortedBy { it.setIndex }.map { row ->
                            ImportedSet(
                                kg = row.weightKg,
                                reps = row.reps,
                                km = row.distanceKm,
                                seconds = row.durationSeconds,
                                rpe = row.rpe,
                                type = row.setType,
                            )
                        },
                    )
                },
            )
        }.sortedBy { it.start }
}
