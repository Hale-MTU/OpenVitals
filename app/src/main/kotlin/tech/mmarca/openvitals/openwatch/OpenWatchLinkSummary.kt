package tech.mmarca.openvitals.openwatch

import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.client.OpenLinkStatus

/** Where OpenVitals stands with OpenWatch, for Settings, OpenWatch: one status line and its details. */
sealed interface OpenWatchLinkSummary {
    /** OpenWatch is not installed: a link to get it. */
    data object NotInstalled : OpenWatchLinkSummary

    /** OpenWatch is installed but Android refused the connection (an older OpenWatch). */
    data object Unreachable : OpenWatchLinkSummary

    /** Binding, or OpenVitals is in the background and not bound. */
    data object Connecting : OpenWatchLinkSummary

    /** OpenWatch asks the user to allow OpenVitals: a button opens its consent screen. */
    data object WaitingForApproval : OpenWatchLinkSummary

    /** The user blocked OpenVitals in OpenWatch. */
    data object Blocked : OpenWatchLinkSummary

    data class Connected(
        /** The watches and scale OpenWatch shows OpenVitals; empty without the devices grant. */
        val devices: List<OpenLinkDevice>,
        /** What OpenWatch allows, in the order the screen lists them. */
        val allowed: List<String>,
        /** What OpenVitals asked for and OpenWatch does not allow. */
        val notAllowed: List<String>,
        /** When a body profile last crossed; 0 if never. */
        val profileSyncedAtMillis: Long,
    ) : OpenWatchLinkSummary {
        val profileShared: Boolean get() = OpenLink.CAPABILITY_PROFILE in allowed
    }
}

/** The capabilities in the order OpenWatch's own screen lists them. */
internal val CapabilityOrder = listOf(
    OpenLink.CAPABILITY_DEVICES,
    OpenLink.CAPABILITY_PROFILE,
    OpenLink.CAPABILITY_RECORDING,
    OpenLink.CAPABILITY_LIVE,
)

internal fun openWatchLinkSummary(
    status: OpenLinkStatus,
    devices: List<OpenLinkDevice>,
    profileSyncedAtMillis: Long,
): OpenWatchLinkSummary = when (status) {
    OpenLinkStatus.HostMissing -> OpenWatchLinkSummary.NotInstalled
    OpenLinkStatus.Unreachable -> OpenWatchLinkSummary.Unreachable
    OpenLinkStatus.Stopped, OpenLinkStatus.Connecting -> OpenWatchLinkSummary.Connecting
    is OpenLinkStatus.Connected -> when (status.state.trust) {
        OpenLinkTrust.PENDING -> OpenWatchLinkSummary.WaitingForApproval
        OpenLinkTrust.BLOCKED -> OpenWatchLinkSummary.Blocked
        OpenLinkTrust.TRUSTED -> {
            val granted = status.state.grantedCapabilities
            OpenWatchLinkSummary.Connected(
                devices = devices,
                allowed = CapabilityOrder.filter { it in granted },
                notAllowed = CapabilityOrder.filter { it !in granted },
                profileSyncedAtMillis = profileSyncedAtMillis,
            )
        }
    }
}
