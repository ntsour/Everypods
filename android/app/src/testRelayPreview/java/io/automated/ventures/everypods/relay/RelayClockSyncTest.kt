package io.automated.ventures.everypods.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayClockSyncTest {
    @Test
    fun estimatesOffsetAndRoundTripWithProcessingTimeRemoved() {
        // Source clock is 120 ms ahead; network is 10 ms each way and source processing is 2 ms.
        val sample = RelayClockSyncSample.calculate(
            localSend = 1_000_000_000,
            remoteReceive = 1_130_000_000,
            remoteSend = 1_132_000_000,
            localReceive = 1_022_000_000,
        )
        assertEquals(120_000_000L, sample?.remoteMinusLocalNanos)
        assertEquals(20_000_000L, sample?.roundTripNanos)
    }

    @Test
    fun rejectsNonMonotonicClockSamples() {
        assertNull(RelayClockSyncSample.calculate(10, 20, 30, 5))
        assertNull(RelayClockSyncSample.calculate(10, 30, 20, 40))
    }
}
