package tech.mmarca.openvitals.features.homewidgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.navigation.Screen

/**
 * "Start workout" on the home screen: the whole tile is one tap that opens the activity entry
 * screen as the dashboard's button does, in record mode. With a recording running, that screen
 * shows the running recording rather than a second setup, so the tile needs no state of its
 * own. It shows no data, so nothing refreshes it ([StaticHomeWidgetReceivers]).
 */
class HomeStartActivityWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(DpSize(88.dp, 88.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { HomeStartActivityWidgetContent() }
    }
}

class HomeStartActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HomeStartActivityWidget()
}

/** What the dashboard's "Start workout" opens. */
internal fun homeStartActivityWidgetRoute(): String =
    Screen.ActivityEntry.createRoute(mode = Screen.ActivityEntryMode.RECORD)

@Composable
private fun HomeStartActivityWidgetContent() {
    val context = LocalContext.current
    val label = context.getString(R.string.dashboard_action_start_workout)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(WidgetBackground))
            .clickable(actionStartActivity(openMetricIntent(context, homeStartActivityWidgetRoute())))
            // One label for the one tap target, whatever the launcher cuts off the text.
            .semantics { contentDescription = label }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_start_activity),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ColorProvider(WidgetPrimaryText)),
            modifier = GlanceModifier.size(28.dp),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = label,
            maxLines = 2,
            style = TextStyle(
                color = ColorProvider(WidgetPrimaryText),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
