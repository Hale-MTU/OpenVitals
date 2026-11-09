package tech.mmarca.openvitals.features.strength

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.period.DatePeriod
import tech.mmarca.openvitals.core.period.TimeRange
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.ui.components.ChartSkeleton
import tech.mmarca.openvitals.ui.components.ChartSkeletonShape
import tech.mmarca.openvitals.ui.components.ChartTokens
import tech.mmarca.openvitals.ui.components.MetricBarChart
import tech.mmarca.openvitals.ui.components.MetricCardPlaceholder
import tech.mmarca.openvitals.ui.components.PaginatedEntryList
import tech.mmarca.openvitals.ui.components.PeriodBarAggregation
import tech.mmarca.openvitals.ui.components.SectionHeader
import tech.mmarca.openvitals.ui.theme.WorkoutColor

internal fun LazyListScope.strengthPeriodContent(
    state: StrengthUiState,
    period: DatePeriod,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onSelectDay: (LocalDate) -> Unit,
    onSelectExercise: (String) -> Unit,
    onImport: () -> Unit,
    onOpenActivity: (String) -> Unit,
) {
    val display = state.display
    if (!display.hasData && state.isLoading) {
        // Still loading: a skeleton where the chart will be. Existing content is kept.
        item {
            ChartSkeleton(
                modifier = metricModifier(),
                shape = ChartSkeletonShape.BARS,
                height = ChartTokens.heightPeriodBar,
            )
        }
        return
    }
    if (!display.hasData) {
        item {
            MetricCardPlaceholder(
                title = stringResource(R.string.screen_strength),
                icon = Icons.Outlined.FitnessCenter,
                accentColor = WorkoutColor,
                message = stringResource(R.string.strength_empty_body),
                modifier = metricModifier(),
            )
        }
        strengthImportItem(state, onImport)
        return
    }

    item {
        StrengthSummary(
            stats = display.stats,
            unitFormatter = unitFormatter,
            modifier = metricModifier(),
        )
    }
    when (state.selectedRange) {
        TimeRange.DAY -> Unit
        TimeRange.MONTH -> item {
            StrengthCalendarCard(
                period = period,
                sessionsByDay = display.sessionsByDay,
                onSelectDay = onSelectDay,
                modifier = metricModifier(),
            )
        }
        TimeRange.WEEK, TimeRange.YEAR -> item {
            MetricBarChart(
                title = stringResource(R.string.strength_stat_sessions),
                values = display.chartValues,
                selectedRange = state.selectedRange,
                period = period,
                accentColor = WorkoutColor,
                summaryValue = unitFormatter.count(display.stats.sessions),
                dateTimeFormatterProvider = dateTimeFormatterProvider,
                yearAggregation = PeriodBarAggregation.SUM,
                onDateSelected = onSelectDay,
                modifier = metricModifier(),
            )
        }
    }
    item { SectionHeader(stringResource(R.string.strength_muscles_title)) }
    item {
        StrengthMuscleCard(
            setsPerMuscle = display.setsPerMuscle,
            modifier = metricModifier(),
        )
    }
    if (state.selectedRange != TimeRange.DAY) {
        item { SectionHeader(stringResource(R.string.strength_progress_title)) }
        item {
            StrengthProgressCard(
                exercises = display.progressExercises,
                selected = display.selectedExercise,
                points = display.progress,
                unitFormatter = unitFormatter,
                dateTimeFormatterProvider = dateTimeFormatterProvider,
                onSelect = onSelectExercise,
                modifier = metricModifier(),
            )
        }
    }
    item {
        PaginatedEntryList(
            title = stringResource(R.string.strength_sessions_title),
            entries = state.sessions,
            modifier = Modifier.fillMaxWidth(),
        ) { session, rowModifier ->
            StrengthSessionCard(
                session = session,
                unitFormatter = unitFormatter,
                dateTimeFormatterProvider = dateTimeFormatterProvider,
                onOpenActivity = onOpenActivity,
                modifier = rowModifier,
            )
        }
    }
    strengthImportItem(state, onImport)
}

private fun LazyListScope.strengthImportItem(state: StrengthUiState, onImport: () -> Unit) {
    item {
        StrengthImportCard(
            importedWorkoutCount = state.importedWorkoutCount,
            isImporting = state.isImporting,
            result = state.importResult,
            onImport = onImport,
            modifier = metricModifier(),
        )
    }
}

@Composable
private fun metricModifier(): Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
