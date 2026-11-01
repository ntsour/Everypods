package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.EOFException

class RemoteCaseEvidenceMessageTest {
    private val session = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val headset = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val observation = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val nonce = UUID.fromString("00000000-0000-4000-8000-000000000004")

    @Test fun `messages round trip without touching existing v2 frames`() {
        val messages = listOf(RemoteCaseEvidenceMessage.Message.Hint(session, headset, observation),
            RemoteCaseEvidenceMessage.Message.Challenge(session, headset, observation, nonce),
            RemoteCaseEvidenceMessage.Message.Confirmation(session, headset, observation, nonce, 1234, true),
            RemoteCaseEvidenceMessage.Message.Withdrawal(session, headset, observation))
        messages.forEach { assertEquals(it, RemoteCaseEvidenceMessage.decode(RemoteCaseEvidenceMessage.encode(it))) }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Frame.Decoder().feed(RemoteCaseEvidenceMessage.encode(messages.first()))
        }
    }
    @Test fun `truncation extra bytes unknown type and invalid flags fail closed`() {
        val good = RemoteCaseEvidenceMessage.encode(RemoteCaseEvidenceMessage.Message.Confirmation(
            session, headset, observation, nonce, 100, true))
        assertThrows(IllegalArgumentException::class.java) { RemoteCaseEvidenceMessage.decode(good.copyOf(good.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { RemoteCaseEvidenceMessage.decode(good + 0) }
        assertThrows(IllegalArgumentException::class.java) { RemoteCaseEvidenceMessage.decode(good.clone().also { it[4] = 99 }) }
        assertThrows(IllegalArgumentException::class.java) { RemoteCaseEvidenceMessage.decode(good.clone().also { it[it.lastIndex] = 2 }) }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteCaseEvidenceMessage.encode(RemoteCaseEvidenceMessage.Message.Confirmation(session, headset, observation, nonce, 5001, true))
        }
    }
    @Test fun `stream reads fragmented frames and rejects a partial ending`() {
        val hint = RemoteCaseEvidenceMessage.Message.Hint(session, headset, observation)
        val challenge = RemoteCaseEvidenceMessage.Message.Challenge(session, headset, observation, nonce)
        val payload = RemoteCaseEvidenceMessage.encode(hint) + RemoteCaseEvidenceMessage.encode(challenge)
        val stream = object : ByteArrayInputStream(payload) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len.coerceAtMost(1))
        }
        assertEquals(hint, RemoteCaseEvidenceMessage.read(stream))
        assertEquals(challenge, RemoteCaseEvidenceMessage.read(stream))
        assertEquals(null, RemoteCaseEvidenceMessage.read(stream))
        assertThrows(EOFException::class.java) {
            RemoteCaseEvidenceMessage.read(ByteArrayInputStream(payload.copyOf(7)))
        }
    }
}
