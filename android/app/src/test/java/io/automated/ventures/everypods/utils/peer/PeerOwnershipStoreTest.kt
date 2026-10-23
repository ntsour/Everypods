package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerOwnershipStoreTest {
    private var now = 100L
    private val store = PeerOwnershipStore(ElapsedClock { now }, freshnessMs = 35L)
    private val peers = setOf("AA", "BB", "CC")
    private val headset = "headset-1"

    @Test
    fun `unknown offline member blocks the whole configured group`() {
        store.configure(peers)
        observe("AA")
        observe("BB")
        assertFalse(store.allConfiguredPeersFresh(headset))
        assertFalse(store.allPeersRelease(headset))
    }

    @Test
    fun `fresh synchronized released peers allow only their known idle target`() {
        store.configure(peers)
        peers.forEach(::observe)
        assertTrue(store.allConfiguredPeersFresh(headset))
        assertTrue(store.allPeersRelease(headset))
        assertFalse(store.allPeersRelease("other-headset"))
    }

    @Test
    fun `held or unknown ownership and audio deny release decision`() {
        store.configure(peers)
        peers.forEach(::observe)
        store.observe(observation("CC", revision = 2, ownership = PeerOwnership.HELD))
        assertFalse(store.allPeersRelease(headset))
        store.observe(observation("CC", revision = 3, ownership = PeerOwnership.RELEASED, audioUse = PeerAudioUse.UNKNOWN))
        assertFalse(store.allPeersRelease(headset))
        store.observe(observation("CC", revision = 4, ownership = PeerOwnership.UNKNOWN, audioUse = PeerAudioUse.IDLE))
        assertTrue(store.allConfiguredPeersFresh(headset))
        assertFalse(store.allPeersRelease(headset))
    }

    @Test
    fun `expired observations and missing headset identity are not authoritative`() {
        store.configure(peers)
        peers.forEach(::observe)
        now = 136L
        assertFalse(store.allConfiguredPeersFresh(headset))
        now = 137L
        assertFalse(store.observe(observation("AA", receivedAt = 100L, revision = 2)))
        assertTrue(store.observe(observation("AA", revision = 2, target = null)))
        assertFalse(store.allConfiguredPeersFresh())
    }

    @Test
    fun `link loss and process reset invalidate the peer observation`() {
        store.configure(peers)
        peers.forEach(::observe)
        store.invalidate("BB")
        assertFalse(store.allConfiguredPeersFresh(headset))
        store.clear()
        assertFalse(store.allConfiguredPeersFresh(headset))
    }

    @Test
    fun `session change resets revision duplicate heartbeat refreshes age and stale revisions are rejected`() {
        store.configure(setOf("AA"))
        assertTrue(store.observe(observation("AA", revision = 12)))
        now = 120L
        assertTrue(store.observe(observation("AA", revision = 12, receivedAt = now)))
        assertTrue(store.allPeersRelease(headset))
        assertFalse(store.observe(observation("AA", revision = 11, receivedAt = now)))
        assertTrue(store.observe(observation("AA", session = "new-session", revision = 0)))
    }

    @Test
    fun `unconfigured or legacy observations cannot establish ownership`() {
        store.configure(setOf("AA"))
        assertFalse(store.observe(observation("BB")))
        assertFalse(store.observe(observation("AA", protocol = 1)))
        assertFalse(store.allConfiguredPeersFresh())
    }

    private fun observe(peer: String) {
        assertTrue(store.observe(observation(peer)))
    }

    private fun observation(
        peer: String,
        session: String = "session-1",
        revision: Long = 1,
        receivedAt: Long = now,
        ownership: PeerOwnership = PeerOwnership.RELEASED,
        audioUse: PeerAudioUse = PeerAudioUse.IDLE,
        target: String? = headset,
        protocol: Int = 2,
    ) = PeerObservation(peer, target, session, revision, receivedAt, ownership, audioUse, protocol)
}
