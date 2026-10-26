package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.data.*
import org.junit.Assert.*
import org.junit.Test

class HeadGesturePolicyTest {
    private val settings = HeadGestureSettings(enabled = true, normal = mapOf(HeadGesture.NOD to StemAction.PLAY_PAUSE))
    @Test fun `context priority interrupts test for ringing and active calls`() {
        assertEquals(HeadGestureContext.RINGING, HeadGesturePolicy.context(true, true, true, true))
        assertEquals(HeadGestureContext.ACTIVE_CALL, HeadGesturePolicy.context(false, true, true, true))
        assertEquals(HeadGestureContext.TEST, HeadGesturePolicy.context(false, false, true, true))
        assertEquals(HeadGestureContext.GYM, HeadGesturePolicy.context(false, false, false, true))
        assertEquals(HeadGestureContext.NORMAL, HeadGesturePolicy.context(false, false, false, false))
    }
    @Test fun `disconnect and unknown or removed earbuds suspend actions`() {
        for (local in listOf(false, true)) for (worn in listOf(false, true)) {
            val owners = HeadGesturePolicy.owners(settings, HeadGestureContext.NORMAL, local, worn, false)
            assertEquals(local && worn, owners.detect)
            assertEquals(local && worn, owners.track)
        }
    }
    @Test fun `releasing diagnostics does not stop actions and disabling actions does not stop diagnostics`() {
        assertTrue(HeadGesturePolicy.owners(settings, HeadGestureContext.NORMAL, true, true, false).track)
        val disabled = settings.copy(enabled = false)
        assertFalse(HeadGesturePolicy.owners(disabled, HeadGestureContext.NORMAL, true, true, true).detect)
        assertTrue(HeadGesturePolicy.owners(disabled, HeadGestureContext.NORMAL, true, true, true).track)
        assertFalse(HeadGesturePolicy.owners(disabled, HeadGestureContext.NORMAL, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(disabled, HeadGestureContext.NORMAL, false, true, true).track)
    }
    @Test fun `test cancellation restores mapped actions and Gym does not inherit`() {
        val disabled = settings.copy(enabled = false)
        assertTrue(HeadGesturePolicy.owners(disabled, HeadGestureContext.TEST, true, true, false).detect)
        assertFalse(HeadGesturePolicy.owners(disabled, HeadGestureContext.NORMAL, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(settings, HeadGestureContext.GYM, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(settings, HeadGestureContext.RINGING, true, true, false).track)
    }
    @Test fun `audible speech alone requests detection without normal or Gym mappings`() {
        val speechOnly = HeadGestureSettings(enabled = true)
        for (context in listOf(HeadGestureContext.NORMAL, HeadGestureContext.GYM)) {
            assertTrue(HeadGesturePolicy.owners(speechOnly, context, true, true, false, true).detect)
            assertFalse(HeadGesturePolicy.owners(speechOnly, context, true, true, false, false).track)
            assertFalse(HeadGesturePolicy.owners(speechOnly.copy(shakeStopsAnnouncements = false), context, true, true, false, true).track)
        }
        assertFalse(HeadGesturePolicy.owners(speechOnly, HeadGestureContext.RINGING, true, true, false, true).detect)
        assertFalse(HeadGesturePolicy.owners(speechOnly, HeadGestureContext.ACTIVE_CALL, true, true, false, true).detect)
        assertFalse(HeadGesturePolicy.owners(speechOnly.copy(enabled = false), HeadGestureContext.NORMAL, true, true, false, true).detect)
        assertFalse(HeadGesturePolicy.owners(speechOnly, HeadGestureContext.NORMAL, true, false, false, true).detect)
        assertFalse(HeadGesturePolicy.owners(speechOnly, HeadGestureContext.NORMAL, false, true, false, true).detect)
    }

    @Test fun `tracking follows the active scheme and stops when its final action is removed`() {
        val normal = HeadGestureSettings(enabled = true, shakeStopsAnnouncements = false,
            normal = mapOf(HeadGesture.NOD to StemAction.PLAY_PAUSE))
        assertTrue(HeadGesturePolicy.owners(normal, HeadGestureContext.NORMAL, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(normal.copy(normal = emptyMap()), HeadGestureContext.NORMAL, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(normal, HeadGestureContext.GYM, true, true, false).track)
        val gym = normal.copy(gym = mapOf(HeadGesture.SHAKE to StemAction.GYM_TIMER_LAP))
        assertTrue(HeadGesturePolicy.owners(gym, HeadGestureContext.GYM, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(gym.copy(gym = emptyMap()), HeadGestureContext.GYM, true, true, false).track)
        val calls = normal.copy(normal = emptyMap(), calls = mapOf(HeadGestureCallAction.ANSWER to HeadGesture.NOD))
        assertFalse(HeadGesturePolicy.owners(calls, HeadGestureContext.NORMAL, true, true, false).track)
        assertTrue(HeadGesturePolicy.owners(calls, HeadGestureContext.RINGING, true, true, false).track)
        assertFalse(HeadGesturePolicy.owners(calls.copy(calls = emptyMap()), HeadGestureContext.RINGING, true, true, false).track)
    }

}
