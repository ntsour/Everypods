/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

import android.content.Context
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Pairing upgrade and per-connection authenticated signaling for existing EveryPods peers. */
class RelayPeerSessionController(
    context: Context,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onState(mac: String, state: PeerState)
        fun onAuthenticatedMessage(mac: String, payload: ByteArray)
        fun onPeerTrusted(mac: String, peerId: String)
        fun onSecurityReady(mac: String, peerId: String, signalingInitiator: Boolean)
        fun onFailure(mac: String?, reason: String)
    }

    enum class Stage { LISTENING, CONNECTING, WAITING_FOR_PEER, VERIFYING, TRUSTED, CONNECTED, DISCONNECTED, FAILED }

    data class PeerState(
        val stage: Stage,
        val stableId: String? = null,
        val verificationCode: String? = null,
        val reason: String? = null,
    )

    private data class PairAttempt(
        val mac: String,
        val initiator: Boolean,
        val nonce: ByteArray,
        val localPublicKey: ByteArray,
        var peerPublicKey: ByteArray? = null,
        var stableId: String? = null,
        var code: String? = null,
        var codec: AuthenticatedSignalCodec? = null,
        var localConfirmed: Boolean = false,
        var remoteConfirmed: Boolean = false,
        var timeout: ScheduledFuture<*>? = null,
    )

    private data class ActiveSession(
        val peerId: String,
        val codec: AuthenticatedSignalCodec,
        val signalingInitiator: Boolean,
        var ready: Boolean,
    )

    private val identity = RelayPeerIdentity(context)
    private val states = ConcurrentHashMap<String, PeerState>()
    private val pairingRequested = ConcurrentHashMap.newKeySet<String>()
    private val pairing = ConcurrentHashMap<String, PairAttempt>()
    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "EveryPodsRelayPairingTimeout").apply { isDaemon = true }
    }
    private val hub = RelayBluetoothSignalHub(context, object : RelayBluetoothSignalHub.Listener {
        override fun onPeerConnected(mac: String, initiator: Boolean) {
            if (initiator) {
                if (pairingRequested.remove(mac)) beginPairing(mac)
                else beginTrustedSession(mac)
            } else {
                publish(mac, PeerState(Stage.WAITING_FOR_PEER))
            }
        }

        override fun onMessage(mac: String, payload: ByteArray) = receive(mac, payload)

        override fun onPeerDisconnected(mac: String) {
            clearPeer(mac)
            publish(mac, PeerState(Stage.DISCONNECTED))
        }

        override fun onFailure(mac: String?, reason: String) {
            if (mac != null) {
                pairingRequested.remove(mac)
                publish(mac, PeerState(Stage.FAILED, reason = reason))
            }
            listener.onFailure(mac, reason)
        }
    })

    fun startListening() = hub.startListening()

    fun state(mac: String): PeerState? = states[normalize(mac)]

    fun isTrusted(macAddress: String): Boolean = identity.peerIdForAddress(normalize(macAddress)) != null

    fun forgetPeer(macAddress: String) {
        val mac = normalize(macAddress)
        clearPeer(mac)
        identity.forgetAddress(mac)
        hub.disconnect(mac)
        publish(mac, PeerState(Stage.DISCONNECTED))
    }

    @Synchronized
    fun connectForPairing(macAddress: String) {
        val mac = normalize(macAddress)
        // Opening setup on the responding device must keep the challenge that
        // is already displayed on the initiating device.
        if (pairing.containsKey(mac) || !pairingRequested.add(mac)) return
        if (hub.isConnected(mac)) {
            pairingRequested.remove(mac)
            beginPairing(mac)
        } else {
            publish(mac, PeerState(Stage.CONNECTING))
            hub.connect(mac)
        }
    }

    @Synchronized
    fun connectForSession(macAddress: String) {
        val mac = normalize(macAddress)
        if (pairing.containsKey(mac) || mac in pairingRequested) return
        require(identity.peerIdForAddress(mac) != null) { "Verify this EveryPods peer before starting relay" }
        publish(mac, PeerState(Stage.CONNECTING))
        if (hub.isConnected(mac)) beginTrustedSession(mac) else hub.connect(mac)
    }

    /** The displayed code must match on both devices before the user confirms on each. */
    @Synchronized
    fun confirmPairing(macAddress: String) {
        val mac = normalize(macAddress)
        val attempt = checkNotNull(pairing[mac]) { "No active pairing verification" }
        check(attempt.code != null && attempt.peerPublicKey != null && attempt.codec != null) {
            "Wait for the peer identity to arrive"
        }
        if (!attempt.localConfirmed) {
            check(hub.send(mac, attempt.codec!!.encode(byteArrayOf(MSG_PAIR_CONFIRM.toByte())))) {
                "Could not send pairing confirmation"
            }
            attempt.localConfirmed = true
        }
        finishPairingIfConfirmed(attempt)
    }

    @Synchronized
    fun cancelPairing(macAddress: String) {
        val mac = normalize(macAddress)
        pairingRequested.remove(mac)
        val attempt = pairing.remove(mac) ?: return
        attempt.timeout?.cancel(false)
        attempt.codec?.close()
        publish(mac, PeerState(Stage.DISCONNECTED))
    }

    fun sendAuthenticated(macAddress: String, payload: ByteArray): Boolean {
        val mac = normalize(macAddress)
        val session = sessions[mac] ?: return false
        if (!session.ready) return false
        return hub.send(mac, session.codec.encode(payload))
    }

    @Synchronized
    private fun beginPairing(mac: String) {
        if (pairing.containsKey(mac)) return
        val attempt = PairAttempt(
            mac = mac,
            initiator = true,
            nonce = identity.newPairingNonce(),
            localPublicKey = identity.localPublicKey(),
        )
        pairing[mac]?.let { it.timeout?.cancel(false); it.codec?.close() }
        pairing[mac] = attempt
        attempt.timeout = executor.schedule({
            if (pairing.remove(mac, attempt)) {
                attempt.codec?.close()
                publish(mac, PeerState(Stage.FAILED, reason = "Pairing timed out. Try again."))
            }
        }, PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        publish(mac, PeerState(Stage.WAITING_FOR_PEER))
        if (!hub.send(mac, encodePairHello(attempt.localPublicKey, attempt.nonce))) {
            pairing.remove(mac, attempt)
            attempt.timeout?.cancel(false)
            publish(mac, PeerState(Stage.FAILED, reason = "Could not send the pairing request"))
        }
    }

    private fun beginResponderPairing(mac: String, peerPublicKey: ByteArray, nonce: ByteArray) {
        validatePublicKey(peerPublicKey)
        pairingRequested.remove(mac)
        val attempt = PairAttempt(
            mac = mac,
            initiator = false,
            nonce = nonce,
            localPublicKey = identity.localPublicKey(),
            peerPublicKey = peerPublicKey,
        )
        configureVerification(attempt)
        attempt.timeout = executor.schedule({
            if (pairing.remove(mac, attempt)) {
                attempt.codec?.close()
                publish(mac, PeerState(Stage.FAILED, reason = "Pairing timed out. Try again."))
            }
        }, PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pairing[mac]?.let { it.timeout?.cancel(false); it.codec?.close() }
        pairing[mac] = attempt
        if (!hub.send(mac, encodePairHello(attempt.localPublicKey, nonce))) {
            pairing.remove(mac, attempt)
            attempt.timeout?.cancel(false)
            attempt.codec?.close()
            publish(mac, PeerState(Stage.FAILED, reason = "Could not send the peer identity"))
        }
        publish(mac, PeerState(Stage.VERIFYING, attempt.stableId, attempt.code))
    }

    private fun beginTrustedSession(mac: String) {
        val peerId = identity.peerIdForAddress(mac)
        val peerPublicKey = peerId?.let(identity::trustedPublicKey)
        if (peerId == null || peerPublicKey == null) {
            publish(mac, PeerState(Stage.FAILED, reason = "Verify this EveryPods peer first"))
            return
        }
        val nonce = identity.newPairingNonce()
        val codec = AuthenticatedSignalCodec(identity.deriveSessionKey(peerPublicKey, nonce), initiator = true)
        sessions.put(mac, ActiveSession(peerId, codec, signalingInitiator = true, ready = false))?.codec?.close()
        publish(mac, PeerState(Stage.CONNECTING, peerId))
        if (!hub.send(mac, encodeSessionHello(identity.stableId(identity.localPublicKey()), nonce))) {
            sessions.remove(mac)?.codec?.close()
            publish(mac, PeerState(Stage.FAILED, peerId, reason = "Could not start authenticated signaling"))
        }
    }

    @Synchronized
    private fun receive(mac: String, payload: ByteArray) {
        try {
            when {
                isInitialMessage(payload) -> receiveInitial(mac, payload)
                pairing.containsKey(mac) -> receivePairingConfirmation(mac, payload)
                else -> receiveSessionMessage(mac, payload)
            }
        } catch (error: Exception) {
            pairing.remove(mac)?.let { it.timeout?.cancel(false); it.codec?.close() }
            sessions.remove(mac)?.codec?.close()
            publish(mac, PeerState(Stage.FAILED, reason = error.message ?: "Authenticated setup failed"))
            listener.onFailure(mac, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun receiveInitial(mac: String, payload: ByteArray) {
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC && input.get().toInt() and 0xff == VERSION) { "Invalid relay handshake" }
        when (input.get().toInt() and 0xff) {
            TYPE_PAIR_HELLO -> {
                val keySize = input.short.toInt() and 0xffff
                require(keySize in 64..512 && input.remaining() == keySize + RelayPeerIdentity.NONCE_BYTES) {
                    "Invalid pairing identity message"
                }
                val publicKey = ByteArray(keySize).also(input::get)
                val nonce = ByteArray(RelayPeerIdentity.NONCE_BYTES).also(input::get)
                val existing = pairing[mac]
                if (existing != null && existing.initiator && existing.nonce.contentEquals(nonce)) {
                    // Repeated identities must not reset an encrypted confirmation
                    // channel or its replay counters after a code is displayed.
                    if (existing.peerPublicKey != null) {
                        require(existing.peerPublicKey!!.contentEquals(publicKey)) { "Peer identity changed during pairing" }
                        return
                    }
                    existing.peerPublicKey = publicKey
                    configureVerification(existing)
                    publish(mac, PeerState(Stage.VERIFYING, existing.stableId, existing.code))
                } else if (existing != null && existing.initiator) {
                    validatePublicKey(publicKey)
                    // Both users may open setup together. The same identity wins
                    // on both devices, so they converge on one nonce and code.
                    if (RelayPairingCollision.keepLocalChallenge(existing.localPublicKey, publicKey)) return
                    beginResponderPairing(mac, publicKey, nonce)
                } else if (existing == null) {
                    beginResponderPairing(mac, publicKey, nonce)
                } else {
                    require(existing.peerPublicKey!!.contentEquals(publicKey)) { "Peer identity changed during pairing" }
                    if (!existing.nonce.contentEquals(nonce)) beginResponderPairing(mac, publicKey, nonce)
                }
            }
            TYPE_SESSION_HELLO -> receiveSessionHello(mac, input)
            else -> error("Unknown relay handshake message")
        }
        require(!input.hasRemaining()) { "Trailing relay handshake data" }
    }

    private fun receiveSessionHello(mac: String, input: ByteBuffer) {
        require(input.remaining() == ID_BYTES + RelayPeerIdentity.NONCE_BYTES) { "Invalid relay session hello" }
        val peerId = ByteArray(ID_BYTES).also(input::get).toString(StandardCharsets.US_ASCII)
        val nonce = ByteArray(RelayPeerIdentity.NONCE_BYTES).also(input::get)
        val peerPublicKey = checkNotNull(identity.trustedPublicKey(peerId)) { "Unverified peer identity" }
        val mappedPeerId = identity.peerIdForAddress(mac)
        require(mappedPeerId == null || mappedPeerId == peerId) { "Bluetooth peer identity changed" }
        val codec = AuthenticatedSignalCodec(identity.deriveSessionKey(peerPublicKey, nonce), initiator = false)
        sessions.put(mac, ActiveSession(peerId, codec, signalingInitiator = false, ready = true))?.codec?.close()
        require(hub.send(mac, codec.encode(byteArrayOf(MSG_SESSION_READY.toByte())))) { "Could not confirm authenticated signaling" }
        publish(mac, PeerState(Stage.CONNECTED, peerId))
        listener.onSecurityReady(mac, peerId, signalingInitiator = false)
    }

    private fun receivePairingConfirmation(mac: String, payload: ByteArray) {
        val attempt = checkNotNull(pairing[mac])
        val clear = checkNotNull(attempt.codec).decode(payload)
        require(clear.contentEquals(byteArrayOf(MSG_PAIR_CONFIRM.toByte()))) { "Invalid pairing confirmation" }
        attempt.remoteConfirmed = true
        finishPairingIfConfirmed(attempt)
    }

    private fun receiveSessionMessage(mac: String, payload: ByteArray) {
        val session = checkNotNull(sessions[mac]) { "No authenticated session" }
        val clear = session.codec.decode(payload)
        if (clear.contentEquals(byteArrayOf(MSG_SESSION_READY.toByte()))) {
            session.ready = true
            publish(mac, PeerState(Stage.CONNECTED, session.peerId))
            listener.onSecurityReady(mac, session.peerId, session.signalingInitiator)
        } else {
            require(session.ready) { "Peer sent data before signaling was ready" }
            listener.onAuthenticatedMessage(mac, clear)
        }
    }

    private fun configureVerification(attempt: PairAttempt) {
        val peerPublic = checkNotNull(attempt.peerPublicKey)
        validatePublicKey(peerPublic)
        attempt.stableId = identity.stableId(peerPublic)
        attempt.code = identity.verificationCode(attempt.localPublicKey, peerPublic, attempt.nonce)
        attempt.codec?.close()
        val key = identity.deriveSessionKey(peerPublic, attempt.nonce)
        attempt.codec = AuthenticatedSignalCodec(key, initiator = attempt.initiator)
        key.fill(0)
    }

    @Synchronized
    private fun finishPairingIfConfirmed(attempt: PairAttempt) {
        if (!attempt.localConfirmed || !attempt.remoteConfirmed) return
        val peerPublicKey = checkNotNull(attempt.peerPublicKey)
        val peerId = identity.trustPeerAddress(attempt.mac, peerPublicKey)
        attempt.timeout?.cancel(false)
        pairing.remove(attempt.mac, attempt)
        sessions.put(attempt.mac, ActiveSession(peerId, checkNotNull(attempt.codec), attempt.initiator, ready = true))
        publish(attempt.mac, PeerState(Stage.TRUSTED, peerId))
        listener.onPeerTrusted(attempt.mac, peerId)
    }

    private fun validatePublicKey(encoded: ByteArray) {
        require(encoded.size in 64..512) { "Invalid peer identity key size" }
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    private fun publish(mac: String, state: PeerState) {
        val normalized = normalize(mac)
        states[normalized] = state
        listener.onState(normalized, state)
    }

    private fun clearPeer(mac: String) {
        pairingRequested.remove(mac)
        pairing.remove(mac)?.let { it.timeout?.cancel(false); it.codec?.close() }
        sessions.remove(mac)?.codec?.close()
    }

    private fun normalize(mac: String): String = mac.uppercase()

    private fun isInitialMessage(payload: ByteArray): Boolean =
        payload.size >= 6 && ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int == MAGIC

    private fun encodePairHello(publicKey: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + 1 + 1 + 2 + publicKey.size + nonce.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION.toByte()).put(TYPE_PAIR_HELLO.toByte())
            .putShort(publicKey.size.toShort()).put(publicKey).put(nonce).array()

    private fun encodeSessionHello(peerId: String, nonce: ByteArray): ByteArray {
        val id = peerId.toByteArray(StandardCharsets.US_ASCII)
        require(id.size == ID_BYTES)
        return ByteBuffer.allocate(4 + 1 + 1 + id.size + nonce.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION.toByte()).put(TYPE_SESSION_HELLO.toByte()).put(id).put(nonce).array()
    }

    override fun close() {
        pairing.values.forEach { it.timeout?.cancel(false); it.codec?.close() }
        pairing.clear()
        sessions.values.forEach { it.codec.close() }
        sessions.clear()
        executor.shutdownNow()
        hub.close()
    }

    companion object {
        private const val MAGIC = 0x45505248 // EPRH
        private const val VERSION = 1
        private const val TYPE_PAIR_HELLO = 1
        private const val TYPE_SESSION_HELLO = 2
        private const val MSG_PAIR_CONFIRM = 1
        private const val MSG_SESSION_READY = 2
        private const val PAIRING_TIMEOUT_SECONDS = 60L
        private const val ID_BYTES = 64
    }
}
