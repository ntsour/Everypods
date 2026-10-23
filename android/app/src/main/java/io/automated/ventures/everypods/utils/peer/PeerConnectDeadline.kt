package io.automated.ventures.everypods.utils.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Watchdog for one exact blocking RFCOMM socket connect attempt. */
class PeerConnectDeadline(
    scope: CoroutineScope,
    timeoutMs: Long,
    private val isCurrent: () -> Boolean,
    private val isConnected: () -> Boolean,
    private val closeSocket: () -> Unit,
) {
    private val timedOut = AtomicBoolean(false)
    private val watchdog: Job

    init {
        require(timeoutMs > 0L)
        watchdog = scope.launch {
            delay(timeoutMs)
            if (isCurrent() && !isConnected()) {
                timedOut.set(true)
                runCatching(closeSocket)
            }
        }
    }

    fun hasTimedOut(): Boolean = timedOut.get()

    fun cancel() = watchdog.cancel()
}
