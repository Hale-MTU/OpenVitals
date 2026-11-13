package tech.mmarca.openvitals.features.manualentry.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.domain.preferences.UnitSystem

/*
 * A set's load is kept and stored in kilograms; these turn it to and from the
 * unit it is typed in. Text that is not a number passes through untouched, so
 * the form's own validation is what rejects it.
 */

/** What the user typed, as the kilogram text the form keeps. */
internal fun loadInputToKgText(input: String, unitSystem: UnitSystem): String = when (unitSystem) {
    UnitSystem.METRIC -> input
    UnitSystem.IMPERIAL -> input.toPositiveDoubleOrNull()
        ?.let { pounds -> (pounds / UnitFormatter.PoundsPerKilogram).toWeightKgText() }
        ?: input
}

/** The form's kilogram text, as the user's unit shows it. */
internal fun kgTextToLoadInput(kgText: String, unitSystem: UnitSystem): String = when (unitSystem) {
    UnitSystem.METRIC -> kgText
    UnitSystem.IMPERIAL -> kgText.toPositiveDoubleOrNull()
        ?.let { kg -> (kg * UnitFormatter.PoundsPerKilogram).toInputText(1) }
        ?: kgText
}

internal class LoadInput(val text: String, val onTextChanged: (String) -> Unit)

/**
 * A load field in the user's unit over a form that keeps kilograms. The typed
 * text is shown as typed ("135.") for as long as it still stands for the
 * form's value; once the form changes underneath, the form's value is shown.
 */
@Composable
internal fun rememberLoadInput(
    kgText: String,
    unitSystem: UnitSystem,
    onKgTextChanged: (String) -> Unit,
): LoadInput {
    var typed by remember(unitSystem) { mutableStateOf<String?>(null) }
    val shown = typed?.takeIf { loadInputToKgText(it, unitSystem) == kgText }
        ?: kgTextToLoadInput(kgText, unitSystem)
    return LoadInput(shown) { text ->
        typed = text
        onKgTextChanged(loadInputToKgText(text, unitSystem))
    }
}
