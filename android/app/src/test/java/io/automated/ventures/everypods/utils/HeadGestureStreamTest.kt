package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Test

class HeadGestureStreamTest {
    private val stream = HeadGestureStream()
    private fun reconcile(now: Long, wanted: Boolean = true, generation: Long = 1, alternate: Boolean = true) =
        stream.reconcile(wanted, generation, alternate, now)

    @Test fun `first owner starts stream once and releasing it stops once`() {
        assertEquals(HeadGestureStream.Command.NONE, reconcile(0, false))
        assertEquals(HeadGestureStream.Command.START, reconcile(10))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(1000))
        assertEquals(HeadGestureStream.Command.STOP, reconcile(1100, false))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(1200, false))
    }
    @Test fun `missing motion retries with bounded backoff then waits for new evidence`() {
        assertEquals(HeadGestureStream.Command.START, reconcile(0))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(1999))
        assertEquals(HeadGestureStream.Command.START, reconcile(2000))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(4999))
        assertEquals(HeadGestureStream.Command.START, reconcile(5000))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(9999))
        assertEquals(HeadGestureStream.Command.START, reconcile(10000))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(60000))
        assertEquals(3, stream.retries)
    }
    @Test fun `valid motion prevents restarting a healthy stream`() {
        reconcile(0)
        for (time in 40L..8000L step 40) {
            stream.onSample(1, time)
            assertEquals(HeadGestureStream.Command.NONE, reconcile(time))
        }
        assertEquals(0, stream.retries)
    }
    @Test fun `stream interruption after healthy motion triggers recovery`() {
        reconcile(0)
        stream.onSample(1, 800)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(2799))
        assertEquals(HeadGestureStream.Command.START, reconcile(2800))
        stream.onSample(1, 2900)
        assertEquals(0, stream.retries)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(3000))
    }
    @Test fun `new generation starts even if old tracking flag was already true`() {
        reconcile(0)
        stream.onSample(1, 100)
        assertEquals(HeadGestureStream.Command.START, reconcile(200, generation = 2))
        stream.onSample(1, 2100) // Old packet cannot make the new stream look healthy.
        assertEquals(HeadGestureStream.Command.START, reconcile(2200, generation = 2))
    }
    @Test fun `changed packet transport starts a new stream`() {
        reconcile(0)
        assertEquals(HeadGestureStream.Command.STOP, reconcile(100, alternate = false))
        assertTrue(stream.commandAlternate)
        stream.onCommandResult(HeadGestureStream.Command.STOP, true)
        assertEquals(HeadGestureStream.Command.START, reconcile(200, alternate = false))
        assertFalse(stream.commandAlternate)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(300, alternate = false))
    }
    @Test fun `disabled owner cancels pending recovery`() {
        reconcile(0)
        assertEquals(HeadGestureStream.Command.STOP, reconcile(100, false))
        stream.onCommandResult(HeadGestureStream.Command.STOP, true)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(20000, false))
    }
    @Test fun `late pre-retry packet cannot cancel recovery budget`() {
        reconcile(0)
        reconcile(2000)
        stream.onSample(1, 1900)
        assertEquals(1, stream.retries)
        assertEquals(HeadGestureStream.Command.START, reconcile(5000))
    }
    @Test fun `explicit reset allows re-registration on a reinitialized live connection`() {
        reconcile(0)
        stream.reset()
        assertEquals(HeadGestureStream.Command.START, reconcile(100))
    }
    @Test fun `ownership loss abandons requests without sending stop to the peer`() {
        reconcile(0)
        assertEquals(HeadGestureStream.Command.NONE, stream.reconcile(false, 1, true, 100, commandAllowed = false))
        assertFalse(stream.acceptingSamples)
        assertEquals(HeadGestureStream.State.IDLE, stream.state(100))
        assertEquals(HeadGestureStream.Command.START, reconcile(200))
    }
    @Test fun `failed stop retries are bounded and do not allow action samples`() {
        reconcile(0)
        assertEquals(HeadGestureStream.Command.STOP, reconcile(100, false))
        stream.onCommandResult(HeadGestureStream.Command.STOP, false)
        assertFalse(stream.acceptingSamples)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(2099, false))
        assertEquals(HeadGestureStream.Command.STOP, reconcile(2100, false))
        assertEquals(HeadGestureStream.Command.STOP, reconcile(5100, false))
        assertEquals(HeadGestureStream.Command.STOP, reconcile(10100, false))
        assertEquals(HeadGestureStream.Command.NONE, reconcile(60000, false))
        assertEquals(HeadGestureStream.State.UNAVAILABLE, stream.state(60000))
    }
    @Test fun `successful stop clears pending retries`() {
        reconcile(0); reconcile(100, false)
        stream.onCommandResult(HeadGestureStream.Command.STOP, true)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(60000, false))
        assertEquals(HeadGestureStream.State.IDLE, stream.state(60000))
    }
    @Test fun `new socket is never sent an old generation stop`() {
        reconcile(0); reconcile(100, false)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(3000, false, generation = 2))
        assertEquals(HeadGestureStream.Command.START, reconcile(3100, generation = 2))
    }
    @Test fun `new demand safely supersedes failed stop for same sensor service`() {
        reconcile(0); reconcile(100, false)
        stream.onCommandResult(HeadGestureStream.Command.STOP, false)
        assertEquals(HeadGestureStream.Command.START, reconcile(200))
        assertTrue(stream.acceptingSamples)
    }
    @Test fun `failed old service stop blocks a different packet variant`() {
        reconcile(0)
        assertEquals(HeadGestureStream.Command.STOP, reconcile(100, alternate = false))
        stream.onCommandResult(HeadGestureStream.Command.STOP, false)
        assertEquals(HeadGestureStream.Command.NONE, reconcile(200, alternate = false))
        assertTrue(stream.commandAlternate)
        assertFalse(stream.acceptingSamples)
    }
    @Test fun `requested is not receiving until valid current-generation frames arrive`() {
        reconcile(0)
        assertEquals(HeadGestureStream.State.STARTING, stream.state(10))
        stream.onSample(1, 40)
        assertEquals(HeadGestureStream.State.RECEIVING, stream.state(40))
        reconcile(2040)
        assertEquals(HeadGestureStream.State.RECOVERING, stream.state(2040))
    }

}
