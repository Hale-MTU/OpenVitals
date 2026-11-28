package tech.mmarca.openvitals.data.repository.contract

import kotlinx.coroutines.flow.StateFlow

/** The daily drinking target, in liters. Clamped on write; read back for the stored value. */
interface HydrationGoalPreferences {

    var hydrationDailyGoalLiters: Double

    /**
     * The goal as the user chose it, or null while they never did and the default holds. Only a
     * chosen goal is shared with OpenWatch, so a default never replaces a goal set there.
     */
    val hydrationGoalChoiceLitersFlow: StateFlow<Double?>

    /** Back to the default goal, as when the goal was cleared in another app. */
    fun resetHydrationDailyGoal()
}
