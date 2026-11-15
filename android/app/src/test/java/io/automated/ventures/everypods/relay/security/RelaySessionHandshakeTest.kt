package io.automated.ventures.everypods.relay.security

import org.junit.Assert.*
import org.junit.Test

class RelaySessionHandshakeTest {
    @Test fun `fresh responder challenge changes keys and prevents replay`() {
        val initiator = ByteArray(32) { 1 }
        val oldNonce = RelaySessionHandshake.sessionNonce(initiator, ByteArray(32) { 2 })
        val newNonce = RelaySessionHandshake.sessionNonce(initiator, ByteArray(32) { 3 })
        assertFalse(oldNonce.contentEquals(newNonce))
        val oldSender = AuthenticatedSignalCodec(oldNonce, true)
        val oldFrame = oldSender.encode("control".toByteArray())
        val newReceiver = AuthenticatedSignalCodec(newNonce, false)
        assertThrows(Exception::class.java) { newReceiver.decode(oldFrame) }
        val newSender = AuthenticatedSignalCodec(newNonce, true)
        assertArrayEquals("control".toByteArray(), newReceiver.decode(newSender.encode("control".toByteArray())))
    }

    @Test fun `transcript binds the two roles`() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertFalse(RelaySessionHandshake.sessionNonce(a, b).contentEquals(RelaySessionHandshake.sessionNonce(b, a)))
        assertThrows(IllegalArgumentException::class.java) { RelaySessionHandshake.sessionNonce(ByteArray(31), b) }
    }
}
