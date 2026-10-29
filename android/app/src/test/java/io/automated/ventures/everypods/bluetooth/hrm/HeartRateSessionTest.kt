package io.automated.ventures.everypods.bluetooth.hrm

import org.junit.Assert.*
import org.junit.Test

class HeartRateSessionTest {
    private var now = 100L
    private var writesSucceed = true
    private val commands = mutableListOf<Pair<Int, Int>>()
    private val session = HeartRateSession({ now }, { 1_000_000 + now }) { service, interval ->
        commands += service to interval
        writesSucceed
    }
    private fun start(firmware: String = "9A348") = session.start(true, true, true, firmware)

    @Test fun `start does not invent BPM and freshness uses monotonic time`() {
        assertTrue(start())
        assertEquals(HeartRateStatus.WAITING, session.state.value.status)
        assertNull(session.state.value.bpm)
        session.sample(200)
        assertEquals(200, session.state.value.bpm)
        assertEquals(1_000_100L, session.state.value.sampleTimeMillis)
        now += 5_000
        session.tick()
        assertEquals(HeartRateStatus.STALE, session.state.value.status)
        assertNull(session.state.value.bpm)
        session.sample(80)
        assertEquals(HeartRateStatus.LIVE, session.state.value.status)
        assertEquals(80, session.state.value.bpm)
    }

    @Test fun `waiting and stale sessions stop after 15 seconds of silence`() {
        for (withSample in listOf(false, true)) {
            assertTrue(start())
            if (withSample) session.sample(70)
            now += 15_000
            session.tick()
            assertEquals(HeartRateFailure.NO_DATA, session.state.value.failure)
            assertNull(session.state.value.bpm)
            assertEquals(84 to 0, commands.last())
        }
    }

    @Test fun `stop from navigation or background is idempotent and cannot auto restart`() {
        start("8B39")
        session.sample(72)
        session.stop()
        session.stop()
        session.sample(90)
        now += 60_000
        session.tick()
        assertEquals(listOf(19 to 1_000_000, 19 to 0), commands)
        assertEquals(HeartRateSessionState(), session.state.value)
    }

    @Test fun `unsupported disconnected and other owner never send commands`() {
        assertFalse(session.start(false, true, true, "9"))
        assertEquals(HeartRateFailure.UNSUPPORTED, session.state.value.failure)
        assertFalse(session.start(true, false, true, "9"))
        assertEquals(HeartRateFailure.NO_CONNECTION, session.state.value.failure)
        assertFalse(session.start(true, true, false, "9"))
        assertEquals(HeartRateFailure.NOT_OWNER, session.state.value.failure)
        assertTrue(commands.isEmpty())
    }

    @Test fun `connection loss clears readings without writing to a replacement socket`() {
        start()
        session.sample(72)
        session.stop(HeartRateFailure.CONNECTION_LOST, connectionUsable = false)
        session.sample(75)
        assertNull(session.state.value.bpm)
        assertEquals(HeartRateFailure.CONNECTION_LOST, session.state.value.failure)
        assertEquals(listOf(84 to 1_000_000), commands)
    }

    @Test fun `queued old and out of order readings never revive or regress BPM`() {
        assertTrue(start())
        now = 5_100L
        assertFalse(session.sample(81, arrivedAtElapsedMs = 100L))
        assertNull(session.state.value.bpm)
        assertTrue(session.sample(82, arrivedAtElapsedMs = 5_099L))
        assertEquals(82, session.state.value.bpm)
        assertFalse(session.sample(83, arrivedAtElapsedMs = 5_098L))
        assertEquals(82, session.state.value.bpm)
    }

    @Test fun `write failures and device changes clear values and require explicit restart`() {
        writesSucceed = false
        assertFalse(start())
        assertEquals(HeartRateFailure.SEND_FAILED, session.state.value.failure)
        writesSucceed = true
        assertTrue(start())
        session.sample(80)
        session.stop(HeartRateFailure.DEVICE_CHANGED)
        assertEquals(HeartRateFailure.DEVICE_CHANGED, session.state.value.failure)
        assertNull(session.state.value.bpm)
        session.sample(100)
        assertNull(session.state.value.bpm)
    }

