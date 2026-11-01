package io.automated.ventures.everypods.utils.peer

/**
 * Receiver-side freshness gate for a future negotiated case-evidence exchange.
 * A hint is not evidence: the sender must answer a one-use challenge while its
 * own IRK-verified observation is still fresh. Clocks are never compared across
 * devices. This class emits no audio effects and does not change the wire protocol.
 */
class RemoteCaseEvidenceGate(
    private val maxAgeMs: Long = 5_000L,
    private val maxRoundTripMs: Long = 2_000L,
) {
    init { require(maxAgeMs > 0 && maxRoundTripMs > 0 && maxRoundTripMs <= maxAgeMs) }

    data class Challenge(val nonce: String, val observationId: String, val peerId: String, val peerSession: String,
                         val headsetId: String, val startedAtMs: Long, val localRevision: Long)
    data class Reply(val nonce: String, val observationId: String, val peerId: String, val peerSession: String,
                     val headsetId: String, val observationAgeMs: Long,
                     val identityVerified: Boolean, val available: Boolean)

    private var pending: Challenge? = null
    private val consumed = LinkedHashMap<Triple<String, String, String>, Long>()
    private val withdrawn = LinkedHashMap<Triple<String, String, String>, Long>()
    private var failClosedUntilMs = 0L

    private fun prune(nowMs: Long) {
        consumed.entries.removeAll { (_, at) -> nowMs >= at && nowMs - at > maxAgeMs }
        withdrawn.entries.removeAll { (_, at) -> nowMs >= at && nowMs - at > maxAgeMs }
    }

    @Synchronized fun begin(challenge: Challenge, policyEnabled: Boolean, localLastHolder: Boolean,
                            peerSynchronized: Boolean): Boolean {
        val old = pending
        if (old != null && challenge.startedAtMs >= old.startedAtMs &&
            challenge.startedAtMs - old.startedAtMs > maxRoundTripMs) pending = null
        prune(challenge.startedAtMs)
        if (!policyEnabled || !localLastHolder || !peerSynchronized || challenge.nonce.isBlank() || challenge.observationId.isBlank() ||
            challenge.peerId.isBlank() || challenge.peerSession.isBlank() || challenge.headsetId.isBlank() ||
            challenge.startedAtMs < 0 || challenge.localRevision < 0 || pending != null || consumed.size >= 64 ||
            challenge.startedAtMs < failClosedUntilMs ||
            Triple(challenge.peerId, challenge.peerSession, challenge.observationId) in consumed ||
            Triple(challenge.peerId, challenge.peerSession, challenge.observationId) in withdrawn) return false
        pending = challenge
        return true
    }

    /** Returns true once only; every terminal reply consumes the matching challenge. */
    @Synchronized fun accept(reply: Reply, nowMs: Long, currentSession: String?, currentHeadset: String?,
                             currentLocalRevision: Long, policyEnabled: Boolean,
                             localLastHolder: Boolean, peerSynchronized: Boolean): Boolean {
        val request = pending ?: return false
        if (reply.nonce != request.nonce || reply.observationId != request.observationId || reply.peerId != request.peerId) return false
        pending = null
        if (nowMs < request.startedAtMs) return false
        val elapsed = nowMs - request.startedAtMs
        val accepted = elapsed in 0..maxRoundTripMs && reply.observationAgeMs in 0..maxAgeMs &&
            reply.observationAgeMs <= maxAgeMs - elapsed && reply.identityVerified && reply.available &&
            reply.peerSession == request.peerSession && currentSession == request.peerSession &&
            reply.headsetId == request.headsetId && currentHeadset == request.headsetId &&
            currentLocalRevision == request.localRevision && policyEnabled && localLastHolder && peerSynchronized
        if (accepted) {
            consumed[Triple(request.peerId, request.peerSession, request.observationId)] = nowMs
        }
        return accepted
    }

    @Synchronized fun cancel() { pending = null }
    @Synchronized fun cancel(nonce: String) { if (pending?.nonce == nonce) pending = null }

    /** Tombstone before notifying the service, including when withdrawal beats the hint. */
    @Synchronized fun withdraw(peerId: String, session: String, observation: String, nowMs: Long) {
        if (nowMs < 0) return
        prune(nowMs)
        if (pending?.let { it.peerId == peerId && it.peerSession == session && it.observationId == observation } == true) pending = null
        if (withdrawn.size >= 64) {
            failClosedUntilMs = if (nowMs > Long.MAX_VALUE - maxAgeMs) Long.MAX_VALUE else nowMs + maxAgeMs
            pending = null
        } else withdrawn[Triple(peerId, session, observation)] = nowMs
    }

    @Synchronized fun isWithdrawn(peerId: String, session: String, observation: String, nowMs: Long): Boolean {
        prune(nowMs)
        return nowMs < failClosedUntilMs || Triple(peerId, session, observation) in withdrawn
    }
}
