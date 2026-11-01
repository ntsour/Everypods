package io.automated.ventures.everypods.utils.peer

import java.nio.ByteBuffer
import java.io.DataInputStream
import java.io.InputStream
import java.util.UUID

/** Separate extension framing. Only a negotiated, session-bound transport may use it. */
object RemoteCaseEvidenceMessage {
    private val magic = byteArrayOf(0x45, 0x50, 0x43, 0x31) // EPC1
    private const val HEADER_BYTES = 5
    private const val UUID_BYTES = 16
    private const val MAX_AGE_MS = 5_000

    sealed interface Message {
        val session: UUID
        val headset: UUID
        val observation: UUID
        data class Hint(override val session: UUID, override val headset: UUID,
                        override val observation: UUID) : Message
        data class Challenge(override val session: UUID, override val headset: UUID,
                             override val observation: UUID, val nonce: UUID) : Message
        data class Confirmation(override val session: UUID, override val headset: UUID,
                                override val observation: UUID, val nonce: UUID,
                                val ageMs: Int, val available: Boolean) : Message
        data class Withdrawal(override val session: UUID, override val headset: UUID,
                              override val observation: UUID) : Message
    }

    fun encode(message: Message): ByteArray {
        val type = when (message) { is Message.Hint -> 1; is Message.Challenge -> 2; is Message.Confirmation -> 3; is Message.Withdrawal -> 4 }
        val extra = when (message) { is Message.Hint, is Message.Withdrawal -> 0; is Message.Challenge -> UUID_BYTES; is Message.Confirmation -> UUID_BYTES + 5 }
        val out = ByteBuffer.allocate(HEADER_BYTES + UUID_BYTES * 3 + extra)
        out.put(magic).put(type.toByte())
        out.putUuid(message.session).putUuid(message.headset).putUuid(message.observation)
        when (message) {
            is Message.Hint, is Message.Withdrawal -> Unit
            is Message.Challenge -> out.putUuid(message.nonce)
            is Message.Confirmation -> {
                require(message.ageMs in 0..MAX_AGE_MS) { "invalid_age" }
                out.putUuid(message.nonce).putInt(message.ageMs).put(if (message.available) 1 else 0)
            }
        }
        return out.array()
    }

    fun decode(bytes: ByteArray): Message {
        require(bytes.size >= HEADER_BYTES) { "truncated_header" }
        val input = ByteBuffer.wrap(bytes)
        for (expected in magic) require(input.get() == expected) { "invalid_magic" }
        val type = input.get().toInt() and 0xff
        val expectedSize = frameSize(type)
        require(bytes.size == expectedSize) { "invalid_length" }
        val session = input.getUuid()
        val headset = input.getUuid()
        val observation = input.getUuid()
        return when (type) {
            1 -> Message.Hint(session, headset, observation)
            2 -> Message.Challenge(session, headset, observation, input.getUuid())
            4 -> Message.Withdrawal(session, headset, observation)
            else -> {
                val nonce = input.getUuid()
                val age = input.int
                val flag = input.get().toInt() and 0xff
                require(age in 0..MAX_AGE_MS && flag in 0..1) { "invalid_confirmation" }
                Message.Confirmation(session, headset, observation, nonce, age, flag == 1)
            }
        }
    }

    /** Read exactly one bounded frame; EOF between frames is normal, partial frames fail. */
    fun read(input: InputStream): Message? {
        val first = input.read()
        if (first == -1) return null
        val header = ByteArray(HEADER_BYTES)
        header[0] = first.toByte()
        val data = DataInputStream(input)
        data.readFully(header, 1, HEADER_BYTES - 1)
        for (i in magic.indices) require(header[i] == magic[i]) { "invalid_magic" }
        val size = frameSize(header[4].toInt() and 0xff)
        val rest = ByteArray(size - HEADER_BYTES)
        data.readFully(rest)
        return decode(header + rest)
    }

    private fun frameSize(type: Int): Int = when (type) {
        1, 4 -> HEADER_BYTES + UUID_BYTES * 3
        2 -> HEADER_BYTES + UUID_BYTES * 4
        3 -> HEADER_BYTES + UUID_BYTES * 4 + 5
        else -> throw IllegalArgumentException("unknown_type")
    }

    private fun ByteBuffer.putUuid(value: UUID): ByteBuffer = putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)
    private fun ByteBuffer.getUuid(): UUID = UUID(long, long)
}
