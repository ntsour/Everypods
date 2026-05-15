package io.nikos.propods.bluetooth.connection

/**
 * Signal that the peer has sent at least one valid AACP frame after our handshake
 * went out. Implementations should be edge-triggered — every call to
 * [awaitFirstResponse] waits for the *next* signal after the call, not a previously
 * observed one (so retries don't see stale acks).
 */
interface HandshakeAckSource {
    /**
     * Suspends until the next ack signal. Caller is responsible for wrapping in a
     * timeout via the engine's clock. May throw [HandshakeAbortedException] if
     * [abort] is called.
     */
    suspend fun awaitFirstResponse()

    /** Reset any pending signal so the next [awaitFirstResponse] starts from a clean state. */
    fun reset()

    /**
     * Wake any pending [awaitFirstResponse] with a [HandshakeAbortedException].
     * Used by [ConnectionEngine.onDisconnect] to unblock the engine without
     * cancelling its enclosing coroutine.
     */
    fun abort(reason: String)
}

class HandshakeAbortedException(reason: String) : Exception(reason)
