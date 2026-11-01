package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class RemoteCaseEvidenceExchangeTest {
    private val observer = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val owner = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val headset = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val event = UUID.fromString("00000000-0000-4000-8000-000000000004")
    private val nonce = UUID.fromString("00000000-0000-4000-8000-000000000005")
    private val observation = RemoteCaseEvidenceExchange.Observation(observer, headset, event, 100, true, true)
    private fun state() = RemoteCaseEvidenceExchange.ReceiverState(owner, observer, headset, 7, true, true, true)

    @Test fun `receiver accepts fresh matching reply once and emits bound challenge`() {
        val hint = RemoteCaseEvidenceMessage.Message.Hint(observer, headset, event)
        val response = RemoteCaseEvidenceMessage.Message.Confirmation(observer, headset, event, nonce, 100, true)
        val input = ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(hint) + RemoteCaseEvidenceMessage.encode(response))
        val output = ByteArrayOutputStream()
        val times = ArrayDeque(listOf(1_000L, 1_200L))
        val gate = RemoteCaseEvidenceGate()
        val result = RemoteCaseEvidenceExchange.receive(input, output, "peer", ::state, { times.removeFirst() }, { nonce }, gate)
        assertNotNull(result)
        assertEquals(4_700L, result!!.remainingFreshMs)
        assertEquals(RemoteCaseEvidenceMessage.Message.Challenge(owner, headset, event, nonce),
            RemoteCaseEvidenceMessage.decode(output.toByteArray()))
        val replay = ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(hint) + RemoteCaseEvidenceMessage.encode(response))
        assertNull(RemoteCaseEvidenceExchange.receive(replay, ByteArrayOutputStream(), "peer", ::state, { 1_300 }, { UUID.randomUUID() }, gate))
    }

    @Test fun `receiver rejects policy and local evidence changes during response`() {
        val hint = RemoteCaseEvidenceMessage.Message.Hint(observer, headset, event)
        val response = RemoteCaseEvidenceMessage.Message.Confirmation(observer, headset, event, nonce, 100, true)
        val input = ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(hint) + RemoteCaseEvidenceMessage.encode(response))
        var calls = 0
        val result = RemoteCaseEvidenceExchange.receive(input, ByteArrayOutputStream(), "peer",
            { if (calls++ == 0) state() else state().copy(localRevision = 8) }, { 1_000 }, { nonce }, RemoteCaseEvidenceGate())
        assertNull(result)
    }

    @Test fun `receiver rejects a peer restart while confirmation is in flight`() {
        val hint = RemoteCaseEvidenceMessage.Message.Hint(observer, headset, event)
        val response = RemoteCaseEvidenceMessage.Message.Confirmation(observer, headset, event, nonce, 100, true)
        val input = ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(hint) + RemoteCaseEvidenceMessage.encode(response))
        var calls = 0
        val result = RemoteCaseEvidenceExchange.receive(input, ByteArrayOutputStream(), "peer",
            { if (calls++ == 0) state() else state().copy(remoteSession = UUID.randomUUID()) },
            { 1_000 }, { nonce }, RemoteCaseEvidenceGate())
        assertNull(result)
    }

    @Test fun `observer answers only its current verified observation and current peer session`() {
        val challenge = RemoteCaseEvidenceMessage.Message.Challenge(owner, headset, event, nonce)
        fun send(currentSession: UUID?, current: RemoteCaseEvidenceExchange.Observation?): Pair<Boolean, ByteArray> {
            val output = ByteArrayOutputStream()
            val ok = RemoteCaseEvidenceExchange.send(ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(challenge)),
                output, observation, owner, { currentSession }, { current })
            return ok to output.toByteArray()
        }
        assertTrue(send(owner, observation).first)
        assertFalse(send(null, observation).first)
        assertFalse(send(owner, observation.copy(identityVerified = false)).first)
        assertFalse(send(owner, observation.copy(available = false)).first)
        assertFalse(send(owner, observation.copy(id = UUID.randomUUID())).first)
    }

    @Test fun `peer session loss between hint and challenge suppresses confirmation`() {
        val challenge = RemoteCaseEvidenceMessage.Message.Challenge(owner, headset, event, nonce)
        val output = ByteArrayOutputStream()
        var checks = 0
        val ok = RemoteCaseEvidenceExchange.send(ByteArrayInputStream(RemoteCaseEvidenceMessage.encode(challenge)), output,
            observation, owner, { if (checks++ == 0) owner else null }, { observation })
        assertFalse(ok)
        assertEquals(RemoteCaseEvidenceMessage.Message.Hint(observer, headset, event),
            RemoteCaseEvidenceMessage.decode(output.toByteArray()))
    }
}
