package io.automated.ventures.everypods.utils

import org.junit.Assert.assertEquals
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
    fun `settles only after a confirmed release`() {
        assertEquals(ComputerReleaseGate.SETTLE_MS, ComputerReleaseGate.connectDelayMs(released = true))
        assertEquals(0L, ComputerReleaseGate.connectDelayMs(released = false))
    }
}
