package tech.mmarca.openvitals.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * An entry an optional integration adds below the Settings root. The default build binds none
 * (`di/ExtensionsModule`), so the root renders exactly as it always has. The opt-in VertMatch
 * build (`app/src/vertmatch`, see vertmatch/README.md) binds one.
 */
interface SettingsExtension {
    @Composable
    fun Entry(modifier: Modifier)
}
