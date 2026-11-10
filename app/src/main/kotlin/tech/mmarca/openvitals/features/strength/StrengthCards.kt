package tech.mmarca.openvitals.features.strength

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.DateTimeFormatterProvider
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.features.activity.exerciseTypeLabel
import tech.mmarca.openvitals.ui.components.ChartTokens
import tech.mmarca.openvitals.ui.components.InsightStat
import tech.mmarca.openvitals.ui.components.InsightStatGrid
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.components.OpenVitalsOutlinedButton
import tech.mmarca.openvitals.ui.theme.WorkoutColor
import tech.mmarca.openvitals.domain.model.ImportedExercise
import tech.mmarca.openvitals.domain.model.StrengthSession
import tech.mmarca.openvitals.domain.model.StrengthProgressPoint

@Composable
internal fun StrengthSummary(
    stats: StrengthPeriodStats,
    unitFormatter: UnitFormatter,
    modifier: Modifier = Modifier,
) {
    val volume = unitFormatter.bodyMass(stats.volumeKg, decimals = 0)
    val insightStats = buildList {
        add(
            InsightStat(
                title = stringResource(R.string.strength_stat_sessions),
                value = unitFormatter.count(stats.sessions),
                unit = "",
                icon = Icons.Outlined.FitnessCenter,
                accentColor = WorkoutColor,
            ),
        )
        add(
            InsightStat(
                title = stringResource(R.string.strength_stat_days),
                value = unitFormatter.count(stats.trainingDays),
                unit = "",
                icon = Icons.Outlined.CalendarMonth,
                accentColor = WorkoutColor,
            ),
        )
        add(
            InsightStat(
                title = stringResource(R.string.strength_stat_sets),
                value = unitFormatter.count(stats.sets),
                unit = "",
                icon = Icons.Outlined.FitnessCenter,
                accentColor = WorkoutColor,
            ),
        )
        add(
            InsightStat(
                title = stringResource(R.string.strength_stat_volume),
                value = volume.value,
                unit = volume.unit,
                icon = Icons.Outlined.Scale,
                accentColor = WorkoutColor,
            ),
        )
        stats.restDays?.let { rest ->
            add(
                InsightStat(
                    title = stringResource(R.string.strength_stat_rest_days),
                    value = unitFormatter.count(rest),
                    unit = "",
                    icon = Icons.Outlined.Hotel,
                    accentColor = WorkoutColor,
                ),
            )
        }
    }
    InsightStatGrid(stats = insightStats, modifier = modifier)
}

/** The session's name: its own title, else its Health Connect type. */
@Composable
internal fun StrengthSession.displayTitle(): String = title.ifBlank {
    exerciseType?.let { exerciseTypeLabel(it) } ?: stringResource(R.string.metric_workout)
}

