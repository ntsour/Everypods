package io.automated.ventures.everypods.utils.peer

import java.util.UUID

/**
 * Liveness toward a peer that advertises `quiet-v1` (Everypods-private docs/peer-link-quiet-redesign.md, D1), in
 * place of [PeerHeartbeat]'s PING every 10 s: a PING only after [IDLE_PROBE_MS] with nothing received, another after
 * [PROBE_RETRY_MS], and the link is gone when [MAX_UNANSWERED] probes in a row go unanswered. Any frame from the peer
 * counts as an answer. A dead link is still found by the transport (socket error, link supervision timeout); this
 * only catches a peer app that hangs with its link up. Times are the caller's elapsedRealtime milliseconds.
 */
class PeerIdleProbe(startMs: Long, private val nonceFactory: () -> String = { UUID.randomUUID().toString() }) {
    sealed interface Action {
        data class SendPing(val nonce: String) : Action
        data object Wait : Action
        data object TimedOut : Action
    }

    private var lastReceivedMs = startMs
    private var probeSentMs: Long? = null
    private var unanswered = 0
    private var outstanding: String? = null

    /** The latest probe's nonce until its PONG arrives. */
    @Synchronized fun outstandingNonce(): String? = outstanding

    /**
     * Any frame from the peer: it is alive, so the minute starts again. The probe's nonce stays until its PONG
     * arrives ([acknowledge]) or the next probe replaces it, so a PONG that comes after another frame (both sides
     * probing at once) still delivers its snapshot.
     */
    @Synchronized fun onFrameReceived(nowMs: Long) {
        lastReceivedMs = nowMs
        probeSentMs = null
        unanswered = 0
    }

    /** True if [nonce] answers the latest probe (then it is spent). */
    @Synchronized fun acknowledge(nonce: String): Boolean {
        if (outstanding == null || nonce != outstanding) return false
        outstanding = null
        return true
    }

    @Synchronized fun onTick(nowMs: Long): Action {
        val sent = probeSentMs
        if (sent != null) {
            if (nowMs - sent < PROBE_RETRY_MS) return Action.Wait
            if (unanswered >= MAX_UNANSWERED) return Action.TimedOut
        } else if (nowMs - lastReceivedMs < IDLE_PROBE_MS) {
            return Action.Wait
        }
        val nonce = nonceFactory()
        outstanding = nonce
        probeSentMs = nowMs
        unanswered++
        return Action.SendPing(nonce)
    }

    /** When [onTick] next has something to do. */
    @Synchronized fun nextDueMs(): Long = probeSentMs?.let { it + PROBE_RETRY_MS } ?: (lastReceivedMs + IDLE_PROBE_MS)

    companion object {
        const val CAPABILITY = "quiet-v1"
        const val IDLE_PROBE_MS = 60_000L
        const val PROBE_RETRY_MS = 15_000L
        const val MAX_UNANSWERED = 2
    }
}
