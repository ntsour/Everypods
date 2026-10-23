/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.security

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stable Android Keystore identity and first-use verification for a configured peer. */
class RelayPeerIdentity(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun localPublicKey(): ByteArray = localKeyPair().public.encoded.copyOf()

    fun stableId(publicKey: ByteArray): String = sha256(publicKey).joinToString("") { "%02x".format(it) }

    fun isTrusted(peerId: String): Boolean = preferences.contains(peerKey(peerId))

    fun trustedPublicKey(peerId: String): ByteArray? = preferences.getString(peerKey(peerId), null)
        ?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Store only after the user confirms locally and the peer confirms over the bonded channel. */
    fun trustPeer(peerId: String, publicKey: ByteArray) {
        require(stableId(publicKey) == peerId) { "Peer identity does not match its stable ID" }
        preferences.edit().putString(peerKey(peerId), Base64.encodeToString(publicKey, Base64.NO_WRAP)).apply()
    }

    fun trustPeerAddress(macAddress: String, peerPublicKey: ByteArray): String {
        val peerId = stableId(peerPublicKey)
        trustPeer(peerId, peerPublicKey)
        preferences.edit().putString(addressKey(macAddress), peerId).apply()
        return peerId
    }

    fun peerIdForAddress(macAddress: String): String? = preferences.getString(addressKey(macAddress), null)

    fun forgetPeer(peerId: String) {
        val editor = preferences.edit().remove(peerKey(peerId))
        preferences.all.filter { it.key.startsWith(ADDRESS_PREFIX) && it.value == peerId }
            .keys.forEach(editor::remove)
        editor.apply()
    }

    fun forgetAddress(macAddress: String) {
        val address = addressKey(macAddress)
        val peerId = preferences.getString(address, null)
        preferences.edit().remove(address).apply()
        if (peerId != null) forgetPeer(peerId)
    }

    /** Derive a fresh session key from the device identity and per-connection nonce. */
    fun deriveSessionKey(peerPublicKeyBytes: ByteArray, sessionNonce: ByteArray): ByteArray {
        require(sessionNonce.size == NONCE_BYTES) { "Invalid relay session nonce" }
        val sharedSecret = deriveSharedSecret(peerPublicKeyBytes)
        return try {
            hkdfSha256(sharedSecret, sessionNonce, SESSION_INFO)
        } finally {
            sharedSecret.fill(0)
        }
    }

    private fun deriveSharedSecret(peerPublicKeyBytes: ByteArray): ByteArray {
        val peerPublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localKeyPair().private)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    /** Six-digit comparison value bound to both persistent identities and this handshake nonce. */
    fun verificationCode(localPublicKey: ByteArray, peerPublicKey: ByteArray, nonce: ByteArray): String {
        require(nonce.size == NONCE_BYTES) { "Invalid pairing nonce" }
        val sorted = listOf(localPublicKey, peerPublicKey).sortedWith { first, second ->
            compareUnsigned(first, second)
        }
        val identityContext = sha256(sorted[0] + sorted[1] + nonce)
        val secret = deriveSharedSecret(peerPublicKey)
        val value = try {
            val codeKey = hkdfSha256(secret, nonce, VERIFY_INFO)
            val mac = Mac.getInstance("HmacSHA256")
            try {
                mac.init(SecretKeySpec(codeKey, "HmacSHA256"))
                ByteBuffer.wrap(mac.doFinal(identityContext), 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
            } finally {
                codeKey.fill(0)
            }
        } finally {
            secret.fill(0)
        }
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun newPairingNonce(): ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)

    private fun localKeyPair(): java.security.KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing != null) {
            val publicKey = store.getCertificate(KEY_ALIAS)?.publicKey
                ?: error("Relay identity certificate is missing")
            return java.security.KeyPair(publicKey, existing as java.security.PrivateKey)
        }
        val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
        generator.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY,
            ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun peerKey(peerId: String): String {
        require(peerId.matches(Regex("[0-9a-f]{64}"))) { "Invalid stable peer identity" }
        return "trusted_peer_$peerId"
    }

    private fun addressKey(macAddress: String): String {
        require(macAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "Invalid Bluetooth address" }
        return ADDRESS_PREFIX + macAddress.uppercase().replace(':', '_')
    }

    private fun hkdfSha256(inputKey: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val pseudorandomKey = extract.doFinal(inputKey)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
                doFinal(info + byteArrayOf(1))
            }
        } finally {
            pseudorandomKey.fill(0)
        }
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (i in 0 until minOf(first.size, second.size)) {
            val difference = (first[i].toInt() and 0xff) - (second[i].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }

    companion object {
        private const val KEY_ALIAS = "everypods_audio_relay_identity_v1"
        private const val PREFERENCES = "audio_relay_trusted_peers_v1"
        private const val ADDRESS_PREFIX = "peer_address_"
        const val NONCE_BYTES = 32
        private val SESSION_INFO = "EveryPods authenticated relay signaling key v1".toByteArray()
        private val VERIFY_INFO = "EveryPods relay pairing verification v1".toByteArray()
    }
}

