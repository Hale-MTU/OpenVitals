package tech.mmarca.openvitals.features.vertmatch

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.data.repository.contract.ActivityDeletionListener
import tech.mmarca.openvitals.domain.model.ExerciseData
import tech.mmarca.openvitals.features.activity.ActivityDetailExtension
import tech.mmarca.openvitals.features.activity.ActivityWorkoutSeries
import tech.mmarca.openvitals.features.settings.SettingsExtension
import tech.mmarca.openvitals.ui.components.DetailSectionCard
import tech.mmarca.openvitals.ui.components.OpenVitalsButton
import tech.mmarca.openvitals.ui.components.OpenVitalsOutlinedButton
import tech.mmarca.openvitals.ui.theme.Spacing

internal fun Context.openVertMatchScreen() =
    startActivity(Intent(this, VertMatchActivity::class.java))

/** The Settings entry: where the runner connects, disconnects and sees the summary. */
@Singleton
internal class VertMatchSettingsEntry @Inject constructor(
    private val connection: VertMatchConnection,
) : SettingsExtension {
    @Composable
    override fun Entry(modifier: Modifier) {
        val state by connection.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        DetailSectionCard(title = stringResource(R.string.vertmatch_title), modifier = modifier) {
            Text(
                text = stringResource(statusText(state.status)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenVitalsOutlinedButton(onClick = context::openVertMatchScreen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vertmatch_open))
            }
        }
    }
}

/** The card on an activity's detail screen that sends it to VertMatch. */
@Singleton
internal class VertMatchActivityCard @Inject constructor(
    private val connection: VertMatchConnection,
) : ActivityDetailExtension {
    @Composable
    override fun Card(workout: ExerciseData, series: ActivityWorkoutSeries, modifier: Modifier) {
        val state by connection.state.collectAsStateWithLifecycle()
        if (state.status == VertMatchStatus.NotConfigured) return
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val externalId = VertMatchConnection.externalIdFor(workout.id)
        val connected = state.status == VertMatchStatus.Connected
        val alreadySent = externalId != null && externalId in state.sent
        val deletedThere = externalId != null && externalId in state.known
        val body = when {
            !connected -> R.string.vertmatch_card_connect_first
            deletedThere -> R.string.vertmatch_message_known
            alreadySent -> R.string.vertmatch_card_sent
            else -> R.string.vertmatch_card_body
        }
        DetailSectionCard(title = stringResource(R.string.vertmatch_title), modifier = modifier) {
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // VertMatch finds a copy it already has from Health Connect by start time and distance.
            if (connected && !alreadySent && !deletedThere && (workout.totalDistanceMeters ?: 0.0) <= 0.0) {
                Text(
                    text = stringResource(R.string.vertmatch_card_no_distance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.takeIf { it != body }?.let { message ->
                Text(text = stringResource(message), style = MaterialTheme.typography.bodyMedium)
            }
            Column(modifier = Modifier.padding(top = Spacing.xs)) {
                when {
                    // Nothing to offer: VertMatch keeps it deleted.
                    connected && deletedThere -> Unit
                    connected && externalId != null -> OpenVitalsButton(
                        onClick = { scope.launch { connection.send(workout, series) } },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (alreadySent) R.string.vertmatch_send_again else R.string.vertmatch_send))
                    }
                    else -> OpenVitalsOutlinedButton(onClick = context::openVertMatchScreen, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vertmatch_open))
                    }
                }
            }
        }
    }
}

/** Deletes VertMatch's copy when the runner deletes the activity here (partner requirement r6). */
@Singleton
internal class VertMatchDeletionListener @Inject constructor(
    private val connection: VertMatchConnection,
) : ActivityDeletionListener {
    override fun onActivityDeleted(id: String) = connection.onActivityDeleted(id)
}

internal fun statusText(status: VertMatchStatus): Int = when (status) {
    VertMatchStatus.NotConfigured -> R.string.vertmatch_status_not_configured
    VertMatchStatus.Disconnected -> R.string.vertmatch_status_disconnected
    VertMatchStatus.Connected -> R.string.vertmatch_status_connected
    VertMatchStatus.AppStopped -> R.string.vertmatch_status_app_stopped
}
