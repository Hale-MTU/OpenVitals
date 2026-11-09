package tech.mmarca.openvitals.features.strength

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.healthconnect.HealthConnectFeature
import tech.mmarca.openvitals.ui.components.MetricAction
import tech.mmarca.openvitals.ui.components.MetricDetailScaffold
import tech.mmarca.openvitals.ui.components.WithHealthConnectFeatureScreen

/** Hevy exports as CSV, which document providers label in several ways. */
private val CsvMimeTypes = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")

/**
 * Strength training: the period's sessions on a calendar, their sets from an imported
 * export, the muscles they worked and the estimated one-rep max over time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrengthScreen(
    viewModel: StrengthViewModel,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onOpenActivity: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importHevyCsv)
    }
    val pickFile = { picker.launch(CsvMimeTypes) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.resumeCurrentPeriod(refreshCurrent = true)
    }

    WithHealthConnectFeatureScreen(
        feature = HealthConnectFeature.ACTIVITIES,
        isLoading = state.isLoading,
        showInlineSyncBanner = false,
    ) { hcUx ->
        MetricDetailScaffold(
            isLoading = state.isLoading,
            selectedRange = state.selectedRange,
            selectedDate = state.selectedDate,
            screenError = state.error,
            onRefresh = viewModel::load,
            onSelectRange = viewModel::selectRange,
            onPreviousPeriod = viewModel::previousPeriod,
            onNextPeriod = viewModel::nextPeriod,
            onSelectDate = viewModel::selectDate,
            onSelectDay = viewModel::selectDay,
            primaryAction = MetricAction(
                labelRes = R.string.strength_import_action,
                icon = Icons.Outlined.FileUpload,
                onClick = pickFile,
            ),
            weekPeriodMode = state.weekPeriodMode,
            syncPaused = hcUx.syncPaused,
        ) { period ->
            strengthPeriodContent(
                state = state,
                period = period,
                unitFormatter = unitFormatter,
                dateTimeFormatterProvider = dateTimeFormatterProvider,
                onSelectDay = viewModel::selectDay,
                onSelectExercise = viewModel::selectExercise,
                onImport = pickFile,
                onOpenActivity = onOpenActivity,
            )
        }
    }
}
