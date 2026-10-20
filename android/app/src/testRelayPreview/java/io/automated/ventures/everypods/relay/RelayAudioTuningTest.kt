package io.automated.ventures.everypods.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayAudioTuningTest {
    @Test
    fun defaultProfileRoundTripsTheNegotiationWireFormat() {
        assertEquals(RelayAudioTuning.DEFAULT, RelayAudioTuning.decodeWire(RelayAudioTuning.DEFAULT.encodeWire()))
    }

    @Test
    fun nonDefaultProfileRoundTripsEveryTunableValue() {
        val tuning = RelayAudioTuning(
            initialJitterMs = 60,
            maximumJitterMs = 300,
            adaptiveBufferMode = 2,
            playbackBufferMs = 160,
            captureBufferMs = 120,
            frameDurationMs = 10,
            lowDelayMode = true,
            bitrateKbps = 256,
            retransmissionMs = 20,
        )
        assertEquals(tuning, RelayAudioTuning.decodeWire(tuning.encodeWire()))
    }

    @Test
    fun invalidProfileAndWireLengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RelayAudioTuning(initialJitterMs = 200, maximumJitterMs = 100).encodeWire()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayAudioTuning.decodeWire(ByteArray(RelayAudioTuning.WIRE_BYTES - 1))
        }
    }
}
