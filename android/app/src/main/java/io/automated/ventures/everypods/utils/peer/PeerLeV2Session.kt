/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

/**
 * The V2 protocol (HELLO / SNAPSHOT / PING / PONG / ACQUIRE_*) over one phone to phone Bluetooth LE link.
 * Every V2 record carries exactly one [PeerV2Frame.Frame]. The LE central is the initiator and mirrors
 * the former RFCOMM client session; the peripheral mirrors `CrossDevice.handleV2ClientConnection`.
 *
 * Everything outside the protocol (identity, snapshots, authorization, the wire) is injected through [Hooks],
 * and time through [clock] and [tick], so the class has no Android or CrossDevice dependency. Methods are
 * synchronized; a failure ends the session once and reports through [Hooks.onEnded] (the LE link stays up).
 */
class PeerLeV2Session(
    private val central: Boolean,
    private val hooks: Hooks,
    private val clock: () -> Long,
) {
    interface Hooks {
        /** Sends one V2 record carrying [frame]; false if the link cannot take it. */
        fun send(frame: PeerV2Frame.Frame): Boolean
        /** True while the peer is a configured, coordination-authorized peer (checked for every frame). */
        fun isAuthorized(): Boolean
        fun localInstallationId(): String?
        fun localSessionId(): String?
        fun localSnapshot(): Map<String, Any?>
        fun newConnectionId(): String
        fun bindPeerIdentity(installationId: String)
        fun observeSnapshot(remoteSessionId: String, snapshot: Map<String, Any?>): Boolean
        fun markReady(remoteSessionId: String, acquisitionCapable: Boolean, intentCapable: Boolean = false)
        fun handleAcquisition(remoteSessionId: String, type: PeerV2Frame.Type, fields: Map<String, Any?>)
        /** The session ended ([reason] is a short code); called at most once, never after [end] by the owner. */
        fun onEnded(reason: String)
    }

    private val decoder = PeerV2Frame.Decoder()
    private var heartbeat = PeerHeartbeat()
    private var connectionId: String? = null
    private var remoteInstall: String? = null
    private var remoteSession: String? = null
    private var remoteAcquisitionCapable = false
    private var remoteIntentCapable = false
    private var initialSnapshot = false
    private var initialPong = false
    private var handshakeDeadlineMs: Long? = null
    private var nextHeartbeatMs: Long = clock() + HEARTBEAT_MS
    @Volatile private var endedFlag = false
    @Volatile private var readyFlag = false

    val isReady: Boolean get() = readyFlag && !endedFlag
    val isEnded: Boolean get() = endedFlag

    /** Central: sends HELLO and arms the handshake watchdog. Peripheral: waits for the peer's HELLO. */
    @Synchronized fun start() {
        if (endedFlag || !central) return
        guarded {
            val installation = hooks.localInstallationId() ?: error("identity_unavailable")
            val session = hooks.localSessionId() ?: error("session_unavailable")
            val id = hooks.newConnectionId()
            connectionId = id
            handshakeDeadlineMs = clock() + HANDSHAKE_TIMEOUT_MS
            send(PeerV2Message.encode(PeerV2Frame.Type.HELLO, mapOf(
                "installationId" to installation, "sessionId" to session,
                "capabilities" to CAPABILITIES, "initiatorId" to installation, "connectionId" to id,
            )))
        }
    }

    /** One V2 record payload (exactly one frame). */
    @Synchronized fun onRecord(payload: ByteArray) {
        if (endedFlag) return
        guarded {
            val frames = decoder.feed(payload)
            require(frames.size == 1) { "v2_record_not_one_frame" }
            handle(frames[0])
        }
    }

    /** Call about once a second: runs the handshake watchdog and the 10 s heartbeat. */
    @Synchronized fun tick() {
        if (endedFlag) return
        val now = clock()
        guarded {
            val deadline = handshakeDeadlineMs
            if (deadline != null && !initialPong && now >= deadline) error("handshake_timeout")
            if (now < nextHeartbeatMs) return@guarded
            nextHeartbeatMs = now + HEARTBEAT_MS
            if (remoteInstall == null) return@guarded
            when (val action = heartbeat.onInterval()) {
                is PeerHeartbeatAction.SendPing -> send(PeerV2Message.encode(PeerV2Frame.Type.PING, mapOf("nonce" to action.nonce)))
                PeerHeartbeatAction.AwaitResponse -> Unit
                PeerHeartbeatAction.TimedOut -> error("heartbeat_timeout")
            }
        }
    }

    /** Sends [frame] to the peer if the session is ready. */
    @Synchronized fun sendFrame(frame: PeerV2Frame.Frame): Boolean {
        if (!isReady) return false
        return hooks.send(frame)
    }

    /** Ends the session without calling [Hooks.onEnded] (the owner is tearing it down). */
    @Synchronized fun end() { endedFlag = true; readyFlag = false }

    private fun handle(frame: PeerV2Frame.Frame) {
        require(hooks.isAuthorized()) { "unauthorized_coordination_peer" }
        val message = PeerV2Message.decode(frame)
        val fields = message.fields
        if (!central && (remoteInstall == null || message.type == PeerV2Frame.Type.HELLO)) {
            require(message.type == PeerV2Frame.Type.HELLO) { "hello_required" }
            // A repeated HELLO is the central retrying its handshake: start over on this session.
            heartbeat = PeerHeartbeat()
            readyFlag = false
            initialPong = false
            remoteAcquisitionCapable = false
            remoteIntentCapable = false
            val install = fields.getValue("installationId") as String
            val session = fields.getValue("sessionId") as String
            val remoteCaps = (fields.getValue("capabilities") as List<*>).filterIsInstance<String>()
            val capable = "acquire-v1" in remoteCaps
            remoteIntentCapable = "acquire-v2" in remoteCaps
            require(fields.getValue("initiatorId") == install) { "invalid_initiator_identity" }
            val localInstall = hooks.localInstallationId() ?: error("identity_unavailable")
            val localSession = hooks.localSessionId() ?: error("session_unavailable")
            hooks.bindPeerIdentity(install)
            remoteInstall = install; remoteSession = session; remoteAcquisitionCapable = capable
            handshakeDeadlineMs = clock() + HANDSHAKE_TIMEOUT_MS
            send(PeerV2Message.encode(PeerV2Frame.Type.HELLO, mapOf(
                "installationId" to localInstall, "sessionId" to localSession, "capabilities" to CAPABILITIES,
                "initiatorId" to fields.getValue("initiatorId"), "connectionId" to fields.getValue("connectionId"),
            )))
            send(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, hooks.localSnapshot()))
            val nonce = heartbeat.startPing() ?: error("initial_ping_already_pending")
            send(PeerV2Message.encode(PeerV2Frame.Type.PING, mapOf("nonce" to nonce)))
            return
        }
        when (message.type) {
            PeerV2Frame.Type.HELLO -> {
                require(central && remoteInstall == null) { "duplicate_hello" }
                require(fields["initiatorId"] == hooks.localInstallationId() && fields["connectionId"] == connectionId) {
                    "v2_connection_identity_mismatch"
                }
                remoteInstall = fields.getValue("installationId") as String
                remoteSession = fields.getValue("sessionId") as String
                val remoteCaps = (fields.getValue("capabilities") as List<*>).filterIsInstance<String>()
                remoteAcquisitionCapable = "acquire-v1" in remoteCaps
                remoteIntentCapable = "acquire-v2" in remoteCaps
                hooks.bindPeerIdentity(remoteInstall!!)
            }
            PeerV2Frame.Type.SNAPSHOT -> {
                val session = remoteSession ?: error("hello_required")
                require(fields["sessionId"] == session) { "v2_session_mismatch" }
                require(hooks.observeSnapshot(session, fields)) { "v2_snapshot_not_authoritative" }
                if (central && !initialSnapshot) {
                    initialSnapshot = true
                    send(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, hooks.localSnapshot()))
                    val nonce = heartbeat.startPing() ?: error("initial_ping_already_pending")
                    send(PeerV2Message.encode(PeerV2Frame.Type.PING, mapOf("nonce" to nonce)))
                }
            }
            PeerV2Frame.Type.PING -> {
                val snapshot = hooks.localSnapshot()
                send(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, snapshot))
                send(PeerV2Message.encode(PeerV2Frame.Type.PONG, mapOf("nonce" to fields.getValue("nonce"), "snapshot" to snapshot)))
            }
            PeerV2Frame.Type.PONG -> {
                val nonce = fields.getValue("nonce") as String
                if (heartbeat.acknowledge(nonce)) {
                    val session = remoteSession ?: error("hello_required")
                    val snapshot = stringKeyed(fields.getValue("snapshot"))
                    require(snapshot["sessionId"] == session) { "v2_session_mismatch" }
                    require(hooks.observeSnapshot(session, snapshot)) { "v2_pong_snapshot_not_authoritative" }
                    initialPong = true
                    readyFlag = true
                    hooks.markReady(session, remoteAcquisitionCapable, remoteIntentCapable)
                }
            }
            PeerV2Frame.Type.ACQUIRE_REQUEST, PeerV2Frame.Type.ACQUIRE_GRANT, PeerV2Frame.Type.ACQUIRE_DENY,
            PeerV2Frame.Type.ACQUIRE_CANCEL, PeerV2Frame.Type.ACQUIRE_START, PeerV2Frame.Type.ACQUIRE_START_ACK -> {
                require(remoteAcquisitionCapable) { "peer_missing_acquire_v1" }
                val session = remoteSession ?: error("hello_required")
                hooks.handleAcquisition(session, message.type, fields)
            }
            else -> error("unsupported_v2_message")
        }
    }

    private fun send(frame: PeerV2Frame.Frame) = check(hooks.send(frame)) { "v2_writer_unavailable" }

    private fun stringKeyed(value: Any?): Map<String, Any?> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("invalid_v2_snapshot")
        return map.entries.associate { (key, item) ->
            (key as? String ?: throw IllegalArgumentException("invalid_v2_snapshot_key")) to item
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(e.message ?: e.javaClass.simpleName) }
    }

    private fun fail(reason: String) {
        if (endedFlag) return
        endedFlag = true
        readyFlag = false
        hooks.onEnded(reason)
    }

    companion object {
        const val HEARTBEAT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        val CAPABILITIES: List<String> get() = PeerV2Message.LOCAL_CAPABILITIES
    }
}
