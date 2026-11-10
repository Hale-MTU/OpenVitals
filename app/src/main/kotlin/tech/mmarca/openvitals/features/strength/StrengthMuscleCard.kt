package tech.mmarca.openvitals.features.strength

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.WorkoutColor

internal class MuscleOutline(val group: MuscleGroup?, val points: FloatArray)

private fun parseOutlines(views: List<Pair<String, List<String>>>): List<MuscleOutline> =
    views.flatMap { (name, polygons) ->
        polygons.map { raw ->
            MuscleOutline(
                group = StrengthMuscles.outlineGroups[name],
                points = raw.trim().split(Regex("\\s+")).map { it.toFloat() }.toFloatArray(),
            )
        }
    }

internal val frontOutlines: List<MuscleOutline> by lazy { parseOutlines(MuscleMapData.front) }
internal val backOutlines: List<MuscleOutline> by lazy { parseOutlines(MuscleMapData.back) }

/** Half sets are real (a secondary muscle counts half), so one decimal when needed. */
private fun Double.setsLabel(): String {
    val rounded = (this * 2).roundToInt() / 2.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(java.util.Locale.getDefault(), "%.1f", rounded)
}

@Composable
internal fun StrengthMuscleCard(
    setsPerMuscle: Map<MuscleGroup, Double>,
    modifier: Modifier = Modifier,
) {
    val max = remember(setsPerMuscle) { setsPerMuscle.values.maxOrNull() ?: 0.0 }
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val body = MaterialTheme.colorScheme.outlineVariant
    val gap = MaterialTheme.colorScheme.surfaceContainer
    fun colorOf(group: MuscleGroup?): Color {
        val sets = group?.let { setsPerMuscle[it] } ?: 0.0
        return when {
            group == null -> body
            sets <= 0.0 || max <= 0.0 -> idle
            else -> WorkoutColor.copy(alpha = (0.3 + 0.7 * sets / max).toFloat())
        }
    }
    val ranked = remember(setsPerMuscle) {
        setsPerMuscle.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    }
    val untrained = remember(setsPerMuscle) { MuscleGroup.entries.filter { (setsPerMuscle[it] ?: 0.0) <= 0.0 } }
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    frontOutlines to R.string.strength_muscles_front,
                    backOutlines to R.string.strength_muscles_back,
                ).forEach { (outlines, label) ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        BodyFigure(outlines, ::colorOf, gap)
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (ranked.isEmpty()) {
                Text(
                    text = stringResource(R.string.strength_muscles_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.strength_muscles_sets_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ranked.forEach { (group, sets) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(group.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(110.dp),
                        )
                        Box(modifier = Modifier.weight(1f).height(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = (sets / max).toFloat().coerceIn(0.04f, 1f))
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(WorkoutColor),
                            )
                        }
                        Text(
                            text = sets.setsLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
                if (untrained.isNotEmpty()) {
                    // map is inline, so the resource lookups stay in the composable's context.
                    val untrainedNames = untrained.map { stringResource(it.labelRes) }
                    Text(
                        text = stringResource(R.string.strength_muscles_untrained, untrainedNames.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.strength_muscles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BodyFigure(outlines: List<MuscleOutline>, colorOf: (MuscleGroup?) -> Color, gap: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(MuscleMapData.WIDTH / MuscleMapData.HEIGHT),
    ) {
        val scale = size.width / MuscleMapData.WIDTH
        outlines.forEach { outline ->
            val p = outline.points
            if (p.size < 6) return@forEach
            val path = Path().apply {
                moveTo(p[0] * scale, p[1] * scale)
                var i = 2
                while (i + 1 < p.size) {
                    lineTo(p[i] * scale, p[i + 1] * scale)
                    i += 2
                }
                close()
            }
            drawPath(path, colorOf(outline.group))
            drawPath(path, gap, style = Stroke(width = 1.5f))
        }
    }
}
