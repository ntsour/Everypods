package io.automated.ventures.everypods.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximitySignalPipelineTest {

    @Test
    fun medianOf_oddAndEvenWindows() {
        assertEquals(5f, DeviceSignalTracker.medianOf(listOf(1, 5, 9)))
        assertEquals(6.5f, DeviceSignalTracker.medianOf(listOf(4, 9, 5, 8)))
    }

    @Test
    fun spikeReject_requiresConfirmation() {
        val tracker = DeviceSignalTracker()
        var t = 1_000L
        repeat(5) {
            tracker.push(-65, txPower = null, nowMs = t)
            t += 200L
        }
        val before = tracker.push(-65, null, t).smoothedRssi
        t += 200L
        val spiked = tracker.push(-25, null, t)
        assertTrue(
            "single spike should be rejected or heavily limited",
            kotlin.math.abs(spiked.smoothedRssi - before) < 15f
        )
        t += 200L
        val confirmed = tracker.push(-25, null, t) // confirms spike
        t += 200L
        val settled = tracker.push(-25, null, t)
        assertTrue(
            "confirmed spike should pull smoothed RSSI upward",
            settled.smoothedRssi > before + 5f
        )
    }

    @Test
    fun bandHysteresis_sameRoomThroughWallAndBack() {
        var band = RoomBand.SEARCHING
        // SAME_ROOM_LOW=-61 → enter Same room with -59
        band = DeviceSignalTracker.updateBand(band, -59f, pathLossDb = null)
        assertEquals(RoomBand.SAME_ROOM, band)

        // Exit low = -64; stay inside hysteresis
        band = DeviceSignalTracker.updateBand(band, -63f, null)
        assertEquals("should stay SAME_ROOM inside exit hysteresis", RoomBand.SAME_ROOM, band)

        band = DeviceSignalTracker.updateBand(band, -66f, null)
        assertEquals(RoomBand.NEXT_ROOM, band)

        band = DeviceSignalTracker.updateBand(band, -85f, null)
        assertEquals(RoomBand.FARTHER, band)

        // NEXT_ROOM enter from far = -74
        band = DeviceSignalTracker.updateBand(band, -73f, null)
        assertEquals(RoomBand.NEXT_ROOM, band)

        // SAME_ROOM enter from next = -58
        band = DeviceSignalTracker.updateBand(band, -57f, null)
        assertEquals(RoomBand.SAME_ROOM, band)

        band = DeviceSignalTracker.updateBand(band, -50f, null)
        assertEquals(RoomBand.RIGHT_HERE, band)

        band = DeviceSignalTracker.updateBand(band, -56f, null)
        assertEquals(RoomBand.RIGHT_HERE, band)
        band = DeviceSignalTracker.updateBand(band, -59f, null)
        assertEquals(RoomBand.SAME_ROOM, band)
    }

    @Test
    fun trend_classifiesCloserFartherStable() {
        val tracker = DeviceSignalTracker()
        var t = 10_000L
        repeat(4) {
            val snap = tracker.push(-88, null, t)
            t += 300L
            if (it == 3) assertEquals(RoomBand.FARTHER, snap.band)
        }
        var lastTrend = SignalTrend.STABLE
        for (rssi in listOf(-82, -76, -70, -64, -58, -52, -48)) {
            lastTrend = tracker.push(rssi, null, t).trend
            t += 500L
        }
        assertEquals(SignalTrend.CLOSER, lastTrend)

        for (rssi in listOf(-52, -60, -68, -76, -84, -90)) {
            lastTrend = tracker.push(rssi, null, t).trend
            t += 500L
        }
        assertEquals(SignalTrend.FARTHER, lastTrend)
    }


    @Test
    fun trend_farBand_respondsToSmallDeltas() {
        val tracker = DeviceSignalTracker()
        var t = 20_000L
        // Settle in FARTHER (~-90)
        repeat(8) {
            tracker.push(-90, null, t)
            t += 400L
        }
        // Slow walk closer with ~0.7 dB steps — under near threshold but over FAR 0.6
        var last = SignalTrend.STABLE
        for (rssi in listOf(-89, -88, -87, -86, -85, -84, -83, -82)) {
            last = tracker.push(rssi, null, t).trend
            t += 450L
        }
        assertEquals(SignalTrend.CLOSER, last)
    }

    @Test
    fun pathLoss_nudgesNearEdge() {
        val band = DeviceSignalTracker.updateBand(
            current = RoomBand.SAME_ROOM,
            rssi = -60f,
            pathLossDb = 75f,
        )
        assertEquals(RoomBand.NEXT_ROOM, band)

        val closer = DeviceSignalTracker.updateBand(
            current = RoomBand.SAME_ROOM,
            rssi = -56f,
            pathLossDb = 40f,
        )
        assertEquals(RoomBand.RIGHT_HERE, closer)
    }
}
