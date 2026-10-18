package io.automated.ventures.everypods.relay

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** X25519 via the platform "XDH" provider (Android API 33+, JDK 11+). Raw 32-byte public keys. */
object X25519 {
    private val X509_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    )

    fun generate(): KeyPair = KeyPairGenerator.getInstance("XDH")
        .apply { initialize(NamedParameterSpec.X25519) }.generateKeyPair()

    fun rawPublic(kp: KeyPair): ByteArray {
        val enc = kp.public.encoded
        require(enc.size == 44 && enc.copyOfRange(0, 12).contentEquals(X509_PREFIX)) { "unexpected X25519 encoding" }
        return enc.copyOfRange(12, 44)
    }

    fun agree(priv: PrivateKey, peerRawPublic: ByteArray): ByteArray {
        require(peerRawPublic.size == 32) { "X25519 public key must be 32 bytes" }
        val pub = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X509_PREFIX + peerRawPublic))
        val secret = try {
            KeyAgreement.getInstance("XDH").run { init(priv); doPhase(pub, true); generateSecret() }
        } catch (e: java.security.InvalidKeyException) {
            // Some providers (JDK) refuse low-order points themselves.
            throw SecurityException("X25519 rejected public key: ${e.message}")
        }
        // Reject the all-zero output of low-order points (RFC 7748 §6.1).
        if (secret.all { it == 0.toByte() }) throw SecurityException("X25519 low-order public key")
        return secret
    }
}

/** RFC 5869 HKDF with HMAC-SHA256. */
object Hkdf {
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256")); doFinal(ikm)
        }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal(); out.write(t); i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int) = expand(extract(salt, ikm), info, length)
}

enum class RelayRole(val sendDirection: Byte, val receiveDirection: Byte) {
    /** Xiaomi side: captures and sends media. */
    SOURCE(0x01, 0x02),
    /** Pixel side: plays media, sends control/stats back. */
    RECEIVER(0x02, 0x01),
}

/**
 * Per-session keys. Both sides feed the same transcript (both ephemeral public keys, and optionally
 * both pinned static identity keys) and the DH outputs; HKDF yields one key per direction.
 */
class RelaySessionKeys(val sourceToReceiver: ByteArray, val receiverToSource: ByteArray) {
    companion object {
        private val INFO = "EveryPods relay v1 keys".toByteArray()

        fun derive(dhOutputs: List<ByteArray>, transcript: ByteArray): RelaySessionKeys {
            require(dhOutputs.isNotEmpty())
            val salt = MessageDigest.getInstance("SHA-256").digest(transcript)
            val okm = Hkdf.derive(salt, dhOutputs.reduce { a, b -> a + b }, INFO, 64)
            return RelaySessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
        }

        /** Canonical transcript: source ephemeral ‖ receiver ephemeral (‖ source static ‖ receiver static). */
        fun transcript(sourceEph: ByteArray, receiverEph: ByteArray, sourceStatic: ByteArray? = null, receiverStatic: ByteArray? = null) =
            "EPRELAY1".toByteArray() + sourceEph + receiverEph + (sourceStatic ?: ByteArray(0)) + (receiverStatic ?: ByteArray(0))
    }
}

/**
 * Sliding anti-replay window (RFC 4303 style) over 64-bit sequence numbers.
 * Accepts each seq at most once, tolerating reorder within [size] packets.
 */
class ReplayWindow(val size: Int = 1024) {
    private val bits = java.util.BitSet(size)
    var highest = -1L
        private set

    /** Non-mutating check; call [commit] only after the AEAD tag verified. */
    fun check(seq: Long): Boolean {
        if (seq < 0) return false
        if (seq > highest) return true
        val offset = highest - seq
        if (offset >= size) return false
        return !bits[(seq % size).toInt()]
    }

    fun commit(seq: Long) {
        if (seq > highest) {
            val shift = if (highest < 0) size.toLong() else seq - highest
            if (shift >= size) bits.clear() else {
                var s = highest + 1
                while (s <= seq) { bits.clear((s % size).toInt()); s++ }
            }
            highest = seq
        }
        bits.set((seq % size).toInt())
    }
}

sealed class OpenResult {
    data class Ok(val packet: RelayPacket) : OpenResult()
    data class Rejected(val reason: String) : OpenResult()
}

/**
 * AES-256-GCM sealing of [RelayPacket]s. Nonce = direction(1) ‖ 0x000000 ‖ seq(8); the clear
 * header is AAD. A sender must never reuse a seq under one key: [seal] enforces monotonic seq.
 *
 * Wire datagram = header(22, length = plaintext length) ‖ ciphertext ‖ tag(16).
 */
class RelayAead(keys: RelaySessionKeys, private val role: RelayRole, replayWindow: Int = 1024) {
    private val sendKey = SecretKeySpec(if (role == RelayRole.SOURCE) keys.sourceToReceiver else keys.receiverToSource, "AES")
    private val recvKey = SecretKeySpec(if (role == RelayRole.SOURCE) keys.receiverToSource else keys.sourceToReceiver, "AES")
    private val window = ReplayWindow(replayWindow)
    private var lastSentSeq = -1L

    fun seal(packet: RelayPacket): ByteArray {
        require(packet.seq > lastSentSeq) { "seq must increase (nonce reuse)" }
        lastSentSeq = packet.seq
        val header = packet.header()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, nonce(role.sendDirection, packet.seq)))
        c.updateAAD(header)
        return header + c.doFinal(packet.payload)
    }

    fun open(datagram: ByteArray): OpenResult {
        val h = try { RelayPacket.parseHeader(datagram) } catch (e: MalformedPacketException) {
            return OpenResult.Rejected("malformed: ${e.message}")
        }
        if (datagram.size != RelayPacket.HEADER_SIZE + h.length + TAG_BYTES) return OpenResult.Rejected("length mismatch")
        if (!window.check(h.seq)) return OpenResult.Rejected("replay or too old seq=${h.seq}")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(TAG_BITS, nonce(role.receiveDirection, h.seq)))
        c.updateAAD(datagram, 0, RelayPacket.HEADER_SIZE)
        val plain = try {
            c.doFinal(datagram, RelayPacket.HEADER_SIZE, datagram.size - RelayPacket.HEADER_SIZE)
        } catch (_: AEADBadTagException) {
            return OpenResult.Rejected("auth failed")
        }
        window.commit(h.seq)
        return OpenResult.Ok(h.toPacket(plain))
    }

    companion object {
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
        fun nonce(direction: Byte, seq: Long): ByteArray =
            java.nio.ByteBuffer.allocate(12).put(direction).put(0).put(0).put(0).putLong(seq).array()
    }
}
