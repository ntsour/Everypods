package io.automated.ventures.everypods.utils.peer

/** Pure conversion between the reservation state machine and strict v2 wire messages. */
class PeerAcquisitionProtocol(
    private val localInstallationId: String,
    private val localSessionId: () -> String,
    private val coordinator: PassiveAcquisitionCoordinator,
) {
    data class Result(val replies: List<PeerV2Frame.Frame>, val readyToConnect: PassiveAcquisitionCoordinator.Event.ReadyToConnect? = null)

    fun begin(
        headsetId: String,
        membership: Set<String>,
        localUnowned: Boolean,
        lastOwnerEligible: Boolean,
        lidOpen: Boolean,
        enabled: Boolean,
        localSnapshotRevision: Long,
        peers: Map<String, PassiveAcquisitionCoordinator.PeerStatus>,
    ): List<PeerV2Frame.Frame> {
        val event = coordinator.begin(headsetId, localSessionId(), membership,
            localUnowned, lastOwnerEligible, lidOpen, enabled, localSnapshotRevision, peers)
        return if (event is PassiveAcquisitionCoordinator.Event.Request) frames(listOf(event)) else emptyList()
    }

    fun receive(
        authenticatedPeerId: String,
        authenticatedPeerSessionId: String,
        frame: PeerV2Frame.Frame,
        localStatus: PassiveAcquisitionCoordinator.PeerStatus?,
        configuredMembership: Set<String>,
        currentSnapshotRevisions: Map<String, Long>? = null,
    ): Result {
        val message = PeerV2Message.decode(frame)
        val f = message.fields
        when (message.type) {
            PeerV2Frame.Type.ACQUIRE_REQUEST -> {
                require(f.getValue("requesterId") == authenticatedPeerId &&
                    f.getValue("requesterSessionId") == authenticatedPeerSessionId) { "requester_identity_mismatch" }
                val decision = coordinator.participantRequest(
                    requestId = f.getValue("requestId") as String,
                    requesterId = authenticatedPeerId,
                    requesterSessionId = authenticatedPeerSessionId,
                    headsetId = f.getValue("headsetId") as String,
                    membership = (f.getValue("membership") as List<*>).filterIsInstance<String>(),
                    membershipDigest = f.getValue("membershipDigest") as String,
                    localSessionId = localSessionId(),
                    localStatus = localStatus,
                    configuredMembership = configuredMembership,
                    snapshotRevisions = longMap(f.getValue("snapshotRevisions")),
                    currentSnapshotRevisions = currentSnapshotRevisions,
                )
                return Result(listOf(participantReply(decision, f)))
            }
            PeerV2Frame.Type.ACQUIRE_START -> {
                require(f.getValue("requesterId") == authenticatedPeerId &&
                    f.getValue("requesterSessionId") == authenticatedPeerSessionId &&
                    f.getValue("grantorId") == localInstallationId &&
                    f.getValue("grantorSessionId") == localSessionId()) { "start_identity_mismatch" }
                val decision = coordinator.participantStart(
                    f.getValue("requestId") as String, authenticatedPeerId, authenticatedPeerSessionId,
                    f.getValue("grantId") as String, localSessionId(), localStatus,
                    currentSnapshotRevisions,
                    (f.getValue("membership") as List<*>).filterIsInstance<String>(),
                    configuredMembership,
                )
                return Result(listOf(participantReply(decision, f)))
            }
            PeerV2Frame.Type.ACQUIRE_CANCEL -> {
                require(f.getValue("requesterId") == authenticatedPeerId &&
                    f.getValue("requesterSessionId") == authenticatedPeerSessionId) { "cancel_identity_mismatch" }
                coordinator.participantCancel(f.getValue("requestId") as String,
                    authenticatedPeerId, authenticatedPeerSessionId)
                return Result(emptyList())
            }
            PeerV2Frame.Type.ACQUIRE_GRANT -> {
                require(f.getValue("grantorId") == authenticatedPeerId &&
                    f.getValue("grantorSessionId") == authenticatedPeerSessionId) { "grantor_identity_mismatch" }
                return Result(frames(coordinator.onGrant(authenticatedPeerId, authenticatedPeerSessionId,
                    f.getValue("requestId") as String, f.getValue("grantId") as String)))
            }
            PeerV2Frame.Type.ACQUIRE_DENY -> {
                require(f.getValue("grantorId") == authenticatedPeerId &&
                    f.getValue("grantorSessionId") == authenticatedPeerSessionId) { "grantor_identity_mismatch" }
                val pending = coordinator.currentIntent()
                val cancel = if (pending?.requestId == f.getValue("requestId")) coordinator.cancelIntent() else emptyList()
                return Result(frames(cancel))
            }
            PeerV2Frame.Type.ACQUIRE_START_ACK -> {
                require(f.getValue("grantorId") == authenticatedPeerId &&
                    f.getValue("grantorSessionId") == authenticatedPeerSessionId) { "grantor_identity_mismatch" }
                val ready = coordinator.onStartAck(authenticatedPeerId,
                    f.getValue("requestId") as String, f.getValue("grantId") as String)
                return Result(emptyList(), ready as? PassiveAcquisitionCoordinator.Event.ReadyToConnect)
            }
            else -> throw IllegalArgumentException("not_acquisition_message")
        }
    }

    fun cancel(): List<PeerV2Frame.Frame> = frames(coordinator.cancelIntent())

    private fun participantReply(event: PassiveAcquisitionCoordinator.Event, request: Map<String, Any?>): PeerV2Frame.Frame = when (event) {
        is PassiveAcquisitionCoordinator.Event.Grant -> PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_GRANT,
            identityFields(request) + mapOf("grantorId" to localInstallationId,
                "grantorSessionId" to localSessionId(), "grantId" to event.grantId))
        is PassiveAcquisitionCoordinator.Event.StartAck -> PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_START_ACK,
            startFields(request) + mapOf("grantorId" to localInstallationId,
                "grantorSessionId" to localSessionId(), "grantId" to event.grantId))
        is PassiveAcquisitionCoordinator.Event.Deny -> PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_DENY,
            identityFields(request) + mapOf("grantorId" to localInstallationId,
                "grantorSessionId" to localSessionId(), "reason" to event.reason.wireValue))
        else -> error("unexpected_participant_event")
    }

    private fun frames(events: List<PassiveAcquisitionCoordinator.Event>): List<PeerV2Frame.Frame> = events.map { event ->
        when (event) {
            is PassiveAcquisitionCoordinator.Event.Request -> PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_REQUEST,
                mapOf("requestId" to event.requestId, "headsetId" to event.headsetId,
                    "requesterId" to localInstallationId, "requesterSessionId" to event.requesterSessionId,
                    "membership" to event.membership, "membershipDigest" to event.membershipDigest,
                    "snapshotRevisions" to event.snapshotRevisions))
            is PassiveAcquisitionCoordinator.Event.Start -> PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_START,
                mapOf("requestId" to event.requestId, "headsetId" to event.headsetId,
                    "requesterId" to event.requesterId, "requesterSessionId" to event.requesterSessionId,
                    "membership" to event.membership, "membershipDigest" to event.membershipDigest, "grantorId" to event.grantorId,
                    "grantorSessionId" to event.grantorSessionId, "grantId" to event.grantId))
            is PassiveAcquisitionCoordinator.Event.Cancel -> PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_CANCEL,
                mapOf("requestId" to event.requestId, "requesterId" to event.requesterId,
                    "requesterSessionId" to event.requesterSessionId, "membershipDigest" to event.membershipDigest))
            is PassiveAcquisitionCoordinator.Event.ReadyToConnect -> error("ready_event_is_local_only")
            is PassiveAcquisitionCoordinator.Event.Grant,
            is PassiveAcquisitionCoordinator.Event.Deny,
            is PassiveAcquisitionCoordinator.Event.StartAck -> error("reply_event_is_peer_specific")
        }
    }

    private fun identityFields(request: Map<String, Any?>): Map<String, Any?> = mapOf(
        "requestId" to request.getValue("requestId"), "headsetId" to request.getValue("headsetId"),
        "requesterId" to request.getValue("requesterId"),
        "requesterSessionId" to request.getValue("requesterSessionId"),
        "membership" to request.getValue("membership"),
        "membershipDigest" to request.getValue("membershipDigest"),
    )

    private fun startFields(request: Map<String, Any?>): Map<String, Any?> = mapOf(
        "requestId" to request.getValue("requestId"), "headsetId" to request.getValue("headsetId"),
        "requesterId" to request.getValue("requesterId"),
        "requesterSessionId" to request.getValue("requesterSessionId"),
        "membership" to request.getValue("membership"),
        "membershipDigest" to request.getValue("membershipDigest"),
    )

    private fun longMap(value: Any?): Map<String, Long> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("invalid_snapshot_revisions")
        return map.entries.associate { (key, revision) ->
            (key as? String ?: throw IllegalArgumentException("invalid_snapshot_revision_key")) to
                (revision as? Long ?: throw IllegalArgumentException("invalid_snapshot_revision"))
        }
    }

    private val PassiveAcquisitionCoordinator.Denial.wireValue: String
        get() = when (this) {
            PassiveAcquisitionCoordinator.Denial.EXPIRED -> "UNKNOWN"
            else -> name
        }
}
