package io.automated.ventures.everypods.bluetooth.hrm

import io.automated.ventures.everypods.data.BatteryComponent
import io.automated.ventures.everypods.data.BatteryStatus
import org.junit.Assert.*
import org.junit.Test

class HeartRateCaseStorageTest {
    private var now = 100L
    private val storage = HeartRateCaseStorage { now }
    private fun packet(left: Int = BatteryStatus.CHARGING, right: Int = BatteryStatus.CHARGING) =
        ByteArray(22).apply {
            byteArrayOf(4, 0, 4, 0, 4, 0).copyInto(this)
            this[7] = BatteryComponent.LEFT.toByte(); this[10] = left.toByte()
            this[12] = BatteryComponent.RIGHT.toByte(); this[15] = right.toByte()
        }

    @Test fun `both buds charging confirms storage even on the first packet and without a lid event`() {
        assertEquals(true, storage.report(packet(right = BatteryStatus.OPTIMIZED_CHARGING), 1, now, 1))
        assertTrue(storage.confirmed(1))
    }

    @Test fun `one stored bud and out of ear alone cannot stop a session`() {
        assertEquals(false, storage.report(packet(right = BatteryStatus.NOT_CHARGING), 1, now, 1))
        assertFalse(storage.confirmed(1))
        assertEquals(false, storage.report(packet(left = BatteryStatus.DISCONNECTED), 1, now, 1))
    }

    @Test fun `stale delayed and previous connection packets cannot end a new session`() {
        assertNull(storage.report(packet(), 1, now, 2))
        assertNull(storage.report(packet(), 2, now + 1, 2))
        now += 5_000
        assertNull(storage.report(packet(), 2, 100, 2))
        assertFalse(storage.confirmed(2))
        assertEquals(true, storage.report(packet(), 2, now, 2))
        assertFalse(storage.confirmed(3))
        now += 5_000
        assertFalse(storage.confirmed(2))
    }

    @Test fun `removing buds clears evidence and malformed or unrelated packets never confirm storage`() {
        assertNull(storage.report(ByteArray(22), 1, now, 1))
        assertNull(storage.report(packet().copyOf(21), 1, now, 1))
        assertEquals(true, storage.report(packet(), 1, now, 1))
        assertEquals(false, storage.report(packet(BatteryStatus.NOT_CHARGING, BatteryStatus.NOT_CHARGING), 1, now, 1))
        assertFalse(storage.confirmed(1))
    }

    @Test fun `component order is irrelevant but both actual buds must be present`() {
        val reversed = packet().apply { this[7] = BatteryComponent.RIGHT.toByte(); this[12] = BatteryComponent.LEFT.toByte() }
        assertEquals(true, storage.report(reversed, 1, now, 1))
        val missing = packet().apply { this[12] = BatteryComponent.CASE.toByte() }
        assertEquals(false, storage.report(missing, 1, now, 1))
    }

    @Test fun `an older delayed storage report cannot undo a newer removal report`() {
        now += 100
        assertEquals(false, storage.report(packet(BatteryStatus.NOT_CHARGING), 1, now, 1))
        assertNull(storage.report(packet(), 1, now - 1, 1))
        assertFalse(storage.confirmed(1))
    }
}
