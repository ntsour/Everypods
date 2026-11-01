package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.*
import org.junit.Test

class AudioConnectionDriverTest {
    private class Port : AudioConnectionDriver.Port {
        var nowMs = 0L
        val deadlines = mutableListOf<Pair<Long, () -> Unit>>()
        val commandDeadlines = mutableListOf<Pair<Long, () -> Unit>>()
        var enabled = true
        var fresh = true
        var requiresGrant = false
        var coordinationCount = 0
        var grantIntentId: Long? = null
        var grantCommandNumber: Int? = null
        var profile = AudioConnectionCoordinator.Profile.DISCONNECTED
        var beforeDispatch: (() -> Unit)? = null
        var insideCommand: (() -> Unit)? = null
        var evidenceCalls = 0
        val pending = mutableListOf<(AudioConnectionCoordinator.Evidence) -> Unit>()
        val commands = mutableListOf<Long>()
        val outcomes = mutableListOf<AudioConnectionCoordinator.Result>()
        var deferConnect = false
        var deferRetry = false
        var pendingRetry: (() -> Unit)? = null
        var lastRejected: (() -> Unit)? = null
        var pendingAuthorize: (() -> Boolean)? = null
        override fun evidence(headset: String, intent: AudioConnectionCoordinator.Intent?): AudioConnectionCoordinator.Evidence {
            evidenceCalls++
            if (evidenceCalls == 2) beforeDispatch?.invoke()
            return AudioConnectionCoordinator.Evidence(nowMs, enabled, fresh, false, true, false, requiresGrant,
                grantIntentId != null, profile, grantIntentId, grantCommandNumber)
        }
        override fun query(headset: String, complete: (AudioConnectionCoordinator.Evidence) -> Unit) { pending.add(complete) }
        override fun coordinate(intent: AudioConnectionCoordinator.Intent, commandNumber: Int) { coordinationCount++ }
        override fun connect(intentId: Long, headset: String, dispatch: (() -> Boolean, () -> Unit) -> Unit,
                             notDispatched: () -> Unit, rejected: () -> Unit, dispatched: () -> Unit) {
            if (deferConnect) pendingAuthorize = {
                var allowed = false
                dispatch({ true }) { allowed = true }
                allowed
            } else dispatch({ true }) {
                insideCommand?.invoke()
                commands.add(intentId)
                lastRejected = rejected
                dispatched()
            }
        }
        override fun scheduleRetry(intentId: Long, callback: () -> Unit) {
            if (deferRetry) pendingRetry = callback else callback()
        }
        override fun scheduleDeadline(delayMs: Long, callback: () -> Unit) { deadlines.add(delayMs to callback) }
        override fun scheduleCommandTimeout(delayMs: Long, callback: () -> Unit) { commandDeadlines.add(delayMs to callback) }
        override fun outcome(intentId: Long, result: AudioConnectionCoordinator.Result) { outcomes.add(result) }
    }
    private fun intent(id: Long) = AudioConnectionCoordinator.Intent(id, "headset", AudioConnectionCoordinator.Trigger.PASSIVE, 1000)

    @Test fun `new connection invalidates an old delayed release`() {
        val p = Port()
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        val release = driver.beginRelease()
        assertTrue(driver.isReleaseCurrent(release))
        driver.submit(intent(1))
        assertFalse(driver.isReleaseCurrent(release))
        var oldReleaseRan = false
        driver.dispatchIfReleaseCurrent(release) { oldReleaseRan = true }
        assertFalse(oldReleaseRan)
    }

