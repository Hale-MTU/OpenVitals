package tech.mmarca.openvitals.features.strength

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import tech.mmarca.openvitals.ui.components.PeriodChartValue
import tech.mmarca.openvitals.domain.model.StrengthSession
import tech.mmarca.openvitals.domain.model.StrengthProgressPoint

/** What the period adds up to; the ViewModel computes it once, off the main thread. */
@Immutable
data class StrengthPeriodStats(
    val sessions: Int = 0,
    val trainingDays: Int = 0,
    val sets: Int = 0,
    val volumeKg: Double = 0.0,
    /** Days since the last session, when the period reaches today. */
    val restDays: Int? = null,
)

@Immutable
data class StrengthDisplayState(
    val stats: StrengthPeriodStats = StrengthPeriodStats(),
    val sessionsByDay: Map<LocalDate, List<StrengthSession>> = emptyMap(),
    /** One value per training day: the day's session count, for the period bar chart. */
    val chartValues: List<PeriodChartValue> = emptyList(),
    /** Exercises with loads, most practised first: the choices of the progress chart. */
    val progressExercises: List<String> = emptyList(),
    val selectedExercise: String? = null,
    val progress: List<StrengthProgressPoint> = emptyList(),
    val setsPerMuscle: Map<MuscleGroup, Double> = emptyMap(),
) {
    val hasData: Boolean get() = sessionsByDay.isNotEmpty()
    val trainedDays: Set<LocalDate> get() = sessionsByDay.keys
}
