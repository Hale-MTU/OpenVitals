package tech.mmarca.openvitals.features.imports.csv

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.ui.theme.Spacing

/** The time in bed and time asleep ranges across the sample, as they will be written. Confirm step only. */
@Composable
internal fun CsvSleepRangeLines(sample: CsvSample, mapping: CsvImportMapping) {
    val sessions = remember(sample, mapping) { previewSleepSessions(rows = sample.dataRows, mapping = mapping) }
    if (sessions.isEmpty()) return

    CsvSleepRangeLine(CsvSleepPart.TIME_IN_BED, sessions.map { it.inBedMillis })
    CsvSleepRangeLine(CsvSleepPart.TIME_ASLEEP, sessions.map { it.asleepMillis })
}

@Composable
private fun CsvSleepRangeLine(part: CsvSleepPart, values: List<Long>) {
    Text(
        text = stringResource(
            R.string.settings_csv_import_confirm_range,
            csvSleepPartLabel(part),
            csvSleepDurationText(values.min()),
            csvSleepDurationText(values.max()),
        ),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = Spacing.xs),
    )
}
