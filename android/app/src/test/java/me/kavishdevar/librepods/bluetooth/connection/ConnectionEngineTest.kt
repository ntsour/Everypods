@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.kavishdevar.librepods.bluetooth.connection

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Fully automated unit tests for [ConnectionEngine]. Every test runs under
 * [runTest] with a [kotlinx.coroutines.test.StandardTestDispatcher], so all
 * delays/timeouts are virtual — a "4-attempt × 5 s timeout × 8 s backoff" chain
 * completes in milliseconds of wall clock.
 *
 * Key timing rule:
 * - [runCurrent] runs pending tasks at the current virtual time only — use this
 *   to let the engine reach [ConnectionState.Handshaking] without tripping the 2 s
 *   handshake timeout.
 * - [advanceTimeBy] / [advanceUntilIdle] are used when the test wants a timeout to
 *   fire (timeout, backoff, watchdog).
 *
 * On failure, [LogDumpRule] prints the full attempt-id-tagged log timeline plus
 * the recorded event sink so a CI red is actionable without re-running.
 */
class ConnectionEngineTest {

    private lateinit var logger: RecordingAttemptLogger
    private lateinit var sink: RecordingEventSink

    @get:Rule val dumpRule = LogDumpRule { logger to sink }

    private data class Rig(
        val engine: ConnectionEngine,
        val factory: FakeBtSocketFactory,
        val ack: FakeHandshakeAckSource,
        val adapter: FakeAdapterState,
    )

    private fun TestScope.rig(
        outcomes: List<ConnectOutcome>,
        config: ConnectionEngine.Config = ConnectionEngine.Config(),
        adapterEnabled: Boolean = true,
    ): Rig {
        logger = RecordingAttemptLogger()
        sink = RecordingEventSink()
        val clock = TestClock(this)
        val factory = FakeBtSocketFactory(outcomes.toMutableList(), clock)
        val ack = FakeHandshakeAckSource()
        val adapter = FakeAdapterState(adapterEnabled)
        val engine = ConnectionEngine(
            socketFactory = factory,
            handshakeAckSource = ack,
            clock = clock,
            eventSink = sink,
            logger = logger,
            adapterState = adapter,
            scope = backgroundScope,
            config = config,
        )
        return Rig(engine, factory, ack, adapter)
    }

    // -------------------- 1: happy path --------------------

    @Test fun `connectsOnFirstTry_emitsConnectedOnlyAfterHandshakeAck`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val states = mutableListOf<ConnectionState>()
        val collector = launch { r.engine.state.toList(states) }

        val job = async { r.engine.connect("test") }
        runCurrent()
        assertTrue("expected Handshaking, got ${r.engine.state.value}",
            r.engine.state.value is ConnectionState.Handshaking)
        assertEquals("no Connected event before ack", 0,
            sink.count { it is ConnectionEvent.L2capConnected })

        r.ack.signalReceived()
        runCurrent()
        assertEquals(ConnectResult.Connected, job.await())

