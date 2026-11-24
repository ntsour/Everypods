package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.utils.peer.PeerAudioUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerReleaseGateTest {
    private val pc = "98:59:7A:10:41:E9"
    private val phone = "68:4D:B6:A3:8D:7E"
    private val isComputer: (String) -> Boolean = { it == pc }

    @Test
    fun `waits only for an online computer that holds the AirPods`() {
        assertEquals(setOf(pc), ComputerReleaseGate.computerHolders(listOf(pc, phone), setOf(pc, phone), isComputer))
    }

    @Test
    fun `no wait when the computer is not the holder or a phone holds them`() {
        assertEquals(emptySet<String>(), ComputerReleaseGate.computerHolders(listOf(pc, phone), setOf(phone), isComputer))
        assertEquals(emptySet<String>(), ComputerReleaseGate.computerHolders(listOf(phone), setOf(pc), isComputer))
    }

    @Test
    fun `address case does not matter`() {
        assertEquals(setOf(pc), ComputerReleaseGate.computerHolders(listOf(pc.lowercase()), setOf(pc), isComputer))
    }

    @Test
    fun `ack timeout outlasts the Windows client's 5 s link-down wait`() {
        assertTrue(ComputerReleaseGate.ACK_TIMEOUT_MS > 5_000L)
    }

    @Test
    fun `a computer holder in a call blocks the automatic takeover`() {
        assertEquals(setOf(pc), ComputerReleaseGate.computersInCall(setOf(pc), mapOf(pc to PeerAudioUse.CALL)))
    }

    @Test
    fun `media, idle or unknown audio on the computer does not block`() {
        listOf(PeerAudioUse.MEDIA, PeerAudioUse.IDLE, PeerAudioUse.UNKNOWN).forEach { use ->
            assertEquals(emptySet<String>(), ComputerReleaseGate.computersInCall(setOf(pc), mapOf(pc to use)))
        }
    }

    @Test
    fun `no snapshot from the computer does not block`() {
        assertEquals(emptySet<String>(), ComputerReleaseGate.computersInCall(setOf(pc), emptyMap()))
    }

    @Test
    fun `a peer in a call that is not a computer holder does not block`() {
        assertEquals(emptySet<String>(), ComputerReleaseGate.computersInCall(emptySet(), mapOf(phone to PeerAudioUse.CALL)))
    }

    @Test
    fun `call check ignores address case`() {
        assertEquals(setOf(pc), ComputerReleaseGate.computersInCall(setOf(pc.lowercase()), mapOf(pc to PeerAudioUse.CALL)))
        assertEquals(setOf(pc), ComputerReleaseGate.computersInCall(setOf(pc), mapOf(pc.lowercase() to PeerAudioUse.CALL)))
    }
}