    @Test fun `release invalidates an undispatched connection without declaring OS outcome`() {
        val p = Port().apply { deferConnect = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        val authorize = p.pendingAuthorize!!
        val release = driver.beginRelease()
        assertFalse(authorize())
        assertTrue(driver.isReleaseCurrent(release))
        assertTrue(p.commands.isEmpty())
    }

    @Test fun `policy change between assessment and dispatch prevents OS command`() {
        val p = Port().apply { beforeDispatch = { enabled = false } }
        AudioConnectionDriver(AudioConnectionCoordinator(), p).submit(intent(1))
        assertTrue(p.commands.isEmpty())
        assertEquals(listOf(AudioConnectionCoordinator.Result.CANCELLED), p.outcomes)
    }

    @Test fun `policy change while waiting for profile proxy prevents actual command`() {
        val p = Port().apply { deferConnect = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        p.enabled = false
        assertFalse(p.pendingAuthorize!!.invoke())
        assertTrue(p.commands.isEmpty())
        assertEquals(listOf(AudioConnectionCoordinator.Result.CANCELLED), p.outcomes)
    }

    @Test fun `replacement is assessed when an old delayed command is fenced`() {
        val p = Port().apply { deferConnect = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        val oldAuthorization = p.pendingAuthorize!!
        driver.submit(intent(2))
        assertFalse(oldAuthorization())
        assertNotNull(p.pendingAuthorize)
        assertTrue(p.pendingAuthorize!!.invoke())
    }

    @Test fun `delayed retry is discarded when a newer intent replaces it`() {
        val p = Port().apply { deferRetry = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        p.lastRejected!!.invoke()
        assertNotNull(p.pendingRetry)
        val oldRetry = p.pendingRetry!!
        driver.submit(intent(2))
        assertEquals(listOf(1L, 2L), p.commands)
        oldRetry()
        assertEquals(listOf(1L, 2L), p.commands)
    }

    @Test fun `late query from older intent cannot complete a replacement`() {
        val p = Port().apply { profile = AudioConnectionCoordinator.Profile.UNKNOWN }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        driver.submit(intent(2))
        assertEquals(2, p.pending.size)
        p.pending[0](p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTED))
        assertEquals(listOf(AudioConnectionCoordinator.Result.SUPERSEDED), p.outcomes)
        p.pending[1](p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTED))
        assertEquals(listOf(AudioConnectionCoordinator.Result.SUPERSEDED, AudioConnectionCoordinator.Result.CONNECTED), p.outcomes)
    }

    @Test fun `older profile query cannot overwrite a newer observation in the same intent`() {
        val p = Port().apply { profile = AudioConnectionCoordinator.Profile.UNKNOWN }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTED)
        assertEquals(2, p.pending.size)
        p.pending[1](p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTED))
        assertEquals(listOf(AudioConnectionCoordinator.Result.CONNECTED), p.outcomes)
        p.pending[0](p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.DISCONNECTED))
        assertTrue(p.commands.isEmpty())
        assertEquals(listOf(AudioConnectionCoordinator.Result.CONNECTED), p.outcomes)
    }

    @Test fun `online failure requires a new grant before a second OS command`() {
        val p = Port().apply { requiresGrant = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        assertEquals(1, p.coordinationCount)
        p.grantIntentId = 1
        p.grantCommandNumber = 1
        driver.onCoordinationChanged(1)
        assertEquals(listOf(1L), p.commands)
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        p.pending.removeAt(0)(p.evidence("headset", null))
        assertEquals(listOf(1L), p.commands)
        assertEquals(2, p.coordinationCount)
        driver.onCoordinationChanged(1)
        assertEquals(listOf(1L), p.commands)
        p.grantCommandNumber = 2
        driver.onCoordinationChanged(1)
        assertEquals(listOf(1L, 1L), p.commands)
    }

    @Test fun `offline attempt yields to coordination when peer returns before retry`() {
        val p = Port()
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        p.requiresGrant = true
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        p.pending.removeAt(0)(p.evidence("headset", null))
        assertEquals(listOf(1L), p.commands)
        assertEquals(1, p.coordinationCount)
    }

    @Test fun `failed command cannot retry after case evidence becomes stale`() {
        val p = Port()
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        p.fresh = false
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        p.pending.removeAt(0)(p.evidence("headset", null))
        assertEquals(listOf(1L), p.commands)
        assertEquals(listOf(AudioConnectionCoordinator.Result.STALE_INTENT), p.outcomes)
    }

    @Test fun `reconciled failure retries only after connecting was observed`() {
        val p = Port()
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        p.pending.removeAt(0)(p.evidence("headset", null))
        assertEquals(listOf(1L), p.commands)
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        p.pending.removeAt(0)(p.evidence("headset", null))
        assertEquals(listOf(1L, 1L), p.commands)
    }
    @Test fun `deadline reports unresolved and cannot release an accepted command`() {
        val p = Port()
        val coordinator = AudioConnectionCoordinator()
        val driver = AudioConnectionDriver(coordinator, p)
        driver.submit(intent(1))
        assertEquals(1000L, p.deadlines.single().first)
        p.nowMs = 1000L
        p.deadlines.single().second.invoke()
        assertEquals(listOf(AudioConnectionCoordinator.Result.UNRESOLVED), p.outcomes)
        assertTrue(coordinator.snapshot.unresolved)
        assertEquals(1, p.pending.size)
        driver.submit(intent(2).copy(expiresAtMs = 2000))
        assertEquals(listOf(1L), p.commands)
        assertTrue(coordinator.snapshot.unresolved)
    }

    @Test fun `coordination expires without dispatch and old timer cannot expire replacement`() {
        val p = Port().apply { requiresGrant = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        val oldDeadline = p.deadlines.single().second
        driver.submit(intent(2).copy(expiresAtMs = 2000))
        p.nowMs = 1000L
        oldDeadline()
        assertEquals(listOf(AudioConnectionCoordinator.Result.SUPERSEDED), p.outcomes)
        p.nowMs = 2000L
        p.deadlines.last().second.invoke()
        assertEquals(listOf(AudioConnectionCoordinator.Result.SUPERSEDED,
            AudioConnectionCoordinator.Result.STALE_INTENT), p.outcomes)
        assertTrue(p.commands.isEmpty())
    }

    @Test fun `early deadline callback waits for the actual monotonic expiry`() {
        val p = Port().apply { requiresGrant = true }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        p.nowMs = 500L
        p.deadlines.single().second.invoke()
        assertEquals(500L, p.deadlines.last().first)
        assertTrue(p.outcomes.isEmpty())
        p.nowMs = 1000L
        p.deadlines.last().second.invoke()
        assertEquals(listOf(AudioConnectionCoordinator.Result.STALE_INTENT), p.outcomes)
    }

    @Test fun `delayed secondary profile callback is fenced by intent and expiry`() {
        val p = Port()
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        driver.submit(intent(1))
        assertTrue(driver.isIntentCurrent(1, "HEADSET"))
        assertFalse(driver.isIntentCurrent(1, "different"))
        driver.submit(intent(2).copy(expiresAtMs = 2000))
        assertFalse(driver.isIntentCurrent(1, "headset"))
        var staleSecondaryRan = false
        driver.dispatchIfIntentCurrent(1, "headset") { staleSecondaryRan = true }
        assertFalse(staleSecondaryRan)
        assertTrue(driver.isIntentCurrent(2, "headset"))
        p.nowMs = 2000
        assertFalse(driver.isIntentCurrent(2, "headset"))
        driver.beginRelease()
        assertFalse(driver.isIntentCurrent(2, "headset"))
    }

    @Test fun `command timeout ends the attempt without allowing an overlap or late retry`() {
        val p = Port()
        val coordinator = AudioConnectionCoordinator()
        val driver = AudioConnectionDriver(coordinator, p, commandTimeoutMs = 30_000)
        driver.submit(intent(1).copy(expiresAtMs = 45_000))
        assertEquals(30_000L, p.commandDeadlines.single().first)
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        p.nowMs = 30_000
        p.commandDeadlines.single().second.invoke()
        assertEquals(listOf(AudioConnectionCoordinator.Result.UNRESOLVED), p.outcomes)
        assertTrue(coordinator.snapshot.unresolved)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.DISCONNECTED))
        assertFalse(coordinator.snapshot.unresolved)
        assertEquals(AudioConnectionCoordinator.Phase.FAILED, coordinator.snapshot.phase)
        assertEquals(listOf(1L), p.commands)
    }

    @Test fun `old command timeout cannot affect a retry with the same intent`() {
        val p = Port()
        val coordinator = AudioConnectionCoordinator()
        val driver = AudioConnectionDriver(coordinator, p, commandTimeoutMs = 30_000)
        driver.submit(intent(1).copy(expiresAtMs = 45_000))
        val firstTimeout = p.commandDeadlines.single().second
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.CONNECTING)
        p.pending.removeAt(0)(p.evidence("headset", null).copy(profile = AudioConnectionCoordinator.Profile.CONNECTING))
        p.nowMs = 21_000
        driver.onProfileBroadcast("headset", AudioConnectionCoordinator.Profile.DISCONNECTED)
        p.pending.removeAt(0)(p.evidence("headset", null))
        assertEquals(listOf(1L, 1L), p.commands)
        p.nowMs = 30_000
        firstTimeout()
        assertTrue(p.outcomes.isEmpty())
        assertEquals(2, coordinator.snapshot.commands)
        assertTrue(coordinator.snapshot.unresolved)
    }

    @Test fun `release cannot interleave with validation and OS command dispatch`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val releaseStarted = java.util.concurrent.CountDownLatch(1)
        val p = Port().apply {
            insideCommand = {
                entered.countDown()
                check(proceed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val driver = AudioConnectionDriver(AudioConnectionCoordinator(), p)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val submit = executor.submit { driver.submit(intent(1)) }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val release = executor.submit<Long> { releaseStarted.countDown(); driver.beginRelease() }
            assertTrue(releaseStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            try {
                release.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                fail("release passed the in-flight dispatch")
            } catch (_: java.util.concurrent.TimeoutException) { /* serialized as required */ }
            proceed.countDown()
            submit.get(3, java.util.concurrent.TimeUnit.SECONDS)
            release.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf(1L), p.commands)
            assertEquals(listOf(AudioConnectionCoordinator.Result.CANCELLED), p.outcomes)
        } finally {
            proceed.countDown()
            executor.shutdownNow()
        }
    }

}
