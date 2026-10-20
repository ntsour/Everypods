package io.automated.ventures.everypods.relay.transport

import io.automated.ventures.everypods.relay.RelayAudioTuning
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayTuningWireTest {
    @Test
    fun requestAndOfferCarryTheSameNegotiatedProfile() {
        val profile = RelayAudioTuning(frameDurationMs = 10, bitrateKbps = 128, retransmissionMs = 40)
        val request = WebRtcRelayTransport.Signal.RequestPath("auto", profile)
        assertEquals(request, RelaySignalCodec.decode(RelaySignalCodec.encode(request)))
        val offer = WebRtcRelayTransport.Signal.OfferPath("192.168.4.2", 24, profile)
        assertEquals(offer, RelaySignalCodec.decode(RelaySignalCodec.encode(offer)))
    }

    @Test
    fun mediaPacketAcceptsBothSupportedFrameDurationsAndChecksNegotiatedDuration() {
        for (duration in listOf(10, 20)) {
            val encoded = RelayMediaPacket(0, 1_000_000, duration, byteArrayOf(4)).encode()
            assertEquals(duration, RelayMediaPacket.decode(encoded, duration).durationMillis)
        }
    }
}
