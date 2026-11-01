package io.automated.ventures.everypods.bluetooth.audio

import io.automated.ventures.everypods.utils.peer.PassiveAcquisitionCoordinator.Event.ReadyToConnect
import org.junit.Assert.*
import org.junit.Test

class AudioPeerGrantBridgeTest {
    private class Backend : AudioPeerGrantBridge.Backend {
        var current = AudioPeerGrantBridge.Request("req1", "headset")
        var valid = true
        var commits = 0
        val cancelled = mutableListOf<String>()
        override fun begin() = current
        override fun isCurrent(request: AudioPeerGrantBridge.Request) = request == current
        override fun authorize(ready: ReadyToConnect, issueCommand: Boolean): Boolean {
            if (issueCommand) commits++
            return valid
        }
        override fun cancel(request: AudioPeerGrantBridge.Request) { cancelled.add(request.requestId) }
    }

    @Test fun `late old grant cannot authorize replacement or retry`() {
        val backend = Backend()
        val bridge = AudioPeerGrantBridge(backend)
        assertTrue(bridge.begin(1, 1))
        backend.current = AudioPeerGrantBridge.Request("req2", "headset")
        assertTrue(bridge.begin(1, 2))
        assertNull(bridge.ready(ReadyToConnect("req1", "headset", 1)))
        assertFalse(bridge.commit(1, 2))
        assertNotNull(bridge.ready(ReadyToConnect("req2", "headset", 2)))
        assertFalse(bridge.commit(1, 1))
        assertTrue(bridge.commit(1, 2))
        assertEquals(1, backend.commits)
        assertEquals(listOf("req1"), backend.cancelled)
    }

    @Test fun `expired reservation is not reused for the same audio intent`() {
        val backend = Backend()
        val bridge = AudioPeerGrantBridge(backend)
        assertTrue(bridge.begin(7, 1))
        backend.current = AudioPeerGrantBridge.Request("req2", "headset")
        assertTrue(bridge.begin(7, 1))
        assertNull(bridge.ready(ReadyToConnect("req1", "headset", 1)))
        assertNotNull(bridge.ready(ReadyToConnect("req2", "headset", 2)))
    }

    @Test fun `revoked peer grant fails immediately before dispatch`() {
        val backend = Backend()
        val bridge = AudioPeerGrantBridge(backend)
        bridge.begin(7, 1)
        bridge.ready(ReadyToConnect("req1", "headset", 1))
        assertTrue(bridge.isValid(7, 1))
        backend.valid = false
        assertFalse(bridge.commit(7, 1))
    }

    @Test fun `wrong headset and cancelled intent cannot use grant`() {
        val backend = Backend()
        val bridge = AudioPeerGrantBridge(backend)
        bridge.begin(7, 1)
        assertNull(bridge.ready(ReadyToConnect("req1", "other", 1)))
        bridge.cancel(8)
        assertNotNull(bridge.ready(ReadyToConnect("req1", "headset", 1)))
        bridge.cancel(7)
        assertFalse(bridge.commit(7, 1))
        assertEquals(listOf("req1"), backend.cancelled)
    }
}