    @Test fun `command exceptions fail closed and stop still clears state`() {
        val broken = HeartRateSession({ now }, { now }) { _, interval ->
            if (interval == 0) throw SecurityException("permission revoked")
            true
        }
        assertTrue(broken.start(true, true, true, "9A348"))
        broken.sample(72)
        broken.stop()
        assertEquals(HeartRateFailure.SEND_FAILED, broken.state.value.failure)
        assertNull(broken.state.value.bpm)
    }

    @Test fun `recovery clears BPM and requires a new start before samples`() {
        assertTrue(start())
        session.sample(72)
        session.stop(HeartRateFailure.CONNECTION_LOST, connectionUsable = false)
        session.markRecovering()
        assertEquals(HeartRateStatus.RECOVERING, session.state.value.status)
        assertNull(session.state.value.bpm)
        assertFalse(session.sample(73))
        assertTrue(start())
        assertEquals(HeartRateStatus.WAITING, session.state.value.status)
    }

    @Test fun `case storage ends a live session quietly even if the final command fails`() {
        start()
        session.sample(72)
        writesSucceed = false
        session.stop(stopReason = HeartRateStopReason.AIRPODS_IN_CASE)
        assertEquals(84 to 0, commands.last())
        assertEquals(HeartRateStatus.STOPPED, session.state.value.status)
        assertEquals(HeartRateStopReason.AIRPODS_IN_CASE, session.state.value.stopReason)
        assertNull(session.state.value.failure)
        assertNull(session.state.value.bpm)
        assertFalse(session.sample(90))
        session.connectionRestored()
        assertEquals(HeartRateStopReason.AIRPODS_IN_CASE, session.state.value.stopReason)
        writesSucceed = true
        assertTrue(start())
        assertNull(session.state.value.stopReason)
        assertNull(session.state.value.failure)
    }

    @Test fun `case storage clears a recovery or expired failure without reviving old samples`() {
        for (recovering in listOf(false, true)) {
            start()
            session.stop(HeartRateFailure.CONNECTION_LOST, false)
            if (recovering) session.markRecovering()
            session.stop(connectionUsable = false, stopReason = HeartRateStopReason.AIRPODS_IN_CASE)
            now += 70_000
            session.tick()
            assertFalse(session.sample(80))
            assertNull(session.state.value.failure)
            assertEquals(HeartRateStatus.STOPPED, session.state.value.status)
        }
    }

    @Test fun `reconnect clears completed connection errors but preserves recovery and actionable failures`() {
        for (failure in listOf(HeartRateFailure.CONNECTION_LOST, HeartRateFailure.NO_DATA, HeartRateFailure.NO_CONNECTION)) {
            session.stop(failure, false)
            session.connectionRestored()
            assertEquals(HeartRateSessionState(), session.state.value)
        }
        session.markRecovering()
        session.connectionRestored()
        assertEquals(HeartRateStatus.RECOVERING, session.state.value.status)
        session.stop(HeartRateFailure.NOT_OWNER, false)
        session.connectionRestored()
        assertEquals(HeartRateFailure.NOT_OWNER, session.state.value.failure)
    }

    @Test fun `removal clears the case explanation without restarting monitoring`() {
        start()
        session.stop(stopReason = HeartRateStopReason.AIRPODS_IN_CASE)
        val commandCount = commands.size
        session.caseStorageEnded()
        assertEquals(HeartRateSessionState(), session.state.value)
        assertEquals(commandCount, commands.size)
        assertFalse(session.sample(80))
        start()
        session.caseStorageEnded()
        assertEquals(HeartRateStatus.WAITING, session.state.value.status)
    }
}
