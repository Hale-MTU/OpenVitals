package tech.mmarca.openvitals.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openvitals.data.repository.contract.HydrationGoalPreferences

/**
 * The daily drinking target, in the app's preferences file ([PreferencesRepository] hands it
 * here). A goal the user never chose is no entry, so [hydrationGoalChoiceLitersFlow] can tell it
 * from the default.
 */
internal class HydrationGoalStore(private val prefs: SharedPreferences) : HydrationGoalPreferences {

    private val choice = MutableStateFlow(readChoice())

    override val hydrationGoalChoiceLitersFlow: StateFlow<Double?> = choice.asStateFlow()

    override var hydrationDailyGoalLiters: Double
        get() = readChoice() ?: DEFAULT_LITERS
        @Synchronized set(value) {
            prefs.edit { putFloat(KEY, value.coerceIn(MIN_LITERS, MAX_LITERS).toFloat()) }
            choice.value = readChoice()
        }

    @Synchronized
    override fun resetHydrationDailyGoal() {
        prefs.edit { remove(KEY) }
        choice.value = null
    }

    private fun readChoice(): Double? =
        if (prefs.contains(KEY)) prefs.getFloat(KEY, DEFAULT_LITERS.toFloat()).toDouble() else null

    companion object {
        const val DEFAULT_LITERS = 2.0
        private const val MIN_LITERS = 0.25
        private const val MAX_LITERS = 10.0
        private const val KEY = "hydration_daily_goal_liters"
    }
}
