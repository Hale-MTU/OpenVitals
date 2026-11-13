package tech.mmarca.openvitals.features.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Duration
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.presentation.UnitFormatter
import tech.mmarca.openvitals.domain.insights.HeartRateZones
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.HeartColor

/** The [HeartRateZones] a session's heart rate was placed on, and the time spent in each. */
@Immutable
internal data class ActivityHeartRateZoneTime(
    val bounds: HeartRateZones.Bounds,
    val durations: List<Duration>,
)

private val ZoneNames = listOf(
    R.string.activity_heart_rate_zone_1,
    R.string.activity_heart_rate_zone_2,
    R.string.activity_heart_rate_zone_3,
    R.string.activity_heart_rate_zone_4,
    R.string.activity_heart_rate_zone_5,
)

@Composable
internal fun ActivityHeartRateZonesCard(
    zoneTime: ActivityHeartRateZoneTime,
    unitFormatter: UnitFormatter,
    modifier: Modifier = Modifier,
) {
    val total = zoneTime.durations.sumOf { it.toMillis() }
    if (total <= 0L) return
    val lowerBounds = zoneTime.bounds.lowerBpm
    OpenVitalsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.activity_heart_rate_zones_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            for (zone in 0 until HeartRateZones.COUNT) {
                val millis = zoneTime.durations[zone].toMillis()
                val upper = lowerBounds.getOrNull(zone + 1)?.minus(1)
                val range = if (upper == null) {
                    "${lowerBounds[zone]}+"
                } else {
                    "${lowerBounds[zone]}–$upper"
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(ZoneNames[zone]),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "${formatZoneDuration(millis)}  ·  $range ${unitFormatter.heartRateUnit()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = (millis.toFloat() / total).coerceIn(0f, 1f))
                                .background(HeartColor),
                        )
                    }
                }
            }
            val maxBpm = zoneTime.bounds.maxBpm
            Text(
                text = if (maxBpm != null) {
                    stringResource(R.string.activity_heart_rate_zones_basis, maxBpm)
                } else {
                    stringResource(R.string.activity_heart_rate_zones_manual)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatZoneDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
