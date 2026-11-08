package tech.mmarca.openvitals.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.data.sync.BmrEstimateService
import tech.mmarca.openvitals.openwatch.relativeTime
import tech.mmarca.openvitals.ui.components.SectionHeader
import tech.mmarca.openvitals.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openvitals.ui.theme.LayoutMetrics

/** Body profile: the declared body, the BMR estimate and the caffeine clearance factors. Shares [BodySettingsViewModel] with Recovery. */
@Composable
fun BodyProfileSettingsScreen(viewModel: BodySettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val openWatchSync by viewModel.openWatchProfileSync.collectAsStateWithLifecycle()

    // The write permission is granted in Health Connect, so it is re-read on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val permissionLauncher = rememberHealthConnectPermissionLauncher(onResult = { viewModel.refresh() })

    SettingsSectionList {
        item { SectionHeader(stringResource(SettingsSection.BODY_PROFILE.titleRes)) }
        openWatchSync?.let { updatedAt ->
            item {
                Text(
                    text = if (updatedAt > 1_000_000_000_000L) {
                        stringResource(R.string.body_profile_openwatch_synced_updated, relativeTime(updatedAt))
                    } else {
                        stringResource(R.string.body_profile_openwatch_synced)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter, vertical = 4.dp),
                )
            }
        }
        item {
            BodyProfileCard(
                profile = state.bodyProfile,
                // The card's only unit-sensitive input is body weight.
                unitSystem = state.weightUnitSystem,
                onSave = viewModel::updateBodyProfile,
                weightMeasured = state.bodyProfileWeightMeasured,
                heightMeasured = state.bodyProfileHeightMeasured,
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter),
            )
        }
        item { SettingsCardSpacer() }
        item {
            BmrEstimateCard(
                enabled = state.bmrEstimateEnabled,
                todayKcal = state.bmrEstimateTodayKcal,
                missingInputs = state.bmrEstimateMissingInputs,
                writePermissionMissing = state.bmrEstimateWritePermissionMissing,
                onEnabledChange = viewModel::setBmrEstimateEnabled,
                onGrantPermission = { permissionLauncher.launch(BmrEstimateService.RequiredPermissions) },
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter),
            )
        }
        item { SettingsCardSpacer() }
        item {
            MetabolismCard(
                preferences = state.caffeinePreferences,
                onSave = viewModel::updateCaffeinePreferences,
                modifier = Modifier.padding(horizontal = LayoutMetrics.screenGutter),
            )
        }
    }
}
