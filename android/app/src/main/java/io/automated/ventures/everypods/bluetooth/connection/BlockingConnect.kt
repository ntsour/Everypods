package io.automated.ventures.everypods.bluetooth.connection

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a blocking socket connect with a timeout that actually unblocks it.
 *
 * `BluetoothSocket.connect()` ignores coroutine cancellation and thread interrupts, so
 * `withTimeout { connect() }` never fires while connect() is stuck. Only `close()` from
 * another thread makes connect() return (with an IOException). Some stacks (seen on
 * HyperOS) leave connect() blocked indefinitely, which previously wedged the caller.
 */
object BlockingConnect {
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "socket-connect-watchdog").apply { isDaemon = true }
    }

    /** Throws [TimeoutException] when [abort] had to be used, or rethrows what [connect] threw. */
    fun run(
        timeoutMs: Long,
        connect: () -> Unit,
        abort: () -> Unit,
        scheduler: ScheduledExecutorService = watchdog,
    ) {
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val task = scheduler.schedule({
            if (!finished.get() && timedOut.compareAndSet(false, true)) runCatching(abort)
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            connect()
        } catch (e: Exception) {
            if (timedOut.get()) throw TimeoutException("connect timed out after ${timeoutMs}ms").apply { initCause(e) }
            throw e
        } finally {
            finished.set(true)
            task.cancel(false)
        }
        // connect() returned normally but the watchdog closed the socket under it.
        if (timedOut.get()) throw TimeoutException("connect timed out after ${timeoutMs}ms")
    }
}

/**
 * Single-flight gate for control-socket connects. A holder that has not released within
 * [maxHoldMs] is treated as wedged and the gate is reclaimed, so one stuck attempt can't
 * block every later auto-connect for the life of the process.
 */
class InFlightGate(private val maxHoldMs: Long, private val now: () -> Long) {
    private val acquiredAt = AtomicLong(0L)

    sealed interface Result {
        data object Acquired : Result
        data class Reclaimed(val heldForMs: Long) : Result
        data class Busy(val heldForMs: Long) : Result
    }

    /** True only while a live (not yet wedged) holder owns the gate. */
    val isHeld: Boolean get() = acquiredAt.get().let { it != 0L && now() - it < maxHoldMs }

    fun tryAcquire(): Result {
        while (true) {
            val t = now().coerceAtLeast(1L)
            val held = acquiredAt.get()
            if (held == 0L) {
                if (acquiredAt.compareAndSet(0L, t)) return Result.Acquired
                continue
            }
            val age = t - held
            if (age < maxHoldMs) return Result.Busy(age)
            if (acquiredAt.compareAndSet(held, t)) return Result.Reclaimed(age)
        }
    }

    /** Releases only if [token] still owns the gate, so a reclaimed stale holder can't free the new one. */
    fun release(token: Long) { acquiredAt.compareAndSet(token, 0L) }

    /** Acquisition timestamp to pass to [release]; valid right after a successful [tryAcquire]. */
    fun token(): Long = acquiredAt.get()
}
