package tech.mmarca.openvitals.domain.query

import tech.mmarca.openvitals.domain.model.StrengthSession

/** The strength sessions of one period, newest first, with how many workouts the import holds. */
data class StrengthPeriodData(
    val sessions: List<StrengthSession> = emptyList(),
    val importedWorkoutCount: Int = 0,
)
