package io.automated.ventures.everypods.utils.peer

/** Length-delimited framing for the Android peer protocol v2 endpoint. */
object PeerV2Frame {
    const val VERSION = 2
    const val HEADER_SIZE = 10
    const val MAX_PAYLOAD_SIZE = 4096
    const val MAX_FRAME_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE

    private val magic = byteArrayOf('E'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte())

    enum class Type(val wireId: Int) {
        HELLO(1), SNAPSHOT(2), PING(3), PONG(4), ACQUIRE_REQUEST(5), ACQUIRE_GRANT(6),
        ACQUIRE_DENY(7), ACQUIRE_CANCEL(8), RELAY_DATA(9), ACQUIRE_START(10), ACQUIRE_START_ACK(11);

        companion object {
            fun fromWireId(id: Int): Type = entries.firstOrNull { it.wireId == id }
                ?: throw IllegalArgumentException("unknown_type")
        }
    }

    class Frame(val type: Type, payload: ByteArray) {
        private val payloadBytes = payload.copyOf()
        val payload: ByteArray get() = payloadBytes.copyOf()

        fun encode(): ByteArray {
            require(payloadBytes.size <= MAX_PAYLOAD_SIZE) { "oversized_payload" }
            val size = payloadBytes.size
            return magic + byteArrayOf(VERSION.toByte(), type.wireId.toByte(), (size ushr 24).toByte(),
                (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + payloadBytes
        }
    }

    /** Supports fragmented/coalesced reads while retaining no more than one maximum frame. */
    class Decoder {
        private var pending = ByteArray(0)
        private var expectedFrameSize: Int? = null
        internal val bufferedByteCount: Int get() = pending.size

        fun feed(chunk: ByteArray): List<Frame> {
            val decoded = mutableListOf<Frame>()
            var offset = 0
            while (offset < chunk.size) {
                if (pending.isEmpty() && expectedFrameSize == null && chunk.size - offset >= HEADER_SIZE) {
                    val size = parseHeader(chunk, offset)
                    if (chunk.size - offset >= size) {
                        decoded += decodeFrame(chunk, offset, size)
                        offset += size
                    } else {
                        pending = chunk.copyOfRange(offset, chunk.size)
                        expectedFrameSize = size
                        offset = chunk.size
                    }
                    continue
                }
                val target = expectedFrameSize ?: HEADER_SIZE
                val copied = minOf(target - pending.size, chunk.size - offset)
                pending += chunk.copyOfRange(offset, offset + copied)
                offset += copied
                if (expectedFrameSize == null && pending.size == HEADER_SIZE) expectedFrameSize = parseHeader(pending, 0)
                val completeSize = expectedFrameSize
                if (completeSize != null && pending.size == completeSize) {
                    decoded += decodeFrame(pending, 0, completeSize)
                    pending = ByteArray(0)
                    expectedFrameSize = null
                }
            }
            check(pending.size <= MAX_FRAME_SIZE) { "decoder_buffer_overflow" }
            return decoded
        }

        private fun parseHeader(bytes: ByteArray, offset: Int): Int {
            require(bytes.size - offset >= HEADER_SIZE) { "incomplete_header" }
            for (index in magic.indices) require(bytes[offset + index] == magic[index]) { "invalid_magic" }
            require((bytes[offset + 4].toInt() and 0xff) == VERSION) { "unsupported_version" }
            Type.fromWireId(bytes[offset + 5].toInt() and 0xff)
            val payloadSize = ((bytes[offset + 6].toLong() and 0xff) shl 24) or
                ((bytes[offset + 7].toLong() and 0xff) shl 16) or
                ((bytes[offset + 8].toLong() and 0xff) shl 8) or (bytes[offset + 9].toLong() and 0xff)
            require(payloadSize <= MAX_PAYLOAD_SIZE) { "oversized_payload" }
            return HEADER_SIZE + payloadSize.toInt()
        }

        private fun decodeFrame(bytes: ByteArray, offset: Int, size: Int): Frame = Frame(
            Type.fromWireId(bytes[offset + 5].toInt() and 0xff),
            bytes.copyOfRange(offset + HEADER_SIZE, offset + size),
        )
    }
}
