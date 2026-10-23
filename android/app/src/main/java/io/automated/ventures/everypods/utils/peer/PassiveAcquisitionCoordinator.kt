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
                      val connectIssued: Boolean = false)
    data class ParticipantReservation(val requestId: String, val requesterId: String,
                                      val requesterSessionId: String, val headsetId: String,
                                      val membershipDigest: String, val snapshotRevisions: Map<String, Long>, val grantId: String,
                                      val grantorSessionId: String, val phase: Phase, val expiresAtElapsedMs: Long)

    private var generation = 0L
    private var intent: Intent? = null
    private var reservation: ParticipantReservation? = null

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
        return Event.ReadyToConnect(ready.requestId, ready.headsetId, ready.generation)
    }

    /** Must be called immediately before the headset command; any changed precondition cancels. */
    @Synchronized fun authorizeConnect(requestId: String, generationAtCall: Long, lidOpen: Boolean,
                                       enabled: Boolean, headsetId: String, membership: Set<String>,
                                       peers: Map<String, PeerStatus>): Boolean {
        val current = intent ?: return false
        val now = clock.nowMs()
        val peerIds = peers.keys.map { it.lowercase() }.toSet()
        val expected = current.membership.filter { it != localInstallationId.lowercase() }.toSet()
        val peersStillSafe = peerIds == expected && peers.values.all { it.ready && it.protocolVersion == 2 &&
            it.headsetId == headsetId && it.ownership == PeerOwnership.RELEASED && it.audioUse == PeerAudioUse.IDLE } &&
            peers.all { (id, status) -> current.snapshotRevisions[id.lowercase()] == status.revision }
        val allowed = current.requestId == requestId && current.generation == generationAtCall &&
            current.phase == Phase.IN_PROGRESS && now < current.expiresAtElapsedMs && lidOpen && enabled &&
            headsetId == current.headsetId && membership.map { it.lowercase() }.toSet() == current.membership.toSet() &&
            current.startAcks == expected && peersStillSafe
        if (allowed) intent = current.copy(connectIssued = true)
        else if (current.requestId == requestId && current.generation == generationAtCall) cancelIntent()
        return allowed
    }

    @Synchronized fun cancelIntent(): List<Event> {
        val old = intent ?: return emptyList()
        // Once the OS headset command has been issued it may complete after this
        // coroutine or phone is gone. Keep the reservation until an ownership
        // snapshot resolves it; a user/lid cancellation cannot free the quorum.
        if (old.phase == Phase.IN_PROGRESS && old.connectIssued) return emptyList()
        generation++
        intent = old.copy(phase = Phase.CANCELLED)
        return listOf(Event.Cancel(old.requestId, localInstallationId, old.requesterSessionId, old.membershipDigest))
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
        val grantId = idFactory()
        reservation = ParticipantReservation(requestId, requesterId, requesterSessionId, headsetId,
            membershipDigest, snapshotRevisions.orEmpty(), grantId, localSessionId, Phase.PREPARING, now + PREPARATION_TTL_MS)
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
        return Event.StartAck(requestId, grantId, localInstallationId)
    }

    @Synchronized fun participantCancel(requestId: String, requesterId: String, requesterSessionId: String): Boolean {
        val current = reservation ?: return false
        if (current.requestId != requestId || current.requesterId != requesterId || current.requesterSessionId != requesterSessionId) return false
        reservation = if (current.phase == Phase.PREPARING) current.copy(phase = Phase.CANCELLED) else current
        return current.phase == Phase.PREPARING
    }

    @Synchronized fun participantResolve(requestId: String, acquisitionConfirmedOrDefinitelyFailed: Boolean): Boolean {
        val current = reservation ?: return false
        if (current.requestId != requestId || !acquisitionConfirmedOrDefinitelyFailed) return false
        reservation = current.copy(phase = Phase.RESOLVED)
        return true
    }

    @Synchronized fun requesterResolve(requestId: String, acquisitionConfirmed: Boolean): Boolean {
        val current = intent ?: return false
        if (current.requestId != requestId || current.phase != Phase.IN_PROGRESS || !acquisitionConfirmed) return false
        intent = current.copy(phase = Phase.RESOLVED)
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
