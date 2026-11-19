package io.automated.ventures.everypods.bluetooth.connection

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BlockingConnectTest {

    @Test
    fun `stuck connect is unblocked by abort and reported as timeout`() {
        val unblock = CountDownLatch(1)
        var aborted = false
        val started = System.nanoTime()
        try {
            BlockingConnect.run(
                timeoutMs = 100,
                // Mimics BluetoothSocket.connect(): ignores interrupts, returns only once closed.
                connect = {
                    while (!unblock.await(1, TimeUnit.SECONDS)) { /* keep blocking */ }
                    throw IOException("socket closed")
                },
                abort = { aborted = true; unblock.countDown() },
            )
            fail("expected timeout")
        } catch (e: TimeoutException) {
            assertTrue(e.cause is IOException)
        }
        assertTrue(aborted)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
    }

    @Test
    fun `fast connect succeeds and never aborts`() {
        var aborted = false
        BlockingConnect.run(timeoutMs = 1_000, connect = {}, abort = { aborted = true })
        Thread.sleep(50)
        assertFalse(aborted)
    }

    @Test
    fun `connect failure before timeout is rethrown as is`() {
        try {
            BlockingConnect.run(timeoutMs = 1_000, connect = { throw IOException("refused") }, abort = {})
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("refused", e.message)
        }
    }

    @Test
    fun `gate is busy while held and reclaimed once wedged`() {
        var now = 1_000L
        val gate = InFlightGate(maxHoldMs = 60_000, now = { now })
        assertEquals(InFlightGate.Result.Acquired, gate.tryAcquire())
        val staleToken = gate.token()
        now += 10_000
        assertTrue(gate.isHeld)
        assertEquals(InFlightGate.Result.Busy(10_000), gate.tryAcquire())

        now += 55_000
        assertFalse(gate.isHeld)
        assertEquals(InFlightGate.Result.Reclaimed(65_000), gate.tryAcquire())
        val freshToken = gate.token()

        // The wedged holder finally returns: it must not free the new holder's gate.
        gate.release(staleToken)
        assertTrue(gate.isHeld)
        gate.release(freshToken)
        assertFalse(gate.isHeld)
        assertEquals(InFlightGate.Result.Acquired, gate.tryAcquire())
    }
}
