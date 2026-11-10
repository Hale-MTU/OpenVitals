package tech.mmarca.openvitals.openwatch

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkState
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.client.OpenLinkStatus

class OpenWatchLinkSummaryTest {

    private val venu = OpenLinkDevice("w1", "Venu 3", OpenLinkDeviceKind.WATCH, OpenLinkIntegration.GARMIN, OpenLinkConnection.CONNECTED)

    @Test
    fun `each link state reads as its one status`() {
        assertThat(openWatchLinkSummary(OpenLinkStatus.HostMissing, emptyList(), 0)).isEqualTo(OpenWatchLinkSummary.NotInstalled)
        assertThat(openWatchLinkSummary(OpenLinkStatus.Unreachable, emptyList(), 0)).isEqualTo(OpenWatchLinkSummary.Unreachable)
        assertThat(openWatchLinkSummary(OpenLinkStatus.HostUntrusted("1234"), emptyList(), 0))
            .isEqualTo(OpenWatchLinkSummary.UnknownSigner("1234"))
        assertThat(openWatchLinkSummary(OpenLinkStatus.Stopped, emptyList(), 0)).isEqualTo(OpenWatchLinkSummary.Connecting)
        assertThat(openWatchLinkSummary(connected(OpenLinkTrust.PENDING), emptyList(), 0)).isEqualTo(OpenWatchLinkSummary.WaitingForApproval)
        assertThat(openWatchLinkSummary(connected(OpenLinkTrust.BLOCKED), emptyList(), 0)).isEqualTo(OpenWatchLinkSummary.Blocked)
    }

    @Test
    fun `connected lists the watches, what is allowed and not, and the last profile sync`() {
        val summary = openWatchLinkSummary(
            connected(OpenLinkTrust.TRUSTED, setOf(OpenLink.CAPABILITY_LIVE, OpenLink.CAPABILITY_DEVICES)),
            listOf(venu),
            profileSyncedAtMillis = 42,
        ) as OpenWatchLinkSummary.Connected

        assertThat(summary.devices).containsExactly(venu)
        assertThat(summary.allowed).containsExactly(OpenLink.CAPABILITY_DEVICES, OpenLink.CAPABILITY_LIVE).inOrder()
        assertThat(summary.notAllowed).containsExactly(OpenLink.CAPABILITY_PROFILE, OpenLink.CAPABILITY_RECORDING).inOrder()
        assertThat(summary.profileShared).isFalse()
        assertThat(summary.profileSyncedAtMillis).isEqualTo(42)
    }

    private fun connected(trust: OpenLinkTrust, granted: Set<String> = emptySet()) =
        OpenLinkStatus.Connected(OpenLinkState(trust = trust, grantedCapabilities = granted))
}
