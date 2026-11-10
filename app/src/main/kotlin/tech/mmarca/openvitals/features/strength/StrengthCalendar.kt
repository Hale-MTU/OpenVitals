package tech.mmarca.openvitals.features.strength

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.core.period.DatePeriod
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.WorkoutColor
import tech.mmarca.openvitals.domain.model.StrengthSession

/** The month's training days, each marked and named; a tap opens the day. */
@Composable
internal fun StrengthCalendarCard(
    period: DatePeriod,
    sessionsByDay: Map<LocalDate, List<StrengthSession>>,
    onSelectDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.getDefault()
    val month = remember(period) { YearMonth.from(period.start) }
    val today = LocalDate.now()
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val weekDays = remember(firstDay) { (0 until 7).map { firstDay.plus(it.toLong()) } }
    OpenVitalsCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                weekDays.forEach { day ->
                    Text(
                        text = day.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val offset = ((month.atDay(1).dayOfWeek.value - firstDay.value) + 7) % 7
            val cells = offset + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val dayNumber = r * 7 + c - offset + 1
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            if (dayNumber in 1..month.lengthOfMonth()) {
                                val date = month.atDay(dayNumber)
                                StrengthCalendarDay(
                                    date = date,
                                    sessions = sessionsByDay[date].orEmpty(),
                                    isToday = date == today,
                                    isFuture = date.isAfter(today),
                                    onClick = { onSelectDay(date) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrengthCalendarDay(
    date: LocalDate,
    sessions: List<StrengthSession>,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val trained = sessions.isNotEmpty()
    val description = if (trained) stringResource(R.string.strength_calendar_trained, sessions.size) else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (trained) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(vertical = 3.dp),
    ) {
        var circle = Modifier.size(34.dp).clip(CircleShape)
        circle = when {
            trained -> circle.background(WorkoutColor)
            isToday -> circle.border(1.5.dp, colors.primary, CircleShape)
            else -> circle
        }
        Box(modifier = circle, contentAlignment = Alignment.Center) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (trained || isToday) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    trained -> colors.surface
                    isToday -> colors.primary
                    isFuture -> colors.onSurface.copy(alpha = 0.35f)
                    else -> colors.onSurface
                },
            )
        }
        Text(
            text = sessions.firstOrNull()?.displayTitle().orEmpty(),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