        assertTrue(r.engine.state.value is ConnectionState.Connected)
        assertEquals(1, sink.count { it is ConnectionEvent.L2capConnected })
        val ackIdx = logger.indexOf("handshake_ack_received")
        val connectedLogIdx = logger.entries.indexOfFirst {
            it.event == "state_change" && it.fields["to"] == "Connected"
        }
        assertTrue("Connected log must come after handshake_ack_received",
            ackIdx in 0 until connectedLogIdx)
        collector.cancel()
        r.engine.onDisconnect("test_teardown")
        advanceUntilIdle()
    }

    // -------------------- 2: handshake never acks --------------------

    @Test fun `socketOpensButHandshakeNeverAcks_failsAndClosesSocket`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val job = async { r.engine.connect("test") }
        // advanceUntilIdle WILL trip the 2 s handshake timeout — that's the point.
        advanceUntilIdle()
        assertEquals(ConnectResult.Failed, job.await())
        val s = r.engine.state.value
        assertTrue("expected Failed, got $s", s is ConnectionState.Failed)
        assertEquals(FailureReason.HandshakeTimeout, (s as ConnectionState.Failed).reason)
        assertTrue("socket must be closed after handshake timeout",
            r.factory.sockets.single().closed)
        assertEquals(0, sink.count { it is ConnectionEvent.L2capConnected })
    }

    // -------------------- 3: retry succeeds on second attempt --------------------

    @Test fun `firstAttemptTimesOut_secondSucceeds_singleConnectedEvent`() = runTest {
        val r = rig(listOf(ConnectOutcome.HangsForever, ConnectOutcome.ImmediateSuccess))
        val job = async { r.engine.connect("test") }
        // First attempt: 5 s connect timeout, then 1 s backoff.
        advanceTimeBy(5_000); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        // Now in second attempt's handshake — *don't* let virtual time advance further.
        assertTrue("expected Handshaking on second attempt, got ${r.engine.state.value}",
            r.engine.state.value is ConnectionState.Handshaking)
        r.ack.signalReceived()
        runCurrent()
        assertEquals(ConnectResult.Connected, job.await())
        assertEquals(1, sink.count { it is ConnectionEvent.L2capConnected })
        assertEquals(2, r.factory.sockets.size)
        assertTrue("first socket must be closed", r.factory.sockets[0].closed)
        assertFalse("second socket still open", r.factory.sockets[1].closed)
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 4: all attempts time out --------------------

    @Test fun `allAttemptsTimeOut_terminalFailedNoZombie`() = runTest {
        val r = rig(List(4) { ConnectOutcome.HangsForever })
        val job = async { r.engine.connect("test") }
        advanceUntilIdle()
        assertEquals(ConnectResult.Failed, job.await())
        val s = r.engine.state.value
        assertTrue("expected Failed, got $s", s is ConnectionState.Failed)
        assertEquals(FailureReason.Timeout, (s as ConnectionState.Failed).reason)
        assertEquals(4, r.factory.sockets.size)
        assertTrue("all four sockets closed", r.factory.sockets.all { it.closed })
        assertEquals("single failure event at the end", 1,
            sink.count { it is ConnectionEvent.Failed })
        assertEquals(0, sink.count { it is ConnectionEvent.L2capConnected })
    }

    // -------------------- 5: IOException retries --------------------

    @Test fun `connectThrowsIOException_retriesWithBackoff`() = runTest {
        val r = rig(listOf(
            ConnectOutcome.Throws("io1"),
            ConnectOutcome.Throws("io2"),
            ConnectOutcome.ImmediateSuccess,
        ))
        val job = async { r.engine.connect("test") }
        // Two failures (instant) + two backoffs (1 s, 2 s) → 3 s of virtual time.
        advanceTimeBy(3_000); runCurrent()
        assertTrue("state=${r.engine.state.value}",
            r.engine.state.value is ConnectionState.Handshaking)
        r.ack.signalReceived(); runCurrent()
        assertEquals(ConnectResult.Connected, job.await())
        val backoffs = logger.entries.filter { it.event == "retry_scheduled" }
            .map { it.fields["in_ms"] as Long }
        assertEquals(listOf(1_000L, 2_000L), backoffs)
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 6: symptom-3 regression --------------------

    @Test fun `postTimeoutSocketLateSuccess_isStillTornDown`() = runTest {
        val r = rig(
            listOf(ConnectOutcome.SucceedsAfter(6_000)),
            config = ConnectionEngine.Config(connectTimeoutMs = 5_000, maxAttempts = 1),
        )
        val job = async { r.engine.connect("test") }
        advanceUntilIdle()
        assertEquals(ConnectResult.Failed, job.await())
        assertEquals("never emit Connected after timeout", 0,
            sink.count { it is ConnectionEvent.L2capConnected })
        assertTrue("socket must be closed", r.factory.sockets[0].closed)
        val timeoutIdx = logger.entries.indexOfFirst {
            it.event == "socket_connect_end" && it.fields["result"] == "timeout"
        }
        val closeIdx = logger.entries.indexOfFirst {
            it.event == "socket_close" && it.fields["reason"] == "post_timeout_late_success"
        }
        assertTrue("timeout must be logged", timeoutIdx >= 0)
        assertTrue("close after timeout must be logged", closeIdx > timeoutIdx)
    }

    // -------------------- 7: concurrent triggers collapse --------------------

    @Test fun `concurrentTriggers_collapseToSingleAttempt`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val deferreds = List(10) { idx -> async { r.engine.connect("t-$idx") } }
        runCurrent()
        r.ack.signalReceived()
        runCurrent()
        val results = deferreds.awaitAll()
        val connectedCount = results.count { it == ConnectResult.Connected }
        val alreadyCount = results.count { it == ConnectResult.AlreadyConnecting }
        assertEquals(1, connectedCount)
        assertEquals(9, alreadyCount)
        assertEquals("only one socket ever created", 1, r.factory.sockets.size)
        assertEquals(1, r.factory.sockets.single().connectCalls)
        assertEquals(1, sink.count { it is ConnectionEvent.L2capConnected })
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 8: disconnect during handshake --------------------

    @Test fun `disconnectDuringHandshake_endsIdleNotConnected`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val job = async { r.engine.connect("test") }
        runCurrent()
        assertTrue("expected Handshaking, got ${r.engine.state.value}",
            r.engine.state.value is ConnectionState.Handshaking)
        r.engine.onDisconnect("test_external")
        runCurrent()
        val result = job.await()
        assertEquals(ConnectResult.Failed, result)
        assertEquals(ConnectionState.Idle, r.engine.state.value)
        assertTrue("socket must be closed", r.factory.sockets.single().closed)
        assertEquals(0, sink.count { it is ConnectionEvent.L2capConnected })
        assertEquals(1, sink.count { it is ConnectionEvent.Disconnected })
    }

    // -------------------- 9: watchdog --------------------

    @Test fun `readLoopWatchdog_forcesDisconnectAfterSilence`() = runTest {
        val r = rig(
            listOf(ConnectOutcome.ImmediateSuccess),
            config = ConnectionEngine.Config(readWatchdogMs = 45_000, watchdogCheckIntervalMs = 15_000),
        )
        val job = async { r.engine.connect("test") }
        runCurrent()
        r.ack.signalReceived()
        runCurrent()
        assertTrue(r.engine.state.value is ConnectionState.Connected)
        assertEquals(ConnectResult.Connected, job.await())
        advanceTimeBy(50_000); runCurrent()
        assertEquals(ConnectionState.Idle, r.engine.state.value)
        assertTrue(r.factory.sockets.single().closed)
        assertTrue(sink.events.any {
            it is ConnectionEvent.Disconnected && it.reason == "watchdog_silence"
        })
    }

    // -------------------- 10: ping prevents watchdog --------------------

    @Test fun `bytesReceivedKeepsConnectionAlive`() = runTest {
        val r = rig(
            listOf(ConnectOutcome.ImmediateSuccess),
            config = ConnectionEngine.Config(readWatchdogMs = 45_000, watchdogCheckIntervalMs = 15_000),
        )
        val job = async { r.engine.connect("test") }
        runCurrent()
        r.ack.signalReceived(); runCurrent()
        job.await()
        repeat(30) {
            advanceTimeBy(10_000)
            r.engine.onBytesReceived()
            runCurrent()
        }
        assertTrue("still connected after sustained activity, got ${r.engine.state.value}",
            r.engine.state.value is ConnectionState.Connected)
        assertEquals(0, sink.count { it is ConnectionEvent.Disconnected })
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 11: peer-close --------------------

    @Test fun `peerClosesSocket_emitsDisconnectedExactlyOnce`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val job = async { r.engine.connect("test") }
        runCurrent()
        r.ack.signalReceived(); runCurrent()
        job.await()
        r.engine.onDisconnect("peer_closed")
        r.engine.onDisconnect("peer_closed_dup") // idempotency
        runCurrent()
        assertEquals(1, sink.count { it is ConnectionEvent.Disconnected })
        assertTrue(r.factory.sockets.single().closed)
        assertEquals(ConnectionState.Idle, r.engine.state.value)
    }

    // -------------------- 12: disconnect after failed connect --------------------

    @Test fun `disconnectAfterFailedConnect_doesNotCrash`() = runTest {
        val r = rig(
            listOf(ConnectOutcome.Throws("boom")),
            config = ConnectionEngine.Config(maxAttempts = 1),
        )
        val job = async { r.engine.connect("test") }
        runCurrent()
        assertEquals(ConnectResult.Failed, job.await())
        r.engine.onDisconnect("late")
        runCurrent()
        assertTrue(r.engine.state.value is ConnectionState.Failed)
        assertEquals(0, sink.count { it is ConnectionEvent.Disconnected })
    }

    // -------------------- 13: mutex released after failure --------------------

    @Test fun `mutexReleasedAfterFailure_allowsNextAttempt`() = runTest {
        val r = rig(
            listOf(ConnectOutcome.Throws("first"), ConnectOutcome.ImmediateSuccess),
            config = ConnectionEngine.Config(maxAttempts = 1),
        )
        val first = async { r.engine.connect("first") }
        runCurrent()
        assertEquals(ConnectResult.Failed, first.await())
        assertTrue(r.engine.state.value is ConnectionState.Failed)

        val second = async { r.engine.connect("second") }
        runCurrent()
        assertTrue("second attempt should reach Handshaking; got ${r.engine.state.value}",
            r.engine.state.value is ConnectionState.Handshaking)
        r.ack.signalReceived(); runCurrent()
        assertEquals(ConnectResult.Connected, second.await())
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 14: bluetooth off mid-attempt --------------------

    @Test fun `bluetoothOffMidAttempt_failsFastNoRetry`() = runTest {
        // Provide enough outcomes so the engine never runs out before noticing
        // the adapter went off. We expect the SECOND attempt's pre-loop adapter
        // check to fire BluetoothOff.
        val r = rig(
            outcomes = List(3) { ConnectOutcome.Throws("io") },
            config = ConnectionEngine.Config(maxAttempts = 3),
        )
        val job = async { r.engine.connect("test") }
        runCurrent()
        // First attempt fails immediately; engine has scheduled a 1 s backoff.
        r.adapter.enabled = false
        advanceTimeBy(1_000); runCurrent()
        assertEquals(ConnectResult.Failed, job.await())
        val s = r.engine.state.value
        assertTrue("expected Failed(BluetoothOff), got $s", s is ConnectionState.Failed)
        assertEquals(FailureReason.BluetoothOff, (s as ConnectionState.Failed).reason)
        // Only one socket should have been created — second attempt bailed before factory call.
        assertEquals(1, r.factory.sockets.size)
    }

    // -------------------- 15: monotonic state flow --------------------

    @Test fun `stateFlow_emitsConnectedAtMostOncePerConnect`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val states = mutableListOf<ConnectionState>()
        val collector = launch { r.engine.state.toList(states) }
        val job = async { r.engine.connect("test") }
        runCurrent()
        r.ack.signalReceived(); runCurrent()
        job.await()
        val connectedCount = states.count { it is ConnectionState.Connected }
        assertEquals(1, connectedCount)
        for (i in 1 until states.size) assertFalse(
            "dup at $i: ${states[i]}", states[i] == states[i - 1])
        collector.cancel()
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 16: log timeline (happy path) --------------------

    @Test fun `logTimeline_happyPath_containsExpectedEventsInOrder`() = runTest {
        val r = rig(listOf(ConnectOutcome.ImmediateSuccess))
        val job = async { r.engine.connect("test") }
        runCurrent()
        r.ack.signalReceived(); runCurrent()
        job.await()
        val expected = listOf(
            "trigger_received",
            "socket_connect_start",
            "socket_connect_end",
            "handshake_sent",
            "handshake_ack_received",
        )
        var idx = -1
        for (e in expected) {
            val next = logger.indexOf(e, idx + 1)
            assertTrue("missing event $e after index $idx in:\n${logger.dump()}",
                next > idx)
            idx = next
        }
        r.engine.onDisconnect("test_teardown"); advanceUntilIdle()
    }

    // -------------------- 17: log timeline (symptom-3 regression) --------------------

    @Test fun `logTimeline_postTimeoutLateSuccess_logsTimeoutThenClose`() = runTest {
        val r = rig(
            listOf(ConnectOutcome.SucceedsAfter(6_000)),
            config = ConnectionEngine.Config(connectTimeoutMs = 5_000, maxAttempts = 1),
        )
        val job = async { r.engine.connect("test") }
        advanceUntilIdle()
        job.await()
        val timeoutIdx = logger.entries.indexOfFirst {
            it.event == "socket_connect_end" && it.fields["result"] == "timeout"
        }
        val closeIdx = logger.entries.indexOfFirst {
            it.event == "socket_close" && it.fields["reason"] == "post_timeout_late_success"
        }
        assertTrue("timeout must be logged", timeoutIdx >= 0)
        assertTrue("close after timeout must be logged", closeIdx > timeoutIdx)
    }
}

/**
 * On test failure, dump the engine log + event sink so a CI red is actionable
 * without re-running locally.
 */
class LogDumpRule(
    private val supplier: () -> Pair<RecordingAttemptLogger, RecordingEventSink>,
) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val (logger, sink) = supplier()
        println("===== ${description.methodName} FAILED =====")
        println("---- attempt log ----")
        println(logger.dump())
        println("---- event sink ----")
        sink.events.forEach { println(" - $it") }
        println("=================================")
    }
}
