package io.automated.ventures.everypods.utils.peer

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** One configured peer and its currently owned legacy/v2 resources. */
class PeerConnection(val mac: String, val generation: Long) {
    @Volatile var socket: BluetoothSocket? = null
    @Volatile var isConnected: Boolean = false
    @Volatile var legacyReady: Boolean = false
    @Volatile var job: Job? = null
    @Volatile var backoffJob: Job? = null
    @Volatile var heldHeadsetDelayJob: Job? = null
    @Volatile var inboundRecheckMs: Long = 5 * 60 * 1000L
    @Volatile var outboundQueue: Channel<ByteArray>? = null
    @Volatile var v2Socket: BluetoothSocket? = null
    @Volatile var v2Job: Job? = null
    @Volatile var v2Queue: Channel<ByteArray>? = null
    val bluetoothWake = Channel<Unit>(Channel.CONFLATED)
    @Volatile var reachabilityProbeRequested: Boolean = false
    @Volatile var manualHeldHeadsetAttemptRequested: Boolean = false
    private val stopped = AtomicBoolean(false)

    /** Idempotent full teardown; the caller closes sockets before joining the loop. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        isConnected = false
        legacyReady = false
        backoffJob?.cancel()
        heldHeadsetDelayJob?.cancel()
        v2Job?.cancel()
        v2Socket?.runCatching { close() }
        v2Socket = null
        v2Queue?.close()
        v2Queue = null
        socket?.runCatching { close() }
        socket = null
        outboundQueue?.close()
        outboundQueue = null
        bluetoothWake.close()
        job?.cancel()
    }
}
