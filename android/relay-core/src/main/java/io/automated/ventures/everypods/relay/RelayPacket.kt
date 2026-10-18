package io.automated.ventures.everypods.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned media packet (v1), big-endian. The header is sent in clear and authenticated as
 * AEAD associated data; the payload is encrypted.
 *
 * ```
 * 0  version   u8   (= 1)
 * 1  codec     u8   (1 = Opus, 0 = PCM16 diagnostic)
 * 2  flags     u8   (bit0 = discontinuity/flush, bit1 = silence, bit2 = keyframe-like restart)
 * 3  channels  u8   (1 or 2)
 * 4  seq       u64  (monotonic per session key; also the AEAD nonce counter)
 * 12 timestamp u64  (sender media clock in 48 kHz sample frames since session start)
 * 20 length    u16  (payload length, ≤ MAX_PAYLOAD)
 * 22 payload
 * ```
 */
data class RelayPacket(
    val version: Int = VERSION,
    val codec: Int = CODEC_OPUS,
    val flags: Int = 0,
    val channels: Int = RelayAudioFormat.CHANNELS,
    val seq: Long,
    val timestamp: Long,
    val payload: ByteArray,
) {
    init {
        require(version in 0..255 && codec in 0..255 && flags in 0..255) { "u8 field out of range" }
        require(channels in 1..2) { "channels" }
        require(seq >= 0 && timestamp >= 0) { "negative seq/timestamp" }
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
    }

    fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        .put(version.toByte()).put(codec.toByte()).put(flags.toByte()).put(channels.toByte())
        .putLong(seq).putLong(timestamp).putShort(payload.size.toShort()).array()

    fun encode(): ByteArray = header() + payload

    override fun equals(other: Any?): Boolean = other is RelayPacket && version == other.version &&
        codec == other.codec && flags == other.flags && channels == other.channels &&
        seq == other.seq && timestamp == other.timestamp && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (seq xor timestamp).hashCode() * 31 + payload.contentHashCode()

    companion object {
        const val VERSION = 1
        const val CODEC_PCM16 = 0
        const val CODEC_OPUS = 1
        const val FLAG_DISCONTINUITY = 0x01
        const val FLAG_SILENCE = 0x02
        const val FLAG_RESTART = 0x04
        const val HEADER_SIZE = 22
        /** Fits one UDP datagram on a 1500-byte MTU with IPv6+UDP and the 16-byte GCM tag. */
        const val MAX_PAYLOAD = 1400
        private val KNOWN_CODECS = setOf(CODEC_PCM16, CODEC_OPUS)

        /** Parses a header + [payloadBytes]. Throws [MalformedPacketException] on any inconsistency. */
        fun decode(bytes: ByteArray): RelayPacket {
            if (bytes.size < HEADER_SIZE) throw MalformedPacketException("short packet ${bytes.size}")
            val h = parseHeader(bytes)
            if (bytes.size != HEADER_SIZE + h.length) throw MalformedPacketException("length mismatch")
            return h.toPacket(bytes.copyOfRange(HEADER_SIZE, bytes.size))
        }

        internal fun parseHeader(bytes: ByteArray): Header {
            if (bytes.size < HEADER_SIZE) throw MalformedPacketException("short header")
            val b = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val version = b.get().toInt() and 0xff
            val codec = b.get().toInt() and 0xff
            val flags = b.get().toInt() and 0xff
            val channels = b.get().toInt() and 0xff
            val seq = b.long
            val ts = b.long
            val len = b.short.toInt() and 0xffff
            if (version != VERSION) throw MalformedPacketException("unsupported version $version")
            if (codec !in KNOWN_CODECS) throw MalformedPacketException("unknown codec $codec")
            if (channels !in 1..2) throw MalformedPacketException("bad channels $channels")
            if (seq < 0 || ts < 0) throw MalformedPacketException("negative seq/ts")
            if (len > MAX_PAYLOAD) throw MalformedPacketException("payload too large $len")
            return Header(version, codec, flags, channels, seq, ts, len)
        }
    }

    internal data class Header(
        val version: Int, val codec: Int, val flags: Int, val channels: Int,
        val seq: Long, val timestamp: Long, val length: Int,
    ) {
        fun toPacket(payload: ByteArray) = RelayPacket(version, codec, flags, channels, seq, timestamp, payload)
    }
}

class MalformedPacketException(msg: String) : Exception(msg)
