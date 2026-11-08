package tech.mmarca.openvitals.openwatch

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.client.OpenLinkClient
import tech.mmarca.openlink.client.OpenLinkStatus
import tech.mmarca.openvitals.BuildConfig
import tech.mmarca.openvitals.core.performance.AppForegroundGate
import tech.mmarca.openvitals.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openvitals.features.manualentry.activity.recording.ActivityRecordingController
import tech.mmarca.openvitals.sensors.ble.BleSensorCoordinator

/**
 * OpenVitals' side of OpenLink, the control channel to OpenWatch (openlink/docs/spec.md).
 * Health data still comes through Health Connect; this carries the rest:
 *
 * - the body profile, both ways, last writer wins;
 * - the paired watches for the dashboard tile, and "sync now";
 * - while an activity records, a session that keeps the watch streaming, and its live
 *   heart rate into the recording as a sensor (a strap the user added still wins).
 *
 * Bound while the app is in the foreground or a recording runs, never otherwise, so
 * OpenWatch is not kept alive for nothing.
 */
@Singleton
class OpenWatchLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundGate: AppForegroundGate,
    private val recording: ActivityRecordingController,
    private val sensors: BleSensorCoordinator,
    private val profiles: BodyProfilePreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val stamp = ProfileStamp(prefs.getLong(KEY_PROFILE_AT, 0L), prefs.getString(KEY_PROFILE_SIGNATURE, null))

    /** The release build of OpenWatch if installed, else its debug build. */
    val hostPackage: String = HostPackages.firstOrNull { isInstalled(it) } ?: HostPackages.first()

    private val client = OpenLinkClient(
        context = context,
        hostPackage = hostPackage,
        requestedCapabilities = OpenLink.ALL_CAPABILITIES,
        appVersion = BuildConfig.VERSION_NAME,
    )

    val status: StateFlow<OpenLinkStatus> = client.status

    /** The dashboard tile, or null for none. */
    val tile: StateFlow<OpenWatchTileDisplay?> = combine(client.status, client.devices, ::openWatchTile)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var started = false
    private var session: String? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(foregroundGate.foregroundFlow, recording.state.map { it.isActive }) { foreground, recording ->
                foreground || recording
            }.distinctUntilChanged().collect { wanted ->
                if (wanted) {
                    client.refresh()
                    client.start()
                } else {
                    client.stop()
                }
            }
        }
        scope.launch {
            profiles.bodyProfileFlow.collect { profile ->
                val push = synchronized(stamp) { stamp.onLocal(profile, System.currentTimeMillis()).also { saveStamp() } }
                push?.let(client::setProfile)
            }
        }
        scope.launch {
            client.hostProfile.filterNotNull().collect { remote ->
                val adopt = synchronized(stamp) { stamp.onRemote(remote).also { saveStamp() } }
                adopt?.let(profiles::setBodyProfile)
            }
        }
        scope.launch {
            recording.state
                .map { if (it.isActive) RecordingKey(it.startTime, it.exerciseType) else null }
                .distinctUntilChanged()
                .collect(::onRecordingChanged)
        }
        scope.launch {
            client.liveBatches.collect { batch ->
                val name = client.devices.value.firstOrNull { it.id == batch.deviceId }?.displayName ?: "OpenWatch"
                batch.samples.filter { it.type == OpenLinkSampleType.HEART_RATE }.forEach { sample ->
                    sensors.acceptExternalHeartRate(
                        sourceId = SOURCE_PREFIX + batch.deviceId,
                        displayName = name,
                        bpm = sample.value.toLong(),
                        at = Instant.ofEpochMilli(sample.atMillis),
                    )
                }
            }
        }
    }

    fun requestSync(deviceId: String) = client.requestSync(deviceId)

    /**
     * Opens OpenWatch: its consent screen while it waits for an answer about OpenVitals,
     * else its home screen. False if neither could be opened.
     */
    fun open(context: Context): Boolean {
        val state = (client.status.value as? OpenLinkStatus.Connected)?.state
        state?.consentIntent?.let { pending ->
            return runCatching { pending.send(); true }.getOrDefault(false)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(hostPackage) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private data class RecordingKey(val startTime: Instant?, val exerciseType: Int?)

    private fun onRecordingChanged(key: RecordingKey?) {
        session?.let { previous ->
            client.unsubscribeLive(previous)
            client.stopRecording(previous)
            sensors.clearExternalHeartRate()
            session = null
        }
        val startTime = key?.startTime ?: return
        val id = "openvitals-" + startTime.toEpochMilli()
        session = id
        client.startRecording(
            OpenLinkRecordingRequest(sessionId = id, exerciseType = key.exerciseType ?: 0, startedAtMillis = startTime.toEpochMilli()),
        )
        client.subscribeLive(OpenLinkLiveSubscription(sessionId = id, types = setOf(OpenLinkSampleType.HEART_RATE)))
        Log.i(TAG, "asked OpenWatch to stream for a recording")
    }

    private fun saveStamp() {
        prefs.edit {
            putLong(KEY_PROFILE_AT, stamp.updatedAtMillis)
            putString(KEY_PROFILE_SIGNATURE, stamp.signature)
        }
    }

    private fun isInstalled(packageName: String): Boolean =
        context.packageManager.queryIntentServices(Intent(OpenLink.ACTION_HOST).setPackage(packageName), 0).isNotEmpty()

    private companion object {
        const val TAG = "OpenWatchLink"
        const val PREFS = "openwatch_link"
        const val KEY_PROFILE_AT = "profile_updated_at"
        const val KEY_PROFILE_SIGNATURE = "profile_signature"
        const val SOURCE_PREFIX = "openwatch:"
        val HostPackages = listOf("tech.mmarca.openwatch", "tech.mmarca.openwatch.debug")
    }
}
