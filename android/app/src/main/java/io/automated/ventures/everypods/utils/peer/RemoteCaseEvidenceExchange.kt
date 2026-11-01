package io.automated.ventures.everypods.utils.peer

import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Challenge/response on the dedicated case-evidence transport. The caller must
 * authenticate the socket's bonded peer, enforce an I/O deadline, and close it.
 * No method here starts an audio connection or grants ownership.
 */
object RemoteCaseEvidenceExchange {
    data class Observation(val session: UUID, val headset: UUID, val id: UUID,
                           val ageMs: Int, val identityVerified: Boolean, val available: Boolean)
    data class ReceiverState(val localSession: UUID, val remoteSession: UUID?, val headset: UUID?,
                             val localRevision: Long, val enabled: Boolean, val lastHolder: Boolean,
                             val synchronized: Boolean)
    data class Accepted(val peerId: String, val peerSession: UUID, val headset: UUID,
                        val observation: UUID, val localSession: UUID, val localRevision: Long,
                        val acceptedAtMs: Long, val remainingFreshMs: Long)

    /** Observer sends only observations it can still verify after the challenge arrives. */
    fun send(input: InputStream, output: OutputStream, initial: Observation,
             expectedReceiverSession: UUID, currentPeerSession: () -> UUID?, current: () -> Observation?): Boolean {
        if (currentPeerSession() != expectedReceiverSession || !initial.identityVerified ||
            !initial.available || initial.ageMs !in 0..5_000) return false
        output.write(RemoteCaseEvidenceMessage.encode(RemoteCaseEvidenceMessage.Message.Hint(
            initial.session, initial.headset, initial.id)))
        output.flush()
        val challenge = RemoteCaseEvidenceMessage.read(input) as? RemoteCaseEvidenceMessage.Message.Challenge ?: return false
        if (challenge.session != expectedReceiverSession || currentPeerSession() != expectedReceiverSession || challenge.headset != initial.headset ||
            challenge.observation != initial.id) return false
        val fresh = current() ?: return false
        if (currentPeerSession() != expectedReceiverSession || fresh.session != initial.session || fresh.headset != initial.headset || fresh.id != initial.id ||
            !fresh.identityVerified || !fresh.available || fresh.ageMs !in 0..5_000) return false
        output.write(RemoteCaseEvidenceMessage.encode(RemoteCaseEvidenceMessage.Message.Confirmation(
            fresh.session, fresh.headset, fresh.id, challenge.nonce, fresh.ageMs, fresh.available)))
        output.flush()
        return true
    }

    /** State is read again after I/O, so peer/session/policy or local case changes revoke the reply. */
    fun receive(input: InputStream, output: OutputStream, peerId: String,
                state: () -> ReceiverState, clock: () -> Long, nonce: () -> UUID,
                gate: RemoteCaseEvidenceGate, first: RemoteCaseEvidenceMessage.Message? = null): Accepted? {
        val hint = (first ?: RemoteCaseEvidenceMessage.read(input)) as? RemoteCaseEvidenceMessage.Message.Hint ?: return null
        val before = state()
        if (before.remoteSession != hint.session || before.headset != hint.headset) return null
        val started = clock()
        val token = nonce()
        val challenge = RemoteCaseEvidenceGate.Challenge(token.toString(), hint.observation.toString(), peerId,
            hint.session.toString(), hint.headset.toString(), started, before.localRevision)
        if (!gate.begin(challenge, before.enabled, before.lastHolder, before.synchronized)) return null
        try {
            output.write(RemoteCaseEvidenceMessage.encode(RemoteCaseEvidenceMessage.Message.Challenge(
                before.localSession, hint.headset, hint.observation, token)))
            output.flush()
            val confirm = RemoteCaseEvidenceMessage.read(input) as? RemoteCaseEvidenceMessage.Message.Confirmation ?: return null
            val after = state()
            val finished = clock()
            val valid = gate.accept(RemoteCaseEvidenceGate.Reply(confirm.nonce.toString(), confirm.observation.toString(),
                peerId, confirm.session.toString(), confirm.headset.toString(), confirm.ageMs.toLong(),
                identityVerified = true, available = confirm.available), finished,
                after.remoteSession?.toString(), after.headset?.toString(), after.localRevision,
                after.enabled, after.lastHolder, after.synchronized && after.localSession == before.localSession)
            if (!valid) return null
            return Accepted(peerId, hint.session, hint.headset, hint.observation, before.localSession,
                before.localRevision, finished,
                5_000L - confirm.ageMs - (finished - started))
        } finally {
            gate.cancel(token.toString())
        }
    }
}
