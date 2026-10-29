package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test

class LatestStatePublisherTest {
    private data class State(val title: String, val locale: String = "en", val action: String = "Connect")
    private class Rig {
        var now = 0L
        var task: (() -> Unit)? = null
        var due = 0L
        val events = mutableListOf<Pair<Long, State?>>()
        val publisher = LatestStatePublisher<State>(
            nowMs = { now }, schedule = { delay, work -> due = now + delay; task = work },
            cancelScheduled = { task = null }, publish = { events += now to it })
        fun advance(delta: Long) {
            val target = now + delta
            while (task != null && due <= target) {
                now = due
                val work = task!!; task = null; work()
            }
            now = target
        }
    }

    @Test fun `thousands of unchanged packet updates produce one notification operation`() {
        val rig = Rig()
        repeat(2_000) { rig.publisher.submit(State("AirPods 70%")); rig.advance(1) }
        assertEquals(1, rig.events.size)
    }
    @Test fun `continuous burst is rate bounded and final latest state is eventually published`() {
        val rig = Rig()
        repeat(3_000) { rig.publisher.submit(State("battery-$it")); rig.advance(1) }
        rig.advance(750)
        assertTrue(rig.events.size <= 5)
        assertEquals(State("battery-2999"), rig.events.last().second)
        rig.events.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 750) }
    }
    @Test fun `reverting to displayed content cancels a pending redundant update`() {
        val rig = Rig()
        rig.publisher.submit(State("A"))
        rig.advance(10); rig.publisher.submit(State("B"))
        rig.advance(10); rig.publisher.submit(State("A"))
        rig.advance(1_000)
        assertEquals(listOf(0L to State("A")), rig.events)
    }
    @Test fun `hide is deduplicated and latest state wins rapid hide show flaps`() {
        val rig = Rig()
        rig.publisher.submit(null)
        repeat(30) { rig.publisher.submit(null) }
        rig.publisher.submit(State("A")); rig.advance(750)
        rig.publisher.submit(null); rig.advance(10); rig.publisher.submit(State("B")); rig.advance(740)
        assertEquals(listOf(null, State("A"), State("B")), rig.events.map { it.second })
        rig.publisher.submit(null); rig.advance(750)
        assertNull(rig.events.last().second)
    }
    @Test fun `locale and notification actions are part of presentation identity`() {
        val rig = Rig()
        rig.publisher.submit(State("A"))
        rig.publisher.submit(State("A", "el")); rig.advance(750)
        rig.publisher.submit(State("A", "el", "Gym off")); rig.advance(750)
        assertEquals(3, rig.events.size)
    }
    @Test fun `service stop prevents delayed or subsequent publication`() {
        val rig = Rig()
        rig.publisher.submit(State("A")); rig.publisher.submit(State("B"))
        rig.publisher.stop(); rig.advance(5_000); rig.publisher.submit(null)
        assertEquals(1, rig.events.size)
    }
}
