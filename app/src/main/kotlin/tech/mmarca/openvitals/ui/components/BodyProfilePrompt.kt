package tech.mmarca.openvitals.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.domain.insights.BmrInput
import tech.mmarca.openvitals.ui.theme.Spacing

/**
 * What a calorie estimate still needs from the body profile, named, with a way
 * to the profile. Shown where an estimate is left out or less exact without a
 * field; nothing is guessed in its place. Draws nothing when [missing] is empty.
 * Without [onOpenBodyProfile] it is the message alone, for a form whose draft
 * must not be left.
 */
@Composable
fun BodyProfilePrompt(
    missing: Set<BmrInput>,
    modifier: Modifier = Modifier,
    onOpenBodyProfile: (() -> Unit)? = null,
) {
    if (missing.isEmpty()) return
    // map is inline, so the composable call inside is allowed; joinToString's lambda is not.
    val labels = BmrInput.entries
        .filter { it in missing }
        .map { stringResource(it.labelRes) }
        .joinToString(separator = ", ")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.body_profile_prompt_message, labels),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onOpenBodyProfile != null) {
            OpenVitalsTonalButton(onClick = onOpenBodyProfile) {
                Text(stringResource(R.string.body_profile_prompt_action))
            }
        }
    }
}

/** The body profile field's name, as the profile screen labels it. */
val BmrInput.labelRes: Int
    get() = when (this) {
        BmrInput.SEX -> R.string.settings_body_profile_sex
        BmrInput.BIRTH_YEAR -> R.string.body_energy_calibration_birth_year
        BmrInput.WEIGHT -> R.string.settings_body_profile_weight
        BmrInput.HEIGHT -> R.string.settings_body_profile_height
    }
