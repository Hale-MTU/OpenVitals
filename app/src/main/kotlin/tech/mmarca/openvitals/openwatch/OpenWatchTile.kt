package tech.mmarca.openvitals.openwatch

import java.time.Instant
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.client.OpenLinkStatus

/** The dashboard's OpenWatch tile: the primary watch, or the request to allow the link. */
data class OpenWatchTileDisplay(
    /** Which watch the sync button drives; null while OpenWatch has not allowed the link. */
    val deviceId: String?,
    val name: String,
    val batteryPercent: Int? = null,
    val lastSyncedAt: Instant? = null,
    /** More watches than the one shown, for a "+2" style suffix. */
    val additionalCount: Int = 0,
    val isSyncing: Boolean = false,
    /** OpenWatch asks the user to allow OpenVitals; the tile says so and opens the request. */
    val awaitingConsent: Boolean = false,
)

/**
 * What the tile shows, or null for no tile: OpenWatch missing or stopped, or no watch
 * paired. The watch being synced owns the tile; failing that, the most recently synced.
 */
internal fun openWatchTile(status: OpenLinkStatus, devices: List<OpenLinkDevice>): OpenWatchTileDisplay? {
    val state = (status as? OpenLinkStatus.Connected)?.state ?: return null
    if (state.trust == OpenLinkTrust.PENDING) {
        return OpenWatchTileDisplay(deviceId = null, name = "OpenWatch", awaitingConsent = true)
    }
    if (!state.isTrusted) return null
    val watches = devices.filter { it.kind != OpenLinkDeviceKind.SCALE }
    val primary = watches.firstOrNull { it.syncing }
        ?: watches.maxByOrNull { it.lastSyncedAtMillis ?: Long.MIN_VALUE }
        ?: return null
    return OpenWatchTileDisplay(
        deviceId = primary.id,
        name = primary.displayName,
        batteryPercent = primary.batteryPercent,
        lastSyncedAt = primary.lastSyncedAtMillis?.let(Instant::ofEpochMilli),
        additionalCount = watches.size - 1,
        isSyncing = primary.syncing,
    )
}
