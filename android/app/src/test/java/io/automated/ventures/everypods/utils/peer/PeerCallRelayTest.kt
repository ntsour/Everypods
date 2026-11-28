package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerCallRelayTest {
    private val sent = mutableListOf<Pair<String, Boolean>>()
    private val relay = PeerCallRelay { mac, active -> sent += mac to active }
    private val mac = "68:5E:DD:55:7E:17"
    private val pc = "98:59:7A:10:41:E9"

    @Test fun `a PC call is passed to the Mac only, once, and cleared when it ends`() {
        relay.update(setOf(mac, pc), emptySet())
        assertEquals(emptyList<Pair<String, Boolean>>(), sent)  // both start idle: nothing to say
        relay.update(setOf(mac, pc), setOf(pc))
        assertEquals(listOf(mac to true), sent)  // never the PC about its own call
        relay.update(setOf(mac, pc), setOf(pc))
        assertEquals(1, sent.size)  // no repeat without a change
        relay.update(setOf(mac, pc), emptySet())
        assertEquals(listOf(mac to true, mac to false), sent)
    }

    @Test fun `a computer that relinks is told again`() {
        relay.update(setOf(mac, pc), setOf(pc))
        relay.linkDown(mac.lowercase())
        relay.update(setOf(pc), setOf(pc))
        relay.update(setOf(mac, pc), setOf(pc))
        assertEquals(listOf(mac to true, mac to true), sent)
    }

    @Test fun `an unlinked computer is dropped and starts idle again`() {
        relay.update(setOf(mac), setOf(pc))
        relay.update(emptySet(), setOf(pc))  // the Mac's link went away without linkDown
        relay.update(setOf(mac), emptySet())
        assertEquals(listOf(mac to true), sent)  // idle again: nothing to clear
    }

    @Test fun `a phone call reaches every computer`() {
        relay.update(setOf(mac, pc), setOf("AA:BB:CC:DD:EE:FF"))
        assertEquals(listOf(pc to true, mac to true).sortedBy { it.first }, sent.sortedBy { it.first })
    }

    @Test fun `only fresh peers holding this headset in a call count`() {
        fun obs(peer: String, ownership: PeerOwnership, headset: String? = "h1") =
            PeerObservation(peer, headset, "s", 1, 0, ownership, PeerAudioUse.CALL, 2)
        val observations = mapOf(
            pc to obs(pc, PeerOwnership.HELD),
            "AA" to obs("AA", PeerOwnership.RELEASED),           // in a call without the AirPods
            "BB" to obs("BB", PeerOwnership.HELD, headset = "h2"), // another headset
            "CC" to obs("CC", PeerOwnership.HELD),                // stale: not in the fresh map
        )
        val fresh = mapOf(pc to PeerAudioUse.CALL, "AA" to PeerAudioUse.CALL, "BB" to PeerAudioUse.CALL)
        assertEquals(setOf(pc), PeerCallRelay.peersInCall(observations, fresh, "h1"))
        assertEquals(setOf(pc, "BB"), PeerCallRelay.peersInCall(observations, fresh, null))
        assertEquals(emptySet<String>(), PeerCallRelay.peersInCall(observations, mapOf(pc to PeerAudioUse.MEDIA), "h1"))
    }
}
