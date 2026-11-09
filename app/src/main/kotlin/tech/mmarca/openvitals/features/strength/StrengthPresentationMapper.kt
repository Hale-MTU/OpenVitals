package tech.mmarca.openvitals.features.strength

import tech.mmarca.openvitals.core.period.PeriodLoadQuery
import tech.mmarca.openvitals.ui.components.PeriodChartValue
import tech.mmarca.openvitals.domain.model.StrengthSession
import tech.mmarca.openvitals.domain.model.StrengthSessions

object StrengthPresentationMapper {

    fun build(
        query: PeriodLoadQuery,
        sessions: List<StrengthSession>,
        selectedExercise: String?,
    ): StrengthDisplayState {
        val period = query.windows.current
        val inPeriod = sessions.filter { !it.date.isBefore(period.start) && !it.date.isAfter(period.end) }
        val byDay = inPeriod.groupBy { it.date }
        val exercises = StrengthSessions.progressExercises(inPeriod)
        val exercise = selectedExercise?.takeIf { it in exercises } ?: exercises.firstOrNull()
        val reachesToday = !period.end.isBefore(query.today)
        return StrengthDisplayState(
            stats = StrengthPeriodStats(
                sessions = inPeriod.size,
                trainingDays = byDay.size,
                sets = inPeriod.sumOf { it.setCount },
                volumeKg = inPeriod.sumOf { it.volumeKg },
                restDays = if (reachesToday) StrengthSessions.restDays(inPeriod, query.today) else null,
            ),
            sessionsByDay = byDay,
            chartValues = byDay.entries
                .sortedBy { it.key }
                .map { (date, daySessions) -> PeriodChartValue(date, daySessions.size.toDouble()) },
            progressExercises = exercises,
            selectedExercise = exercise,
            progress = exercise?.let { StrengthSessions.progress(inPeriod, it) }.orEmpty(),
            setsPerMuscle = StrengthMuscles.setsPerMuscle(inPeriod),
        )
    }

    /** The display with another exercise chosen; everything else is unchanged. */
    fun withExercise(display: StrengthDisplayState, sessions: List<StrengthSession>, exercise: String): StrengthDisplayState =
        display.copy(
            selectedExercise = exercise,
            progress = StrengthSessions.progress(sessions.filter { it.date in display.sessionsByDay }, exercise),
        )
}
