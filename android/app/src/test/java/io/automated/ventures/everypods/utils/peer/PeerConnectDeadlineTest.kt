package io.automated.ventures.everypods.utils.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PeerConnectDeadlineTest {
    @Test fun `deadline closes only the pending socket and marks timeout`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pending = AtomicBoolean(true)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val attempt = PeerConnectDeadline(
            scope, 50L, { pending.get() }, { false },
            { closeCount.incrementAndGet(); closed.countDown() },
        )
        try {
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(attempt.hasTimedOut())
            assertEquals(1, closeCount.get())
        } finally {
            attempt.cancel()
            scope.cancel()
        }
    }

    @Test fun `timeout does not close a completed socket or obsolete generation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connected = AtomicBoolean(true)
        val closeCount = AtomicInteger()
        val completed = PeerConnectDeadline(scope, 50L, { true }, { connected.get() }, { closeCount.incrementAndGet() })
        val current = AtomicBoolean(true)
        val superseded = PeerConnectDeadline(scope, 300L, { current.get() }, { false }, { closeCount.incrementAndGet() })
        try {
            Thread.sleep(100)
            assertFalse(completed.hasTimedOut())
            connected.set(false)
            current.set(false)
            Thread.sleep(250)
            assertFalse(superseded.hasTimedOut())
            assertEquals(0, closeCount.get())
        } finally {
            completed.cancel()
            superseded.cancel()
            scope.cancel()
        }
    }
}
