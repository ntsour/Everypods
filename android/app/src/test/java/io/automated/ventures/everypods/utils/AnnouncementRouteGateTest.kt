package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.utils.AnnouncementRouteGate.Input
import io.automated.ventures.everypods.utils.AnnouncementRouteGate.Output
import io.automated.ventures.everypods.utils.AnnouncementRouteGate.OutputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementRouteGateTest {

    private val airPodsMac = "F4:6A:D7:12:AB:CD"
    private val airPods = Output(OutputKind.BT_A2DP, airPodsMac, "AirPods Pro")
    private val car = Output(OutputKind.BT_A2DP, "00:1A:7D:DA:71:13", "VW CAR")
    private val bleSpeaker = Output(OutputKind.BLE_SPEAKER, "11:22:33:44:55:66", "JBL Flip")
    private val speaker = Output(OutputKind.BUILTIN_SPEAKER, "", "Pixel 10")
    private val wired = Output(OutputKind.WIRED, "", "Wired")

    private fun input(
        route: List<Output>?,
        managed: String? = airPodsMac,
        btConnect: Boolean = true,
        otherBt: Boolean = false,
        preview: Boolean = false,
    ) = Input(route, managed, btConnect, otherBt, preview)

    private fun decide(i: Input) = AnnouncementRouteGate.decide(i)

    // --- Default (AirPods only) ------------------------------------------------

    @Test
    fun managedAirPodsRouteAnnounces() {
        val d = decide(input(listOf(airPods)))
        assertTrue(d.reason, d.announce)
        assertTrue(d.logLine.startsWith("Announce ok: route=bt_a2dp/AirPods Pro"))
    }

    @Test
    fun addressMatchIsCaseInsensitive() {
        assertTrue(decide(input(listOf(airPods), managed = airPodsMac.lowercase())).announce)
    }

    @Test
    fun otherBluetoothDeviceSkippedByDefault() {
        val d = decide(input(listOf(car)))
        assertFalse(d.announce)
        assertTrue(d.logLine, d.logLine.startsWith("Announce skip: route=bt_a2dp/VW CAR"))
        assertTrue(d.logLine, d.logLine.contains("not AirPods"))
    }

    @Test
    fun airPodsConnectedButCarHasTheRouteSkips() {
        // The AACP link to the AirPods may be up, but only the route counts.
        assertFalse(decide(input(listOf(car))).announce)
    }

    @Test
    fun bleSpeakerSkippedByDefault() {
        assertFalse(decide(input(listOf(bleSpeaker))).announce)
    }

    @Test
    fun phoneSpeakerSkipped() {
        val d = decide(input(listOf(speaker)))
        assertFalse(d.announce)
        assertTrue(d.logLine, d.logLine.startsWith("Announce skip: route=speaker"))
    }

    @Test
    fun wiredSkipped() {
        assertFalse(decide(input(listOf(wired))).announce)
    }

    @Test
    fun routeIncludingPhoneSpeakerSkippedEvenWithAirPods() {
        assertFalse(decide(input(listOf(airPods, speaker))).announce)
        assertFalse(decide(input(listOf(airPods, speaker), otherBt = true)).announce)
    }

    @Test
    fun unknownOrEmptyRouteSkips() {
        assertFalse(decide(input(null)).announce)
        assertFalse(decide(input(emptyList())).announce)
    }

    @Test
    fun missingBluetoothConnectSkipsGracefully() {
        val d = decide(input(listOf(airPods), btConnect = false))
        assertFalse(d.announce)
        assertTrue(d.reason, d.reason.contains("BLUETOOTH_CONNECT"))
    }

    @Test
    fun anonymisedRouteAddressDoesNotMatch() {
        val anon = airPods.copy(address = "XX:XX:XX:XX:AB:CD")
        assertFalse(decide(input(listOf(anon))).announce)
    }

    @Test
    fun noManagedAddressSkips() {
        assertFalse(decide(input(listOf(airPods), managed = null)).announce)
        assertFalse(decide(input(listOf(airPods), managed = "")).announce)
    }

    // --- "Also announce on other Bluetooth devices" ON ----------------------------

    @Test
    fun toggleOnAnnouncesOnOtherBluetooth() {
        assertTrue(decide(input(listOf(car), otherBt = true)).announce)
        assertTrue(decide(input(listOf(bleSpeaker), otherBt = true)).announce)
        assertTrue(decide(input(listOf(airPods), otherBt = true)).announce)
    }

    @Test
    fun toggleOnDoesNotNeedAddressOrPermission() {
        assertTrue(decide(input(listOf(car), managed = null, btConnect = false, otherBt = true)).announce)
    }

    @Test
    fun toggleOnStillNeverUsesPhoneSpeakerOrWired() {
        assertFalse(decide(input(listOf(speaker), otherBt = true)).announce)
        assertFalse(decide(input(listOf(wired), otherBt = true)).announce)
    }

    // --- Preview ----------------------------------------------------------------

    @Test
    fun previewAlwaysPlays() {
        assertTrue(decide(input(listOf(speaker), preview = true)).announce)
        assertTrue(decide(input(null, managed = null, btConnect = false, preview = true)).announce)
        assertTrue(decide(input(listOf(car), preview = true)).announce)
    }

    // --- Helpers ----------------------------------------------------------------

    @Test
    fun normalizeAddress() {
        assertEquals(airPodsMac, AnnouncementRouteGate.normalizeAddress(" f4:6a:d7:12:ab:cd "))
        assertNull(AnnouncementRouteGate.normalizeAddress("XX:XX:XX:XX:AB:CD"))
        assertNull(AnnouncementRouteGate.normalizeAddress("02:00:00:00:00:00"))
        assertNull(AnnouncementRouteGate.normalizeAddress("not-a-mac"))
        assertNull(AnnouncementRouteGate.normalizeAddress(null))
    }

    @Test
    fun logLineMasksAddress() {
        val line = decide(input(listOf(car))).logLine
        assertFalse(line, line.contains("00:1A:7D:DA:71:13"))
        assertTrue(line, line.contains("…71:13"))
    }
}
