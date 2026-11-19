package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetLinkRestoreTest {
    @Test
    fun `last holder restores audio when the AirPods link back on their own`() {
        assertTrue(HeadsetLinkRestore.shouldRestore(
            autoConnectEnabled = true, lastHolderHere = true, peerHolding = false, peerUsingAudio = false))
    }

    @Test
    fun `never restores when another phone has or uses the AirPods, or this phone is not last holder`() {
        assertFalse(HeadsetLinkRestore.shouldRestore(true, true, peerHolding = true, peerUsingAudio = false))
        assertFalse(HeadsetLinkRestore.shouldRestore(true, true, peerHolding = false, peerUsingAudio = true))
        assertFalse(HeadsetLinkRestore.shouldRestore(true, lastHolderHere = false, peerHolding = false, peerUsingAudio = false))
        assertFalse(HeadsetLinkRestore.shouldRestore(autoConnectEnabled = false, true, false, false))
    }
}
