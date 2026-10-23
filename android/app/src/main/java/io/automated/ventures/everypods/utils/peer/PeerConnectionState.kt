package io.automated.ventures.everypods.utils.peer

/** Immutable per-peer lifecycle state; Bluetooth socket flags are never UI authority. */
sealed interface PeerConnectionState {
    data object Disabled : PeerConnectionState
    data object WaitingForBluetooth : PeerConnectionState
    data object WaitingForPermission : PeerConnectionState
    data class Backoff(val attempt: Int, val retryAtElapsedMs: Long) : PeerConnectionState
    data class Connecting(val generation: Long) : PeerConnectionState
    data class Synchronizing(val generation: Long) : PeerConnectionState
    data class Ready(
        val generation: Long,
        val protocolVersion: Int,
        val sessionId: String?,
        val snapshotRevision: Long?,
    ) : PeerConnectionState
}

enum class PeerConnectionError {
    PERMISSION_REQUIRED,
    ADAPTER_UNAVAILABLE,
    CONNECT_TIMEOUT,
    REMOTE_UNAVAILABLE,
    PROTOCOL_INVALID,
    HANDSHAKE_TIMEOUT,
    HEARTBEAT_TIMEOUT,
    IO_FAILURE,
    PEER_REMOVED,
}

/** Exponential bounded retry with injected jitter, based only on monotonic time. */
object PeerRetryPolicy {
    private val delaysMs = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)
    const val MAX_DELAY_MS = 15_000L
    const val MIN_DELAY_DURING_AUDIO_MS = 15_000L

    fun requiresUserCorrection(error: PeerConnectionError?): Boolean =
        error == PeerConnectionError.PERMISSION_REQUIRED

    fun delayMs(attempt: Int, localAudioActive: Boolean, jitterFactor: Double): Long {
        require(attempt >= 0) { "negative_attempt" }
        require(jitterFactor in 0.8..1.2) { "jitter_out_of_bounds" }
        val base = delaysMs[attempt.coerceAtMost(delaysMs.lastIndex)]
            .coerceAtMost(MAX_DELAY_MS)
            .let { if (localAudioActive) maxOf(it, MIN_DELAY_DURING_AUDIO_MS) else it }
        return (base * jitterFactor).toLong()
    }
}
