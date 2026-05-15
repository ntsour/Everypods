package io.nikos.propods.bluetooth.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Outcomes the [FakeBtSocket] can be scripted to deliver from `connect()`. */
sealed class ConnectOutcome {
    object ImmediateSuccess : ConnectOutcome()
    data class Throws(val message: String = "io error") : ConnectOutcome()
    object HangsForever : ConnectOutcome()
    /** Reports connected only after [delayMs] of virtual time. Used for the
     *  post-timeout-late-success regression test. */
    data class SucceedsAfter(val delayMs: Long) : ConnectOutcome()
}

/**
 * Test double for [BtSocket]. Per-instance — [FakeBtSocketFactory] hands out a
 * fresh one for every attempt so we can assert ordering of close() across attempts.
 */
class FakeBtSocket(
    private val outcome: ConnectOutcome,
    private val clock: ConnectionClock,
) : BtSocket {
    @Volatile var closed: Boolean = false; private set
    @Volatile var connectCalls: Int = 0; private set
    @Volatile private var connected: Boolean = false

    override suspend fun connect() {
        connectCalls++
        when (outcome) {
            ConnectOutcome.ImmediateSuccess -> {
                connected = true
            }
            is ConnectOutcome.Throws -> throw java.io.IOException(outcome.message)
            ConnectOutcome.HangsForever -> {
                // Will be cancelled by withTimeout.
                clock.delay(Long.MAX_VALUE / 4)
            }
            is ConnectOutcome.SucceedsAfter -> {
                clock.delay(outcome.delayMs)
                if (!closed) connected = true
            }
        }
    }

    override fun close() {
        closed = true
        connected = false
    }

    override val isConnected: Boolean get() = connected && !closed

    override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
    override val outputStream: OutputStream = object : OutputStream() { override fun write(b: Int) {} }
}

class FakeBtSocketFactory(
    private val outcomes: MutableList<ConnectOutcome>,
    private val clock: ConnectionClock,
) : BtSocketFactory {
    val sockets: MutableList<FakeBtSocket> = CopyOnWriteArrayList()
    override fun create(): BtSocket {
        val outcome = outcomes.removeAt(0)
        val s = FakeBtSocket(outcome, clock)
        sockets += s
        return s
    }
}

class FakeAdapterState(@Volatile var enabled: Boolean = true) : BluetoothAdapterState {
    override fun isEnabled(): Boolean = enabled
}

class TestClock(private val scope: TestScope) : ConnectionClock {
    override suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)
    override fun nowMs(): Long = scope.currentTime
}

class RecordingEventSink : ConnectionEventSink {
    val events: MutableList<ConnectionEvent> = CopyOnWriteArrayList()
    override fun emit(event: ConnectionEvent) { events += event }
    fun count(predicate: (ConnectionEvent) -> Boolean): Int = events.count(predicate)
}

/**
 * Programmable [HandshakeAckSource] that mirrors the production
 * [DeferredHandshakeAckSource] behavior — every [reset] arms a fresh deferred and
 * [signalReceived] completes it. Tests call [signalReceived] manually to mimic
 * AACPManager receiving a valid frame.
 */
class FakeHandshakeAckSource : HandshakeAckSource {
    @Volatile private var pending: CompletableDeferred<Unit> = CompletableDeferred()
    var resetCount: Int = 0; private set

    override suspend fun awaitFirstResponse() {
        pending.await()
    }

    override fun reset() {
        resetCount++
        pending = CompletableDeferred()
    }

    fun signalReceived() {
        pending.complete(Unit)
    }

    override fun abort(reason: String) {
        pending.completeExceptionally(HandshakeAbortedException(reason))
    }
}

/**
 * In-memory [AttemptLogger]. Captures the (attemptId, event, fields) triples for
 * both assertions and an on-failure dump. The on-failure dump is wired through
 * `LogDumpRule` so any failing test prints the full timeline.
 */
class RecordingAttemptLogger : AttemptLogger {
    data class Entry(val attemptId: Int, val event: String, val fields: Map<String, Any?>) {
        override fun toString(): String {
            val f = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            return "[Conn-#$attemptId] $event$f"
        }
    }
    val entries: MutableList<Entry> = CopyOnWriteArrayList()
    private val counter = AtomicInteger(0)
    override fun log(attemptId: Int, event: String, fields: Map<String, Any?>) {
        counter.incrementAndGet()
        entries += Entry(attemptId, event, fields)
    }
    fun events(): List<String> = entries.map { it.event }
    fun dump(): String = entries.joinToString("\n") { it.toString() }
    fun contains(event: String, fields: Map<String, Any?> = emptyMap()): Boolean =
        entries.any { it.event == event && fields.all { (k, v) -> it.fields[k] == v } }
    fun indexOf(event: String, fromIndex: Int = 0): Int {
        for (i in fromIndex until entries.size) if (entries[i].event == event) return i
        return -1
    }
}
