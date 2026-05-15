package io.nikos.propods.bluetooth.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default [HandshakeAckSource] implementation. Each [reset] arms a fresh
 * [CompletableDeferred]; the first [signalReceived] call after a reset
 * completes it. [awaitFirstResponse] suspends until that completion.
 *
 * Thread-safe: state is guarded by a mutex so that a reset that races a
 * signal doesn't lose the signal silently.
 */
class DeferredHandshakeAckSource : HandshakeAckSource {
    private val mutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun awaitFirstResponse() {
        val toAwait = mutex.withLock { pending }
        toAwait.await()
    }

    override fun reset() {
        // Swap in a fresh deferred. Any awaiter on the old one is left waiting; the
        // engine controls the timeout, so this is fine.
        pending = CompletableDeferred()
    }

    /** Called by the receive path when a valid packet arrives. Idempotent. */
    fun signalReceived() {
        pending.complete(Unit)
    }

    override fun abort(reason: String) {
        pending.completeExceptionally(HandshakeAbortedException(reason))
    }
}
