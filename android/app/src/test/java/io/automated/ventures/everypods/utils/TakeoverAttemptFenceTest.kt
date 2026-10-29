package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test

class TakeoverAttemptFenceTest {
    @Test fun `peer yield fences delayed native callback and every retry of older takeover`() {
        val fence = TakeoverAttemptFence()
        val oldTakeover = fence.next()
        assertTrue(fence.isCurrent(oldTakeover))
        val yield = fence.next()
        assertFalse(fence.isCurrent(oldTakeover))
        assertTrue(fence.isCurrent(yield))
        val newUserPlay = fence.next()
        assertFalse(fence.isCurrent(yield))
        assertFalse(fence.isCurrent(oldTakeover))
        assertTrue(fence.isCurrent(newUserPlay))
    }
    @Test fun `destroyed service cannot run a stale handover callback`() {
        val fence = TakeoverAttemptFence()
        val callback = fence.next()
        fence.next()
        assertFalse(fence.isCurrent(callback))
    }
}
