package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.peer.PeerIdleProbe.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerIdleProbeTest {
    private var n = 0
    private fun probe(start: Long = 0L) = PeerIdleProbe(start) { "nonce-${++n}" }

    @Test fun `no probe during the first silent minute, then one, then one more, then timed out`() {
        val p = probe()
        assertEquals(Action.Wait, p.onTick(59_999L))
        assertEquals(60_000L, p.nextDueMs())
        assertEquals(Action.SendPing("nonce-1"), p.onTick(60_000L))
        assertEquals("nonce-1", p.outstandingNonce())
        assertEquals(Action.Wait, p.onTick(74_999L))
        assertEquals(Action.SendPing("nonce-2"), p.onTick(75_000L))
        assertEquals(90_000L, p.nextDueMs())
        assertEquals(Action.TimedOut, p.onTick(90_000L))
    }

    @Test fun `any frame answers the probe and restarts the minute`() {
        val p = probe()
        p.onTick(60_000L)
        p.onFrameReceived(61_000L)
        assertEquals(121_000L, p.nextDueMs())
        assertEquals("the probe's PONG still matches after another frame", true, p.acknowledge("nonce-1"))
        assertNull(p.outstandingNonce())
        assertEquals(false, p.acknowledge("nonce-1"))
        assertEquals(Action.Wait, p.onTick(120_000L))
        assertEquals(Action.SendPing("nonce-2"), p.onTick(121_000L))
    }

    @Test fun `steady traffic never probes`() {
        val p = probe()
        for (t in 10_000L..600_000L step 10_000L) {
            p.onFrameReceived(t)
            assertEquals(Action.Wait, p.onTick(t + 5_000L))
        }
    }
}
