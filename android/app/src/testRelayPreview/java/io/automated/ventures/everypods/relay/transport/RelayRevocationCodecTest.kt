package io.automated.ventures.everypods.relay.transport

import org.junit.Assert.*
import org.junit.Test

class RelayRevocationCodecTest {
    @Test fun revocationAndAcknowledgementRemainDistinctAndRoundTrip() {
        val revoke = RelaySignalCodec.encode(WebRtcRelayTransport.Signal.RevokeRelay)
        val ack = RelaySignalCodec.encode(WebRtcRelayTransport.Signal.RevokeRelayAck)
        assertFalse(revoke.contentEquals(ack))
        assertEquals(WebRtcRelayTransport.Signal.RevokeRelay, RelaySignalCodec.decode(revoke))
        assertEquals(WebRtcRelayTransport.Signal.RevokeRelayAck, RelaySignalCodec.decode(ack))
    }
    @Test fun malformedRevocationIsRejected() {
        val encoded = RelaySignalCodec.encode(WebRtcRelayTransport.Signal.RevokeRelay)
        assertThrows(IllegalArgumentException::class.java) { RelaySignalCodec.decode(encoded + byteArrayOf(1)) }
    }
}
