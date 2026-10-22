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

    @Test
    fun lowerBufferValuesRoundTripWithoutChangingLegacyWireCodes() {
        for (buffer in listOf(10, 20, 30)) {
            for (jitter in listOf(20, 30, 40)) {
                val profile = RelayAudioTuning(initialJitterMs = jitter, maximumJitterMs = 60,
                    frameDurationMs = 10, playbackBufferMs = buffer, captureBufferMs = buffer)
                assertEquals(profile, RelayAudioTuning.decodeWire(profile.encodeWire()))
            }
        }
        for ((code, value) in listOf(0, 40, 60, 80, 120, 160, 200).withIndex()) {
            val bytes = RelayAudioTuning(playbackBufferMs = value).encodeWire()
            assertEquals(code, bytes[9].toInt())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayAudioTuning(initialJitterMs = 30, frameDurationMs = 20).validated()
        }
    }
}
