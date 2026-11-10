package tech.mmarca.openvitals.openwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkState
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.client.OpenLinkStatus

class OpenWatchTileTest {

    private val trusted = OpenLinkStatus.Connected(OpenLinkState(trust = OpenLinkTrust.TRUSTED, grantedCapabilities = OpenLink.ALL_CAPABILITIES))

    private fun watch(id: String, lastSync: Long?, syncing: Boolean = false, kind: OpenLinkDeviceKind = OpenLinkDeviceKind.WATCH) =
        OpenLinkDevice(id, id, kind, OpenLinkIntegration.GARMIN, batteryPercent = 70, lastSyncedAtMillis = lastSync, syncing = syncing)

    @Test
    fun `no OpenWatch, no tile`() {
        assertNull(openWatchTile(OpenLinkStatus.HostMissing, listOf(watch("a", 1))))
        assertNull(openWatchTile(OpenLinkStatus.Stopped, emptyList()))
    }

    @Test
    fun `a trusted link with only the scale shows no tile`() {
        assertNull(openWatchTile(trusted, listOf(watch("scale", null, kind = OpenLinkDeviceKind.SCALE))))
    }

    @Test
    fun `the watch being synced owns the tile, else the most recently synced`() {
        val devices = listOf(watch("old", 1_000), watch("new", 5_000), watch("busy", 100, syncing = true))

        assertEquals("busy", openWatchTile(trusted, devices)!!.deviceId)
        val tile = openWatchTile(trusted, devices.dropLast(1))!!
        assertEquals("new", tile.deviceId)
        assertEquals(1, tile.additionalCount)
        assertEquals(70, tile.batteryPercent)
    }

    @Test
    fun `while OpenWatch asks the user, the tile says so and drives no sync`() {
        val tile = openWatchTile(OpenLinkStatus.Connected(OpenLinkState(trust = OpenLinkTrust.PENDING)), emptyList())!!

        assertTrue(tile.awaitingConsent)
        assertNull(tile.deviceId)
    }

    @Test
    fun `a blocked link shows nothing`() {
        assertNull(openWatchTile(OpenLinkStatus.Connected(OpenLinkState(trust = OpenLinkTrust.BLOCKED)), listOf(watch("a", 1))))
    }
}