@Composable
internal fun StrengthSessionCard(
    session: StrengthSession,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onOpenActivity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val startedAt = remember(session.start, dateTimeFormatterProvider) {
        dateTimeFormatterProvider.mediumDateTime().format(session.start)
    }
    val chips = buildList {
        session.durationMinutes?.let { add(unitFormatter.minutes(it).text) }
        if (session.setCount > 0) add("${unitFormatter.count(session.setCount)} ${stringResource(R.string.strength_stat_sets).lowercase()}")
        if (session.volumeKg > 0) add(unitFormatter.bodyMass(session.volumeKg, decimals = 0).text)
    }
    OpenVitalsCard(
        modifier = modifier.fillMaxWidth(),
        onClick = session.healthConnectId?.let { id -> { onOpenActivity(id) } },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = session.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = startedAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chips.isNotEmpty()) {
                Text(
                    text = chips.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!session.hasSets) {
                Text(
                    text = stringResource(R.string.strength_session_no_sets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            session.exercises.forEach { exercise ->
                Row {
                    Text(
                        text = exercise.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(
                        text = exercise.setsLabel(unitFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** "3×10 @ 40 kg" when every set is the same, else one entry per set: "40×10, 45×8". */
private fun ImportedExercise.setsLabel(unitFormatter: UnitFormatter): String {
    if (isCardio) {
        return sets.joinToString(", ") { set ->
            listOfNotNull(
                set.seconds?.let { unitFormatter.minutes((it / 60.0).roundToLong()).text },
                set.km?.let { unitFormatter.distance(it * 1000).text },
            ).joinToString(" · ")
        }
    }
    val first = sets.firstOrNull() ?: return ""
    val same = sets.all { it.kg == first.kg && it.reps == first.reps }
    return if (same) {
        "${sets.size}×${first.reps ?: "?"}" +
            (first.kg?.takeIf { it > 0 }?.let { " @ ${unitFormatter.bodyMass(it).text}" } ?: "")
    } else {
        sets.joinToString(", ") { set ->
            (set.kg?.takeIf { it > 0 }?.let { "${unitFormatter.bodyMass(it).value}×" } ?: "") + (set.reps?.toString() ?: "?")
        }
    }
}

@Composable
internal fun StrengthProgressCard(
    exercises: List<String>,
    selected: String?,
    points: List<StrengthProgressPoint>,
    unitFormatter: UnitFormatter,
    dateTimeFormatterProvider: DateTimeFormatterProvider,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (exercises.isEmpty()) {
                Text(
                    text = stringResource(R.string.strength_progress_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                exercises.forEach { name ->
                    FilterChip(
                        selected = name == selected,
                        onClick = { onSelect(name) },
                        label = { Text(name, maxLines = 1) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.strength_progress_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (points.isNotEmpty()) {
                val best = remember(points) { points.maxBy { it.oneRepMaxKg } }
                val dateFormat = dateTimeFormatterProvider.mediumDate()
                Text(
                    text = stringResource(
                        R.string.strength_progress_best,
                        unitFormatter.bodyMass(best.oneRepMaxKg).text,
                        dateFormat.format(best.date),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StrengthProgressChart(points, unitFormatter)
                Row {
                    Text(
                        text = dateFormat.format(points.first().date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = dateFormat.format(points.last().date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StrengthProgressChart(points: List<StrengthProgressPoint>, unitFormatter: UnitFormatter) {
    val line = WorkoutColor
    val grid = MaterialTheme.colorScheme.outlineVariant
    val minValue = remember(points) { points.minOf { it.oneRepMaxKg } }
    val maxValue = remember(points) { points.maxOf { it.oneRepMaxKg } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.height(ChartTokens.heightLine).padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                unitFormatter.bodyMass(maxValue, decimals = 0).text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                unitFormatter.bodyMass(minValue, decimals = 0).text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(modifier = Modifier.weight(1f).height(ChartTokens.heightLine)) {
            val pad = 8.dp.toPx()
            val first = points.first().date.toEpochDay().toFloat()
            val span = (points.last().date.toEpochDay() - points.first().date.toEpochDay()).toFloat().coerceAtLeast(1f)
            val range = (maxValue - minValue).toFloat().coerceAtLeast(1f)
            fun x(p: StrengthProgressPoint) =
                if (points.size == 1) size.width / 2 else pad + (p.date.toEpochDay() - first) / span * (size.width - 2 * pad)
            fun y(p: StrengthProgressPoint) = pad + (1f - ((p.oneRepMaxKg - minValue).toFloat() / range)) * (size.height - 2 * pad)
            drawLine(grid, Offset(0f, pad), Offset(size.width, pad), strokeWidth = 1f)
            drawLine(grid, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), strokeWidth = 1f)
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p))
            }
            if (points.size > 1) drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
            points.forEachIndexed { i, p ->
                drawCircle(line, radius = if (i == points.lastIndex) 5.dp.toPx() else 3.dp.toPx(), center = Offset(x(p), y(p)))
            }
        }
    }
}

@Composable
internal fun StrengthImportCard(
    importedWorkoutCount: Int,
    isImporting: Boolean,
    result: StrengthImportResult?,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.strength_import_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.strength_import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (importedWorkoutCount > 0) {
                Text(
                    text = stringResource(R.string.strength_import_count, importedWorkoutCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (result) {
                is StrengthImportResult.Done -> Text(
                    text = stringResource(
                        R.string.strength_import_done,
                        result.summary.workouts,
                        result.summary.sets,
                        result.summary.added,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                StrengthImportResult.NotHevy -> Text(
                    text = stringResource(R.string.strength_import_not_hevy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                StrengthImportResult.Failed -> Text(
                    text = stringResource(R.string.strength_import_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
            OpenVitalsOutlinedButton(onClick = onImport, enabled = !isImporting) {
                if (isImporting) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                }
                Text(stringResource(R.string.strength_import_action))
            }
        }
    }
}
