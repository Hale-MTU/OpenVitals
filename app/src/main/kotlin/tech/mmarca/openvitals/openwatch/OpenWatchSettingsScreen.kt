package tech.mmarca.openvitals.openwatch

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.ui.components.OpenVitalsCard
import tech.mmarca.openvitals.ui.theme.LayoutMetrics

@HiltViewModel
class OpenWatchSettingsViewModel @Inject constructor(
    private val link: OpenWatchLink,
) : ViewModel() {
    val summary: StateFlow<OpenWatchLinkSummary> = link.summary

    fun refresh() = link.refresh()

    fun open(context: android.content.Context): Boolean = link.open(context)
}

/**
 * Settings, OpenWatch: where OpenVitals stands with OpenWatch in one line, and what to do
 * about it. Not installed: a link to get it. Waiting: the way to OpenWatch's consent screen.
 * Connected: the watches it sees, what OpenWatch allows, and when the profile last crossed.
 */
@Composable
fun OpenWatchSettingsScreen(viewModel: OpenWatchSettingsViewModel) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Installing, updating or answering in OpenWatch happens in another app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val openWatchUrl = stringResource(R.string.openwatch_url)
    val cannotOpen = stringResource(R.string.openwatch_cannot_open)
    val noBrowser = stringResource(R.string.openwatch_move_no_browser, openWatchUrl)
    val openOpenWatch = {
        if (!viewModel.open(context)) Toast.makeText(context, cannotOpen, Toast.LENGTH_SHORT).show()
    }
    val getOpenWatch = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openWatchUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, noBrowser, Toast.LENGTH_LONG).show()
        }
        Unit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LayoutMetrics.screenGutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenVitalsCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = openWatchStatusLine(summary), style = MaterialTheme.typography.titleMedium)
                when (val current = summary) {
                    OpenWatchLinkSummary.NotInstalled -> {
                        Body(stringResource(R.string.openwatch_not_installed_body))
                        Button(onClick = getOpenWatch) { Text(stringResource(R.string.openwatch_move_action_get)) }
                    }
                    OpenWatchLinkSummary.Unreachable -> {
                        Body(stringResource(R.string.openwatch_unreachable_body))
                        Button(onClick = getOpenWatch) { Text(stringResource(R.string.openwatch_move_action_get)) }
                    }
                    OpenWatchLinkSummary.Connecting -> Unit
                    OpenWatchLinkSummary.WaitingForApproval -> {
                        Body(stringResource(R.string.openwatch_waiting_body))
                        Button(onClick = openOpenWatch) { Text(stringResource(R.string.openwatch_open_consent)) }
                    }
                    OpenWatchLinkSummary.Blocked -> {
                        Body(stringResource(R.string.openwatch_blocked_body))
                        Button(onClick = openOpenWatch) { Text(stringResource(R.string.openwatch_open)) }
                    }
                    is OpenWatchLinkSummary.Connected -> {
                        Body(
                            if (current.profileSyncedAtMillis > 0) {
                                stringResource(R.string.openwatch_profile_synced, relativeTime(current.profileSyncedAtMillis))
                            } else if (current.profileShared) {
                                stringResource(R.string.openwatch_profile_not_synced)
                            } else {
                                stringResource(R.string.openwatch_profile_not_shared)
                            },
                        )
                    }
                }
            }
        }

        (summary as? OpenWatchLinkSummary.Connected)?.let { connected ->
            OpenVitalsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.openwatch_devices_title), style = MaterialTheme.typography.titleSmall)
                    if (OpenLink.CAPABILITY_DEVICES !in connected.allowed) {
                        Body(stringResource(R.string.openwatch_devices_not_allowed))
                    } else if (connected.devices.isEmpty()) {
                        Body(stringResource(R.string.openwatch_no_devices))
                    } else {
                        connected.devices.forEach { DeviceLine(it) }
                    }
                }
            }
            OpenVitalsCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.openwatch_allowed_title), style = MaterialTheme.typography.titleSmall)
                    CapabilityOrder.forEach { capability ->
                        CapabilityLine(capability, allowed = capability in connected.allowed)
                    }
                    if (connected.notAllowed.isNotEmpty()) {
                        Body(stringResource(R.string.openwatch_allowed_hint))
                    }
                    Button(onClick = openOpenWatch) { Text(stringResource(R.string.openwatch_open)) }
                }
            }
        }
    }
}

/** The one plain status line, also the Settings root's summary for the entry. */
@Composable
fun openWatchStatusLine(summary: OpenWatchLinkSummary): String = when (summary) {
    OpenWatchLinkSummary.NotInstalled -> stringResource(R.string.openwatch_status_not_installed)
    OpenWatchLinkSummary.Unreachable -> stringResource(R.string.openwatch_status_unreachable)
    OpenWatchLinkSummary.Connecting -> stringResource(R.string.openwatch_status_connecting)
    OpenWatchLinkSummary.WaitingForApproval -> stringResource(R.string.openwatch_status_waiting)
    OpenWatchLinkSummary.Blocked -> stringResource(R.string.openwatch_status_blocked)
    is OpenWatchLinkSummary.Connected -> {
        val watches = summary.devices.joinToString(", ") { it.displayName }
        if (watches.isEmpty()) {
            stringResource(R.string.openwatch_status_connected)
        } else {
            stringResource(R.string.openwatch_status_connected_devices, watches)
        }
    }
}

/** "5 minutes ago", or "just now" inside the first minute. */
@Composable
internal fun relativeTime(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    if (nowMillis - atMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.openwatch_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(atMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    }

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeviceLine(device: OpenLinkDevice) {
    val details = buildList {
        add(
            stringResource(
                when (device.connection) {
                    OpenLinkConnection.CONNECTED -> R.string.openwatch_device_connected
                    OpenLinkConnection.DISCONNECTED -> R.string.openwatch_device_disconnected
                    OpenLinkConnection.UNKNOWN -> R.string.openwatch_device_unknown
                },
            ),
        )
        device.batteryPercent?.let { add(stringResource(R.string.dashboard_watch_battery, it)) }
        device.lastSyncedAtMillis?.let { add(stringResource(R.string.dashboard_watch_last_sync, relativeTime(it))) }
    }
    Column {
        Text(text = device.displayName, style = MaterialTheme.typography.bodyLarge)
        Body(details.joinToString(" · "))
    }
}

@Composable
private fun CapabilityLine(capability: String, allowed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = if (allowed) Icons.Outlined.Check else Icons.Outlined.Close,
            contentDescription = stringResource(if (allowed) R.string.openwatch_allowed else R.string.openwatch_not_allowed),
            tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(
                when (capability) {
                    OpenLink.CAPABILITY_PROFILE -> R.string.openwatch_capability_profile
                    OpenLink.CAPABILITY_DEVICES -> R.string.openwatch_capability_devices
                    OpenLink.CAPABILITY_RECORDING -> R.string.openwatch_capability_recording
                    else -> R.string.openwatch_capability_live
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
