package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCaseEvidenceGateTest {
    private fun request() = RemoteCaseEvidenceGate.Challenge("nonce", "observation", "peer", "session", "headset", 1_000, 4)
    private fun reply() = RemoteCaseEvidenceGate.Reply("nonce", "observation", "peer", "session", "headset", 500, true, true)
    private fun gate() = RemoteCaseEvidenceGate().also { assertTrue(it.begin(request(), true, true, true)) }
    private fun RemoteCaseEvidenceGate.receive(reply: RemoteCaseEvidenceGate.Reply = reply(), now: Long = 2_000,
                                                session: String? = "session", headset: String? = "headset",
                                                revision: Long = 4, enabled: Boolean = true,
                                                holder: Boolean = true, sync: Boolean = true) =
        accept(reply, now, session, headset, revision, enabled, holder, sync)

    @Test fun `fresh confirmed report is one use`() {
        val gate = gate()
        assertTrue(gate.receive())
        assertFalse(gate.receive())
        assertFalse(gate.begin(request().copy(nonce = "new", startedAtMs = 2_001), true, true, true))
    }
    @Test fun `missing policy eligibility or synchronization cannot initiate`() {
        assertFalse(RemoteCaseEvidenceGate().begin(request(), false, true, true))
        assertFalse(RemoteCaseEvidenceGate().begin(request(), true, false, true))
        assertFalse(RemoteCaseEvidenceGate().begin(request(), true, true, false))
    }
    @Test fun `stale slow unverified or unavailable reports cannot authorize`() {
        assertFalse(gate().receive(reply().copy(observationAgeMs = 4_500)))
        assertFalse(gate().receive(now = 3_001))
        assertFalse(gate().receive(now = 999))
        assertFalse(gate().receive(reply().copy(identityVerified = false)))
        assertFalse(gate().receive(reply().copy(available = false)))
    }
    @Test fun `session headset local evidence and policy changes invalidate a reply`() {
        assertFalse(gate().receive(session = "new"))
        assertFalse(gate().receive(headset = "other"))
        assertFalse(gate().receive(revision = 5))
        assertFalse(gate().receive(enabled = false))
        assertFalse(gate().receive(holder = false))
        assertFalse(gate().receive(sync = false))
    }
    @Test fun `unrelated reply does not consume a live challenge`() {
        val gate = gate()
        assertFalse(gate.receive(reply().copy(nonce = "other")))
        assertTrue(gate.receive())
    }
    @Test fun `lost reply does not hold a challenge forever`() {
        val gate = gate()
        assertFalse(gate.begin(request().copy(nonce = "next", startedAtMs = 3_000), true, true, true))
        assertTrue(gate.begin(request().copy(nonce = "next", startedAtMs = 3_001), true, true, true))
        assertFalse(gate.receive(reply()))
        assertTrue(gate.receive(reply().copy(nonce = "next"), now = 3_100))
    }
    @Test fun `withdrawal cancels pending and prevents a late hint`() {
        val gate = gate()
        gate.withdraw("peer", "session", "observation", 1_200)
        assertFalse(gate.receive())
        assertTrue(gate.isWithdrawn("peer", "session", "observation", 1_300))
        assertFalse(gate.begin(request().copy(nonce = "next", startedAtMs = 1_300), true, true, true))
    }
    @Test fun `withdrawal after confirmation blocks later dispatch checks`() {
        val gate = gate()
        assertTrue(gate.receive(now = 1_200))
        gate.withdraw("peer", "session", "observation", 1_300)
        assertTrue(gate.isWithdrawn("peer", "session", "observation", 1_400))
    }
}
