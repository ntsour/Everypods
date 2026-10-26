package io.automated.ventures.everypods.data

import org.junit.Assert.*
import org.junit.Test

class HeadGestureRoutingTest {
    @Test fun `every general action uses normal and gym mapping`() {
        StemAction.entries.forEach { action ->
            val s = HeadGestureSettings(enabled = true, normal = mapOf(HeadGesture.NOD to action), gym = mapOf(HeadGesture.SHAKE to action))
            assertEquals(HeadGestureDispatch.Action(action), s.resolve(HeadGesture.NOD, HeadGestureContext.NORMAL))
            assertEquals(HeadGestureDispatch.Action(action), s.resolve(HeadGesture.SHAKE, HeadGestureContext.GYM))
            assertEquals(HeadGestureDispatch.None, s.resolve(HeadGesture.NOD, HeadGestureContext.GYM))
        }
    }
    @Test fun `unassigned calls never fall back and ringing transitions to active mapping`() {
        val s = HeadGestureSettings(enabled = true, normal = mapOf(HeadGesture.NOD to StemAction.PLAY_PAUSE),
            calls = mapOf(HeadGestureCallAction.ANSWER to HeadGesture.NOD, HeadGestureCallAction.HANG_UP to HeadGesture.SHAKE))
        assertEquals(HeadGestureDispatch.Call(HeadGestureCallAction.ANSWER), s.resolve(HeadGesture.NOD, HeadGestureContext.RINGING))
        assertEquals(HeadGestureDispatch.None, s.resolve(HeadGesture.NOD, HeadGestureContext.ACTIVE_CALL))
        assertEquals(HeadGestureDispatch.None, s.resolve(HeadGesture.SHAKE, HeadGestureContext.RINGING))
        assertEquals(HeadGestureDispatch.Call(HeadGestureCallAction.HANG_UP), s.resolve(HeadGesture.SHAKE, HeadGestureContext.ACTIVE_CALL))
    }
    @Test fun `disabled master suppresses actions but explicit test is safe`() {
        val s = HeadGestureSettings(normal = mapOf(HeadGesture.NOD to StemAction.PLAY_PAUSE))
        HeadGestureContext.entries.filter { it != HeadGestureContext.TEST }.forEach { assertEquals(HeadGestureDispatch.None, s.resolve(HeadGesture.NOD, it)) }
        assertEquals(HeadGestureDispatch.Test, s.resolve(HeadGesture.NOD, HeadGestureContext.TEST))
    }
    @Test fun `spoken announcements override shake in normal and Gym only while audible`() {
        for (context in listOf(HeadGestureContext.NORMAL, HeadGestureContext.GYM)) {
            val s = HeadGestureSettings(enabled = true,
                normal = mapOf(HeadGesture.SHAKE to StemAction.NEXT_TRACK),
                gym = mapOf(HeadGesture.SHAKE to StemAction.GYM_TIMER_LAP))
            assertEquals(HeadGestureDispatch.StopAnnouncement, s.resolve(HeadGesture.SHAKE, context, true))
            assertEquals(HeadGestureDispatch.Action(if (context == HeadGestureContext.GYM) StemAction.GYM_TIMER_LAP else StemAction.NEXT_TRACK),
                s.resolve(HeadGesture.SHAKE, context, false))
        }
    }
    @Test fun `call assignments win over announcements and unassigned calls never fall through`() {
        val s = HeadGestureSettings(enabled = true,
            normal = mapOf(HeadGesture.SHAKE to StemAction.NEXT_TRACK),
            calls = mapOf(HeadGestureCallAction.DECLINE to HeadGesture.SHAKE))
        assertEquals(HeadGestureDispatch.Call(HeadGestureCallAction.DECLINE), s.resolve(HeadGesture.SHAKE, HeadGestureContext.RINGING, true))
        assertEquals(HeadGestureDispatch.None, s.resolve(HeadGesture.SHAKE, HeadGestureContext.ACTIVE_CALL, true))
    }
    @Test fun `announcement toggle off preserves assigned actions and master off suppresses cancellation`() {
        val s = HeadGestureSettings(enabled = true, shakeStopsAnnouncements = false,
            normal = mapOf(HeadGesture.SHAKE to StemAction.PLAY_PAUSE))
        assertEquals(HeadGestureDispatch.Action(StemAction.PLAY_PAUSE), s.resolve(HeadGesture.SHAKE, HeadGestureContext.NORMAL, true))
        assertEquals(HeadGestureDispatch.None, s.copy(enabled = false, shakeStopsAnnouncements = true)
            .resolve(HeadGesture.SHAKE, HeadGestureContext.NORMAL, true))
    }
    @Test fun `safe test never cancels announcements and nod keeps its assigned action`() {
        val s = HeadGestureSettings(enabled = true, normal = mapOf(HeadGesture.NOD to StemAction.PLAY_PAUSE))
        assertEquals(HeadGestureDispatch.Test, s.resolve(HeadGesture.SHAKE, HeadGestureContext.TEST, true))
        assertEquals(HeadGestureDispatch.Action(StemAction.PLAY_PAUSE), s.resolve(HeadGesture.NOD, HeadGestureContext.NORMAL, true))
        assertEquals(HeadGestureDispatch.StopAnnouncement, s.resolve(HeadGesture.SHAKE, HeadGestureContext.NORMAL, true))
    }

}
