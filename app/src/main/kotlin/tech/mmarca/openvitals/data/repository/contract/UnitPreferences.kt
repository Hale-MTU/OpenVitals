package tech.mmarca.openvitals.data.repository.contract

import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openvitals.domain.preferences.UnitQuantity
import tech.mmarca.openvitals.domain.preferences.UnitSystem
import tech.mmarca.openvitals.domain.preferences.UnitSystemPreference

/** What values display in. Stored health data stays metric. */
interface UnitPreferences {

    /** Already resolved: never the SYSTEM preference itself. */
    val unitSystem: UnitSystem

    /** The choice itself, SYSTEM included, as OpenWatch is told it. */
    val unitSystemPreferenceFlow: StateFlow<UnitSystemPreference>

    /** The override for one quantity, or null when it follows [unitSystem]. */
    fun unitOverride(quantity: UnitQuantity): UnitSystem?
}
