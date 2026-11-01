package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.*
import org.junit.Test

class EpisodeWakeGuardTest {
    @Test fun `old outcome cannot release replacement episode`() {
        val released = mutableListOf<Int>()
        var acquired = 0
        val guard = EpisodeWakeGuard { timeout ->
            assertEquals(45_000L, timeout)
            val id = ++acquired
            EpisodeWakeGuard.Lease { released.add(id) }
        }
        assertTrue(guard.acquire(1, 45_000))
        assertTrue(guard.acquire(1, 45_000))
        assertEquals(1, acquired)
        assertTrue(guard.acquire(2, 45_000))
        assertEquals(listOf(1), released)
        guard.release(1)
        assertEquals(listOf(1), released)
        guard.release(2)
        guard.close()
        assertEquals(listOf(1, 2), released)
    }

    @Test fun `failed acquisition and release exceptions are contained`() {
        var calls = 0
        val guard = EpisodeWakeGuard {
            if (++calls == 1) error("unavailable")
            EpisodeWakeGuard.Lease { error("already expired") }
        }
        assertFalse(guard.acquire(1, 1))
        assertTrue(guard.acquire(2, 1))
        guard.close()
        assertTrue(guard.acquire(3, 1))
    }
    @Test fun `skipped work releases immediately but pending or active work retains the lease`() {
        val released = mutableListOf<Long>()
        var current = 0L
        val guard = EpisodeWakeGuard { EpisodeWakeGuard.Lease { released.add(current) } }
        current = 1
        guard.acquire(1, 45_000)
        guard.releaseIfIdle(1, callbackPending = true, coordinatorActive = false)
        guard.releaseIfIdle(1, callbackPending = false, coordinatorActive = true)
        assertTrue(released.isEmpty())
        guard.releaseIfIdle(1, callbackPending = false, coordinatorActive = false)
        assertEquals(listOf(1L), released)
        current = 2
        guard.acquire(2, 45_000)
        guard.releaseIfIdle(1, callbackPending = false, coordinatorActive = false)
        assertEquals(listOf(1L), released)
        guard.releaseIfIdle(2, callbackPending = false, coordinatorActive = false)
        assertEquals(listOf(1L, 2L), released)
    }

}