/** Ordered, replay-protected AES-GCM envelope for signaling sent over RFCOMM. */
class AuthenticatedSignalCodec(sessionKey: ByteArray, initiator: Boolean) {
    init { require(sessionKey.size == 32) { "Expected a 256-bit relay session key" } }
    private val initiatorToResponder = deriveKey(sessionKey, INITIATOR_TO_RESPONDER)
    private val responderToInitiator = deriveKey(sessionKey, RESPONDER_TO_INITIATOR)
    private val sendKey = if (initiator) initiatorToResponder else responderToInitiator
    private val receiveKey = if (initiator) responderToInitiator else initiatorToResponder
    private val sendNoncePrefix = noncePrefix(sendKey)
    private val receiveNoncePrefix = noncePrefix(receiveKey)
    private var sendCounter = 0L
    private var receiveCounter = 0L
    private var closed = false

    @Synchronized
    fun encode(payload: ByteArray): ByteArray {
        check(!closed) { "Signal codec is closed" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Signal is too large" }
        val counter = ++sendCounter
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte())
            .putLong(counter)
            .putInt(payload.size)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(TAG_BITS, nonce(sendNoncePrefix, counter)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }

    @Synchronized
    fun decode(frame: ByteArray): ByteArray {
        check(!closed) { "Signal codec is closed" }
        require(frame.size in (HEADER_BYTES + TAG_BYTES)..(HEADER_BYTES + MAX_PAYLOAD_BYTES + TAG_BYTES)) {
            "Invalid authenticated signal frame length"
        }
        val input = ByteBuffer.wrap(frame, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        require(input.get().toInt() and 0xff == VERSION) { "Unsupported signal protocol version" }
        val counter = input.long
        val payloadSize = input.int
        require(counter == receiveCounter + 1) { "Signal replay or sequence gap" }
        require(payloadSize in 0..MAX_PAYLOAD_BYTES && frame.size == HEADER_BYTES + payloadSize + TAG_BYTES) {
            "Invalid signal payload length"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(TAG_BITS, nonce(receiveNoncePrefix, counter)))
        cipher.updateAAD(frame, 0, HEADER_BYTES)
        val cleartext = cipher.doFinal(frame, HEADER_BYTES, frame.size - HEADER_BYTES)
        receiveCounter = counter
        return cleartext
    }

    @Synchronized
    fun close() {
        initiatorToResponder.fill(0)
        responderToInitiator.fill(0)
        sendNoncePrefix.fill(0)
        receiveNoncePrefix.fill(0)
        closed = true
    }

    companion object {
        private const val VERSION = 1
        private const val HEADER_BYTES = 13
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val MAX_PAYLOAD_BYTES = 128 * 1024
        private val INITIATOR_TO_RESPONDER = "relay control initiator to responder v1".toByteArray()
        private val RESPONDER_TO_INITIATOR = "relay control responder to initiator v1".toByteArray()

        private fun deriveKey(sessionKey: ByteArray, info: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(sessionKey, "HmacSHA256"))
                doFinal(info).copyOf(32)
            }

        private fun noncePrefix(key: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("nonce prefix".toByteArray()).copyOf(4)
        }

        private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(prefix).putLong(counter).array()
    }
}
