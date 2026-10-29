package io.automated.ventures.everypods.utils.peer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Pure two-phase reservation protocol. It never issues a Bluetooth/headset command. */
class PassiveAcquisitionCoordinator(
    private val localInstallationId: String,
    private val clock: ElapsedClock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Event {
        data class Request(val requestId: String, val headsetId: String, val requesterSessionId: String,
                           val membership: List<String>, val membershipDigest: String,
                           val snapshotRevisions: Map<String, Long>) : Event
        data class Start(val requestId: String, val headsetId: String, val requesterSessionId: String,
                         val requesterId: String, val grantorId: String, val grantorSessionId: String,
                         val membership: List<String>, val membershipDigest: String, val grantId: String) : Event
        data class Cancel(val requestId: String, val requesterId: String, val requesterSessionId: String,
                          val membershipDigest: String) : Event
        data class Grant(val requestId: String, val grantId: String, val grantorId: String) : Event
        data class Deny(val requestId: String, val reason: Denial) : Event
        data class StartAck(val requestId: String, val grantId: String, val grantorId: String) : Event
        data class ReadyToConnect(val requestId: String, val headsetId: String, val generation: Long) : Event
    }
    enum class Denial { HELD, UNKNOWN, MEMBERSHIP_MISMATCH, BUSY, STALE_SNAPSHOT, SESSION_CHANGED, EXPIRED }
    enum class Phase { PREPARING, STARTING, IN_PROGRESS, RESOLVED, CANCELLED }
    data class PeerStatus(val installationId: String, val sessionId: String, val revision: Long,
                          val protocolVersion: Int, val ready: Boolean, val headsetId: String?,
                          val ownership: PeerOwnership, val audioUse: PeerAudioUse)
    data class Intent(val requestId: String, val headsetId: String, val generation: Long,
                      val expiresAtElapsedMs: Long, val membership: List<String>,
                      val membershipDigest: String, val requesterSessionId: String, val sentAtElapsedMs: Long,
                      val snapshotRevisions: Map<String, Long>,
                      val phase: Phase, val grants: Map<String, String>,
                      val grantorSessions: Map<String, String>, val startAcks: Set<String>,
                      val connectIssued: Boolean = false, val connectionObserved: Boolean = false)
    data class ParticipantReservation(val requestId: String, val requesterId: String,
                                      val requesterSessionId: String, val headsetId: String,
                                      val membershipDigest: String, val snapshotRevisions: Map<String, Long>, val grantId: String,
                                      val grantorSessionId: String, val phase: Phase, val expiresAtElapsedMs: Long)

    data class Transition(val role: String, val requestId: String, val phase: Phase,
                          val reason: String, val commandIssued: Boolean = false)
    var transitionListener: ((Transition) -> Unit)? = null
    private fun changed(role: String, requestId: String, phase: Phase, reason: String,
                        commandIssued: Boolean = false) {
        transitionListener?.invoke(Transition(role, requestId, phase, reason, commandIssued))
    }

    private var generation = 0L
    private var intent: Intent? = null
    private var reservation: ParticipantReservation? = null
    private val pendingCancellations = linkedMapOf<String, Event.Cancel>()
    @Synchronized fun pendingCancellations(): List<Event.Cancel> = pendingCancellations.values.toList()
    private fun rememberCancellation(current: Intent): Event.Cancel =
        Event.Cancel(current.requestId, localInstallationId, current.requesterSessionId, current.membershipDigest)
            .also { pendingCancellations[current.requestId] = it }

    @Synchronized fun currentIntent(): Intent? = intent
    @Synchronized fun currentReservation(): ParticipantReservation? = reservation

    /** Creates a fresh 10-second intent only if all configured peers are synchronized v2 peers. */
    @Synchronized fun begin(
        headsetId: String,
        requesterSessionId: String,
        membership: Set<String>,
        localUnowned: Boolean,
        lastOwnerEligible: Boolean,
        lidOpen: Boolean,
        enabled: Boolean,
        localSnapshotRevision: Long,
        peers: Map<String, PeerStatus>,
    ): Event {
        val now = clock.nowMs()
        if (!lidOpen || !enabled || !localUnowned || !lastOwnerEligible) return Event.Deny("", Denial.UNKNOWN)
        val members = membership.map { it.lowercase() }.distinct().sorted()
        if (members.size < 2 || localInstallationId.lowercase() !in members) return Event.Deny("", Denial.MEMBERSHIP_MISMATCH)
        if (peers.keys.map { it.lowercase() }.toSet() != members.toSet() - localInstallationId.lowercase()) {
            return Event.Deny("", Denial.MEMBERSHIP_MISMATCH)
        }
        if (peers.values.any { !it.ready || it.protocolVersion != 2 || it.headsetId != headsetId ||
                    it.ownership != PeerOwnership.RELEASED || it.audioUse != PeerAudioUse.IDLE }) {
            return Event.Deny("", Denial.UNKNOWN)
        }
        reservation?.takeIf { it.phase == Phase.PREPARING && now >= it.expiresAtElapsedMs }?.let {
            reservation = it.copy(phase = Phase.CANCELLED)
            changed("participant", it.requestId, Phase.CANCELLED, "preparation_expired")
        }
        if (reservation?.phase in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) {
            return Event.Deny("", Denial.BUSY)
        }
        val revisions = peers.values.associate { it.installationId.lowercase() to it.revision } +
            (localInstallationId.lowercase() to localSnapshotRevision)
        if (revisions.values.any { it < 0 }) return Event.Deny("", Denial.STALE_SNAPSHOT)
        val membersDigest = membershipDigest(members)
        val active = intent
        if (active?.phase == Phase.IN_PROGRESS) return Event.Deny(active.requestId, Denial.BUSY)
        if (active != null && active.phase in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) {
            if (active.phase == Phase.IN_PROGRESS) return Event.Deny(active.requestId, Denial.BUSY)
            if (now < active.expiresAtElapsedMs && active.headsetId == headsetId &&
                active.requesterSessionId == requesterSessionId && active.membership == members &&
                active.membershipDigest == membersDigest && active.snapshotRevisions == revisions) {
                return Event.Request(active.requestId, active.headsetId, active.requesterSessionId,
                    active.membership, active.membershipDigest, active.snapshotRevisions)
            }
            return Event.Deny(active.requestId, Denial.BUSY)
        }
        generation++
        val requestId = idFactory()
        val digest = membersDigest
        intent = Intent(requestId, headsetId, generation, now + INTENT_TTL_MS, members, digest, requesterSessionId,
            now, revisions, Phase.PREPARING, emptyMap(), emptyMap(), emptySet())
        changed("requester", requestId, Phase.PREPARING, "begin")
        return Event.Request(requestId, headsetId, requesterSessionId, members, digest, revisions)
    }

    @Synchronized fun onGrant(peerId: String, grantorSessionId: String, requestId: String, grantId: String): List<Event> {
        val current = intent ?: return emptyList()
        val now = clock.nowMs()
        if (current.requestId != requestId || current.phase != Phase.PREPARING || now > current.sentAtElapsedMs + GRANT_RECEIVE_MS ||
            now >= current.expiresAtElapsedMs || peerId.lowercase() !in current.membership || peerId.lowercase() == localInstallationId.lowercase()) return emptyList()
        val existing = current.grants[peerId.lowercase()]
        if (existing != null && existing != grantId) return emptyList()
        val grants = current.grants + (peerId.lowercase() to grantId)
        val sessions = current.grantorSessions + (peerId.lowercase() to grantorSessionId)
        val otherPeers = current.membership.filter { it != localInstallationId.lowercase() }.toSet()
        if (grants.keys != otherPeers) {
            intent = current.copy(grants = grants, grantorSessions = sessions)
            return emptyList()
        }
        // Every participant granted a new round, so all earlier locks are gone.
        pendingCancellations.clear()
        intent = current.copy(phase = Phase.STARTING, grants = grants, grantorSessions = sessions)
        return grants.map { (grantor, token) -> Event.Start(current.requestId, current.headsetId,
            current.requesterSessionId, localInstallationId, grantor, sessions.getValue(grantor),
            current.membership, current.membershipDigest, token) }
    }

    @Synchronized fun onStartAck(peerId: String, requestId: String, grantId: String): Event? {
        val current = intent ?: return null
        val now = clock.nowMs()
        if (current.requestId != requestId || current.phase != Phase.STARTING ||
            now > current.sentAtElapsedMs + START_ACK_MS || now >= current.expiresAtElapsedMs ||
            current.grants[peerId.lowercase()] != grantId) return null
        val acks = current.startAcks + peerId.lowercase()
        intent = current.copy(startAcks = acks)
        val required = current.membership.filter { it != localInstallationId.lowercase() }.toSet()
        if (acks != required) return null
        val ready = intent!!.copy(phase = Phase.IN_PROGRESS)
        intent = ready
        changed("requester", current.requestId, Phase.IN_PROGRESS, "ready_before_command")
        return Event.ReadyToConnect(ready.requestId, ready.headsetId, ready.generation)
    }

    /** Must be called immediately before the headset command; any changed precondition cancels. */
    @Synchronized fun authorizeConnect(requestId: String, generationAtCall: Long, lidOpen: Boolean,
                                       enabled: Boolean, headsetId: String, membership: Set<String>,
                                       peers: Map<String, PeerStatus>, issueCommand: Boolean = true): Boolean {
        val current = intent ?: return false
        val now = clock.nowMs()
        val peerIds = peers.keys.map { it.lowercase() }.toSet()
        val expected = current.membership.filter { it != localInstallationId.lowercase() }.toSet()
        val peersStillSafe = peerIds == expected && peers.values.all { it.ready && it.protocolVersion == 2 &&
            it.headsetId == headsetId && it.ownership == PeerOwnership.RELEASED && it.audioUse == PeerAudioUse.IDLE } &&
            peers.all { (id, status) -> current.snapshotRevisions[id.lowercase()] == status.revision }
        val allowed = current.requestId == requestId && current.generation == generationAtCall &&
            current.phase == Phase.IN_PROGRESS && (!issueCommand || !current.connectIssued) &&
            now < current.expiresAtElapsedMs && lidOpen && enabled &&
            headsetId == current.headsetId && membership.map { it.lowercase() }.toSet() == current.membership.toSet() &&
            current.startAcks == expected && peersStillSafe
        if (allowed && issueCommand) {
            intent = current.copy(connectIssued = true)
            changed("requester", current.requestId, Phase.IN_PROGRESS, "command_committed", true)
        } else if (!allowed && (current.requestId == requestId && current.generation == generationAtCall)) cancelIntent()
        return allowed
    }

    @Synchronized fun cancelIntent(): List<Event> {
        val old = intent ?: return emptyList()
        if (old.phase !in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) return emptyList()
        // Once the OS headset command has been issued it may complete after this
        // coroutine or phone is gone. Keep the reservation until an ownership
        // snapshot resolves it; a user/lid cancellation cannot free the quorum.
        if (old.phase == Phase.IN_PROGRESS && old.connectIssued) return emptyList()
        generation++
        intent = old.copy(phase = Phase.CANCELLED)
        changed("requester", old.requestId, Phase.CANCELLED, "cancel_before_command")
        return listOf(rememberCancellation(old))
    }

    @Synchronized fun cancelExpiredPreparation(): List<Event> {
        val current = intent ?: return emptyList()
        return if (!current.connectIssued && clock.nowMs() >= current.expiresAtElapsedMs) cancelIntent() else emptyList()
    }

    @Synchronized fun commandIsCurrent(requestId: String, generationAtCall: Long): Boolean {
        val current = intent ?: return false
        return current.requestId == requestId && current.generation == generationAtCall &&
            current.phase == Phase.IN_PROGRESS && current.connectIssued
    }

    @Synchronized fun observeConnecting() {
        val current = intent ?: return
        if (current.phase == Phase.IN_PROGRESS && current.connectIssued) {
            intent = current.copy(connectionObserved = true)
            changed("requester", current.requestId, current.phase, "profile_connecting", true)
        }
    }

    /** The OS explicitly refused this command before any connection progress. */
    @Synchronized fun rejectCommand(requestId: String, generationAtCall: Long): List<Event> {
        val current = intent ?: return emptyList()
        if (!commandIsCurrent(requestId, generationAtCall) || current.connectionObserved) return emptyList()
        generation++
        intent = current.copy(phase = Phase.CANCELLED)
        changed("requester", current.requestId, Phase.CANCELLED, "os_rejected_command", true)
        return listOf(rememberCancellation(current))
    }

    /** Only a real profile transition after this command proves terminal failure. */
    @Synchronized fun failAfterProfileDisconnect(): List<Event> {
        val current = intent ?: return emptyList()
        if (current.phase != Phase.IN_PROGRESS || !current.connectIssued || !current.connectionObserved) return emptyList()
        generation++
        intent = current.copy(phase = Phase.CANCELLED)
        changed("requester", current.requestId, Phase.CANCELLED, "profile_failed_after_command", true)
        return listOf(rememberCancellation(current))
    }

    @Synchronized fun participantRequest(
        requestId: String, requesterId: String, requesterSessionId: String, headsetId: String,
        membership: List<String>, membershipDigest: String, localSessionId: String,
        localStatus: PeerStatus?, configuredMembership: Set<String>,
        snapshotRevisions: Map<String, Long>? = null,
        currentSnapshotRevisions: Map<String, Long>? = null,
    ): Event {
        val now = clock.nowMs()
        val members = membership.map { it.lowercase() }
        if (intent?.phase in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) return Event.Deny(requestId, Denial.BUSY)
        if (members != configuredMembership.map { it.lowercase() }.distinct().sorted() ||
            members != members.distinct().sorted() || localInstallationId.lowercase() !in members ||
            requesterId.lowercase() !in members || membershipDigest(members) != membershipDigest) return Event.Deny(requestId, Denial.MEMBERSHIP_MISMATCH)
        if (snapshotRevisions != null && (currentSnapshotRevisions == null ||
                snapshotRevisions.keys != members.toSet() || snapshotRevisions != currentSnapshotRevisions)) {
            return Event.Deny(requestId, Denial.STALE_SNAPSHOT)
        }
        if (localStatus == null || !localStatus.ready || localStatus.protocolVersion != 2 || localStatus.sessionId != localSessionId ||
            localStatus.headsetId != headsetId || localStatus.ownership == PeerOwnership.UNKNOWN) return Event.Deny(requestId, Denial.UNKNOWN)
        if (localStatus.ownership == PeerOwnership.HELD || localStatus.audioUse != PeerAudioUse.IDLE) return Event.Deny(requestId, Denial.HELD)
        val existing = reservation
        if (existing != null && existing.phase in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) {
            if (existing.phase == Phase.PREPARING && now >= existing.expiresAtElapsedMs) {
                reservation = existing.copy(phase = Phase.CANCELLED)
                if (existing.requestId == requestId) return Event.Deny(requestId, Denial.EXPIRED)
            } else {
                return if (existing.requestId == requestId && existing.requesterId == requesterId &&
                    existing.requesterSessionId == requesterSessionId && existing.headsetId == headsetId &&
                    existing.membershipDigest == membershipDigest) Event.Grant(requestId, existing.grantId, localInstallationId)
                else Event.Deny(requestId, Denial.BUSY)
            }
        }
        val grantId = idFactory()
        reservation = ParticipantReservation(requestId, requesterId, requesterSessionId, headsetId,
            membershipDigest, snapshotRevisions.orEmpty(), grantId, localSessionId, Phase.PREPARING, now + PREPARATION_TTL_MS)
        changed("participant", requestId, Phase.PREPARING, "granted")
        return Event.Grant(requestId, grantId, localInstallationId)
    }

    @Synchronized fun participantStart(requestId: String, requesterId: String, requesterSessionId: String,
                                       grantId: String, currentSessionId: String,
                                       localStatus: PeerStatus? = null,
                                       currentSnapshotRevisions: Map<String, Long>? = null,
                                       membership: List<String>? = null,
                                       configuredMembership: Set<String>? = null): Event {
        val current = reservation ?: return Event.Deny(requestId, Denial.EXPIRED)
        if (current.requestId != requestId || current.requesterId != requesterId ||
            current.requesterSessionId != requesterSessionId || current.grantId != grantId ||
            currentSessionId != current.grantorSessionId) return Event.Deny(requestId, Denial.EXPIRED)
        val duplicateStart = current.phase == Phase.IN_PROGRESS
        if (!duplicateStart && (current.phase != Phase.PREPARING || clock.nowMs() >= current.expiresAtElapsedMs)) {
            if (current.phase == Phase.PREPARING) reservation = current.copy(phase = Phase.CANCELLED)
            return Event.Deny(requestId, Denial.EXPIRED)
        }
        if (current.snapshotRevisions.isNotEmpty() && current.snapshotRevisions != currentSnapshotRevisions) {
            if (!duplicateStart) reservation = current.copy(phase = Phase.CANCELLED)
            return Event.Deny(requestId, Denial.STALE_SNAPSHOT)
        }
        if (membership != null && (membership != membership.distinct().sorted() ||
                membershipDigest(membership) != current.membershipDigest ||
                configuredMembership?.map { it.lowercase() }?.toSet() != membership.toSet())) {
            if (!duplicateStart) reservation = current.copy(phase = Phase.CANCELLED)
            return Event.Deny(requestId, Denial.MEMBERSHIP_MISMATCH)
        }
        if (localStatus != null && (!localStatus.ready || localStatus.protocolVersion != 2 ||
                localStatus.sessionId != currentSessionId || localStatus.headsetId != current.headsetId ||
                localStatus.ownership != PeerOwnership.RELEASED || localStatus.audioUse != PeerAudioUse.IDLE)) {
            if (!duplicateStart) reservation = current.copy(phase = Phase.CANCELLED)
            return Event.Deny(requestId, if (localStatus.ownership == PeerOwnership.HELD || localStatus.audioUse != PeerAudioUse.IDLE)
                Denial.HELD else Denial.UNKNOWN)
        }
        if (duplicateStart) return Event.StartAck(requestId, grantId, localInstallationId)
        reservation = current.copy(phase = Phase.IN_PROGRESS, expiresAtElapsedMs = Long.MAX_VALUE)
        changed("participant", current.requestId, Phase.IN_PROGRESS, "start_acknowledged")
        return Event.StartAck(requestId, grantId, localInstallationId)
    }

    /** CANCEL is terminal proof from the authenticated requester, not a timeout. */
    @Synchronized fun participantCancel(requestId: String, requesterId: String, requesterSessionId: String,
                                        membershipDigest: String? = null): Boolean {
        val current = reservation ?: return false
        if (current.requestId != requestId || current.requesterId != requesterId ||
            current.requesterSessionId != requesterSessionId ||
            membershipDigest != null && current.membershipDigest != membershipDigest ||
            current.phase !in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) return false
        reservation = current.copy(phase = Phase.CANCELLED)
        changed("participant", requestId, Phase.CANCELLED, "requester_terminal_cancel")
        return true
    }

    /** HELD is success evidence even when offline/manual recovery used another request. */
    @Synchronized fun observePeerHeld(requesterId: String, headsetId: String): Boolean {
        val current = reservation ?: return false
        if (current.requesterId != requesterId || current.headsetId != headsetId ||
            current.phase !in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) return false
        return participantResolve(current.requestId, true)
    }

    @Synchronized fun participantResolve(requestId: String, acquisitionConfirmedOrDefinitelyFailed: Boolean): Boolean {
        val current = reservation ?: return false
        if (current.requestId != requestId || !acquisitionConfirmedOrDefinitelyFailed ||
            current.phase !in setOf(Phase.PREPARING, Phase.STARTING, Phase.IN_PROGRESS)) return false
        reservation = current.copy(phase = Phase.RESOLVED)
        changed("participant", requestId, Phase.RESOLVED, "headset_outcome_confirmed")
        return true
    }

    @Synchronized fun requesterResolve(requestId: String, acquisitionConfirmed: Boolean): Boolean {
        val current = intent ?: return false
        if (current.requestId != requestId || current.phase != Phase.IN_PROGRESS || !acquisitionConfirmed) return false
        intent = current.copy(phase = Phase.RESOLVED)
        changed("requester", requestId, Phase.RESOLVED, "headset_held", current.connectIssued)
        return true
    }

    companion object {
        const val INTENT_TTL_MS = 10_000L
        const val PREPARATION_TTL_MS = 5_000L
        const val GRANT_RECEIVE_MS = 2_000L
        const val START_ACK_MS = 3_000L
        fun membershipDigest(members: List<String>): String {
            val canonical = members.map { it.lowercase() }.distinct().sorted().joinToString("\n", postfix = "\n")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
