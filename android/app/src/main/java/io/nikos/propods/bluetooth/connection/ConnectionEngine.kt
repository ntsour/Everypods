package io.nikos.propods.bluetooth.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives one logical AirPods L2CAP connection. Owns retry, backoff, the handshake
 * gate, and the read-loop watchdog. UI consumers observe [state]; broadcast-style
 * consumers receive [ConnectionEvent]s via the injected [ConnectionEventSink].
 *
 * Single in-flight attempt is guaranteed by [inFlight]. Concurrent callers receive
 * [ConnectResult.AlreadyConnecting].
 */
class ConnectionEngine(
    private val socketFactory: BtSocketFactory,
    private val handshakeAckSource: HandshakeAckSource,
    private val clock: ConnectionClock,
    private val eventSink: ConnectionEventSink,
    private val logger: AttemptLogger,
    private val adapterState: BluetoothAdapterState,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
) {
    data class Config(
        val connectTimeoutMs: Long = 5_000,
        val handshakeTimeoutMs: Long = 2_000,
        val maxAttempts: Int = 4,
        val backoffSchedule: List<Long> = listOf(1_000, 2_000, 4_000, 8_000),
        val readWatchdogMs: Long = 45_000,
        val watchdogCheckIntervalMs: Long = 15_000,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val inFlight = AtomicBoolean(false)
    private val attemptCounter = AtomicInteger(0)
    private val mutex = Mutex()

    @Volatile private var currentSocket: BtSocket? = null
    @Volatile private var currentAttemptId: Int = -1
    @Volatile private var lastBytesAtMs: Long = 0
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var disconnectRequested: Boolean = false

    /**
     * Trigger a connection attempt. Returns when the attempt has fully resolved
     * (either reached [ConnectionState.Connected] with handshake ack, or failed
     * after retries). Concurrent callers get [ConnectResult.AlreadyConnecting].
     */
    suspend fun connect(trigger: String): ConnectResult {
        if (_state.value is ConnectionState.Connected) {
            logger.log(-1, "connect_skipped", mapOf("trigger" to trigger, "reason" to "already_connected"))
            return ConnectResult.AlreadyConnecting
        }
        if (!inFlight.compareAndSet(false, true)) {
            logger.log(-1, "connect_skipped", mapOf("trigger" to trigger, "reason" to "in_flight"))
            return ConnectResult.AlreadyConnecting
        }
        val attemptId = attemptCounter.incrementAndGet()
        currentAttemptId = attemptId
        connectJob = kotlin.coroutines.coroutineContext[Job]
        disconnectRequested = false
        logger.log(attemptId, "trigger_received", mapOf("source" to trigger))
        return try {
            runConnectLoop(attemptId)
        } catch (_: CancellationException) {
            // Cancelled by onDisconnect; onDisconnect runs the cleanup itself.
            ConnectResult.Failed
        } finally {
            connectJob = null
            inFlight.set(false)
        }
    }

    private suspend fun runConnectLoop(attemptId: Int): ConnectResult {
        var lastReason: FailureReason = FailureReason.MaxRetriesExhausted
        for (attemptNumber in 1..config.maxAttempts) {
            if (!adapterState.isEnabled()) {
                logger.log(attemptId, "bluetooth_off_bail")
                transitionTo(ConnectionState.Failed(FailureReason.BluetoothOff))
                eventSink.emit(ConnectionEvent.Failed(FailureReason.BluetoothOff))
                return ConnectResult.Failed
            }
            transitionTo(ConnectionState.Connecting(attemptId, attemptNumber))

            val socket = socketFactory.create()
            currentSocket = socket
            handshakeAckSource.reset()

            val attemptResult = tryOpenSocket(attemptId, socket)
            when (attemptResult) {
                is OpenResult.Success -> {
                    val handshakeOk = runHandshake(attemptId, socket)
                    if (disconnectRequested) {
                        // onDisconnect already ran cleanup and emitted Disconnected.
                        // Just unwind silently.
                        return ConnectResult.Failed
                    }
                    if (handshakeOk) {
                        transitionTo(ConnectionState.Connected(attemptId))
                        eventSink.emit(ConnectionEvent.L2capConnected(attemptId))
                        startWatchdog(attemptId)
                        return ConnectResult.Connected
                    }
                    // handshake failed → close and fail terminally (no retry — peer is there but broken)
                    lastReason = FailureReason.HandshakeTimeout
                    safeClose(socket, "handshake_timeout", attemptId)
                    currentSocket = null
                    transitionTo(ConnectionState.Failed(lastReason))
                    eventSink.emit(ConnectionEvent.Failed(lastReason))
                    return ConnectResult.Failed
                }
                is OpenResult.Timeout -> {
                    safeClose(socket, "post_timeout_late_success", attemptId)
                    currentSocket = null
                    lastReason = FailureReason.Timeout
                }
                is OpenResult.IoError -> {
                    safeClose(socket, "io_error", attemptId)
                    currentSocket = null
                    lastReason = FailureReason.IoError(attemptResult.message)
                }
                is OpenResult.BluetoothOff -> {
                    safeClose(socket, "bluetooth_off", attemptId)
                    currentSocket = null
                    transitionTo(ConnectionState.Failed(FailureReason.BluetoothOff))
                    eventSink.emit(ConnectionEvent.Failed(FailureReason.BluetoothOff))
                    return ConnectResult.Failed
                }
            }

            if (attemptNumber < config.maxAttempts) {
                val backoff = config.backoffSchedule.getOrElse(attemptNumber - 1) {
                    config.backoffSchedule.lastOrNull() ?: 1_000L
                }
                logger.log(
                    attemptId, "retry_scheduled",
                    mapOf("in_ms" to backoff, "attempt" to attemptNumber, "of" to config.maxAttempts)
                )
                if (!adapterState.isEnabled()) {
                    logger.log(attemptId, "bluetooth_off_bail")
                    transitionTo(ConnectionState.Failed(FailureReason.BluetoothOff))
                    eventSink.emit(ConnectionEvent.Failed(FailureReason.BluetoothOff))
                    return ConnectResult.Failed
                }
                clock.delay(backoff)
            }
        }
        transitionTo(ConnectionState.Failed(lastReason))
        eventSink.emit(ConnectionEvent.Failed(lastReason))
        return ConnectResult.Failed
    }

    private sealed class OpenResult {
        object Success : OpenResult()
        object Timeout : OpenResult()
        data class IoError(val message: String) : OpenResult()
        object BluetoothOff : OpenResult()
    }

    private suspend fun tryOpenSocket(attemptId: Int, socket: BtSocket): OpenResult {
        logger.log(attemptId, "socket_connect_start")
        val start = clock.nowMs()
        return try {
            withTimeout(config.connectTimeoutMs) {
                socket.connect()
            }
            val elapsed = clock.nowMs() - start
            if (!adapterState.isEnabled()) {
                logger.log(attemptId, "socket_connect_end", mapOf("result" to "bluetooth_off", "elapsed_ms" to elapsed))
                OpenResult.BluetoothOff
            } else if (!socket.isConnected) {
                logger.log(attemptId, "socket_connect_end", mapOf("result" to "not_connected", "elapsed_ms" to elapsed))
                OpenResult.IoError("Socket reported not connected after connect()")
            } else {
                logger.log(attemptId, "socket_connect_end", mapOf("result" to "success", "elapsed_ms" to elapsed))
                OpenResult.Success
            }
        } catch (_: TimeoutCancellationException) {
            val elapsed = clock.nowMs() - start
            logger.log(attemptId, "socket_connect_end", mapOf("result" to "timeout", "elapsed_ms" to elapsed))
            OpenResult.Timeout
        } catch (e: Exception) {
            val elapsed = clock.nowMs() - start
            logger.log(
                attemptId, "socket_connect_end",
                mapOf("result" to "io_error", "elapsed_ms" to elapsed, "message" to (e.message ?: ""))
            )
            OpenResult.IoError(e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun runHandshake(attemptId: Int, @Suppress("unused") socket: BtSocket): Boolean {
        transitionTo(ConnectionState.Handshaking(attemptId))
        logger.log(attemptId, "handshake_sent")
        return try {
            withTimeout(config.handshakeTimeoutMs) {
                handshakeAckSource.awaitFirstResponse()
            }
            logger.log(attemptId, "handshake_ack_received")
            true
        } catch (_: TimeoutCancellationException) {
            logger.log(attemptId, "handshake_ack_timeout")
            false
        } catch (e: HandshakeAbortedException) {
            logger.log(attemptId, "handshake_aborted", mapOf("reason" to (e.message ?: "")))
            false
        } catch (e: Exception) {
            logger.log(attemptId, "handshake_ack_error", mapOf("message" to (e.message ?: "")))
            false
        }
    }

    private fun safeClose(socket: BtSocket, reason: String, attemptId: Int) {
        logger.log(attemptId, "socket_close", mapOf("reason" to reason))
        try { socket.close() } catch (_: Exception) {}
    }

    private fun transitionTo(next: ConnectionState) {
        val prev = _state.value
        if (prev::class == next::class && prev == next) return
        _state.value = next
        logger.log(
            currentAttemptId, "state_change",
            mapOf("from" to prev::class.simpleName, "to" to next::class.simpleName)
        )
    }

    /** Consumers (the read loop) must call this each time they read non-empty bytes. */
    fun onBytesReceived() {
        lastBytesAtMs = clock.nowMs()
    }

    /**
     * External "the link is dead" signal — peer closed, read returned -1, ACL
     * disconnected broadcast, watchdog firing. Idempotent.
     */
    fun onDisconnect(reason: String) {
        scope.launch {
            mutex.withLock {
                val s = _state.value
                if (s is ConnectionState.Idle || s is ConnectionState.Failed) {
                    return@withLock
                }
                transitionTo(ConnectionState.Disconnecting)
                disconnectRequested = true
                // Wake any pending handshake await so the engine unwinds promptly,
                // without cancelling the enclosing coroutine.
                handshakeAckSource.abort(reason)
                watchdogJob?.cancel(); watchdogJob = null
                currentSocket?.let { safeClose(it, reason, currentAttemptId) }
                currentSocket = null
                eventSink.emit(ConnectionEvent.Disconnected(reason))
                transitionTo(ConnectionState.Idle)
            }
        }
    }

    private fun startWatchdog(attemptId: Int) {
        watchdogJob?.cancel()
        lastBytesAtMs = clock.nowMs()
        watchdogJob = scope.launch {
            while (isActive) {
                clock.delay(config.watchdogCheckIntervalMs)
                val silence = clock.nowMs() - lastBytesAtMs
                if (silence >= config.readWatchdogMs) {
                    logger.log(attemptId, "read_loop_watchdog_fire", mapOf("silence_ms" to silence))
                    onDisconnect("watchdog_silence")
                    return@launch
                }
            }
        }
    }

    // -- Test-friendly accessors --
    internal fun currentSocketForTest(): BtSocket? = currentSocket
}
