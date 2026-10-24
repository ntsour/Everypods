package io.automated.ventures.everypods.bluetooth.hrm

import org.junit.Assert.*
import org.junit.Test

class HeartRateSharingTest {
    @Test fun `first real sample precedes pairing and stop clears saved TICKR`() {
        val commands = mutableListOf<String>()
        val relay = WahooHeartRateRelay({ commands += "value:$it" }, { commands += "pair" })
        relay.publish(0)
        relay.publish(200)
        relay.publish(83)
        relay.stop()
        relay.stop()
        relay.publish(80)
        assertEquals(listOf("value:200", "pair", "value:83", "value:0"), commands)
        assertEquals(2L, relay.sent)
    }
    @Test fun `stale reset is idempotent and fresh readings can resume`() {
        val values = mutableListOf<Int>()
        val relay = WahooHeartRateRelay({ values += it }, {})
        relay.publish(75)
        relay.clearReading()
        relay.clearReading()
        relay.publish(76)
        relay.clearReading()
        relay.stop()
        assertEquals(listOf(75, 0, 76, 0, 0), values)
    }
    @Test fun `stop before first reading sends zero without pairing`() {
        val values = mutableListOf<Int>()
        val relay = WahooHeartRateRelay({ values += it }, { fail("Must not pair without real data") })
        relay.stop()
        assertEquals(listOf(0), values)
    }
    @Test fun `receiver reconnect waits for a fresh sample before pairing again`() {
        val commands = mutableListOf<String>()
        val relay = WahooHeartRateRelay({ commands += "value:$it" }, { commands += "pair" })
        relay.publish(70)
        relay.clearReading()
        relay.resetPairing()
        assertEquals(listOf("value:70", "pair", "value:0"), commands)
        relay.publish(71)
        assertEquals(listOf("value:70", "pair", "value:0", "value:71", "pair"), commands)
    }
    @Test fun `failed initial value cannot pair or count a sample`() {
        val relay = WahooHeartRateRelay({ error("send failed") }, { fail("Must not pair") })
        runCatching { relay.publish(80) }
        assertEquals(0L, relay.sent)
    }
    @Test fun `manual stop suppresses gym automation until gym is turned off`() {
        val intent = HeartRateSharingIntent()
        intent.start(HeartRateSharingMethod.TICKR, gym = true)
        assertTrue(intent.gymOwned)
        intent.stop(manual = true)
        assertNull(intent.method)
        assertTrue(intent.suppressedForGym)
        intent.gymChanged(false)
        assertFalse(intent.suppressedForGym)
        intent.start(HeartRateSharingMethod.BLUETOOTH, gym = true)
        assertEquals(HeartRateSharingMethod.BLUETOOTH, intent.method)
    }
    @Test fun `manual sessions are not owned by gym and intent is not restored`() {
        val intent = HeartRateSharingIntent()
        intent.start(HeartRateSharingMethod.TICKR, gym = false)
        intent.gymChanged(false)
        assertFalse(intent.gymOwned)
        assertNotNull(intent.method)
        assertNull(HeartRateSharingIntent().method)
    }
}
