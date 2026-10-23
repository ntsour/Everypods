package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerHeartbeatTest {
    @Test fun `only matching pong clears outstanding heartbeat`() {
        val heartbeat = PeerHeartbeat { "nonce-1" }
        assertEquals("nonce-1", heartbeat.startPing())
        assertFalse(heartbeat.acknowledge("old-nonce"))
        assertTrue(heartbeat.hasOutstandingPing())
        assertEquals(PeerHeartbeatAction.AwaitResponse, heartbeat.onInterval())
        assertTrue(heartbeat.acknowledge("nonce-1"))
        assertFalse(heartbeat.hasOutstandingPing())
    }

    @Test fun `two response intervals expire a missing heartbeat`() {
        val heartbeat = PeerHeartbeat { "nonce-1" }
        heartbeat.startPing()
        assertEquals(PeerHeartbeatAction.AwaitResponse, heartbeat.onInterval())
        assertEquals(PeerHeartbeatAction.TimedOut, heartbeat.onInterval())
    }

    @Test fun `interval after healthy response starts a fresh nonce`() {
        var id = 0
        val heartbeat = PeerHeartbeat { "nonce-${++id}" }
        assertEquals("nonce-1", heartbeat.startPing())
        assertTrue(heartbeat.acknowledge("nonce-1"))
        assertEquals(PeerHeartbeatAction.SendPing("nonce-2"), heartbeat.onInterval())
    }
}
