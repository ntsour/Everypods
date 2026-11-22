/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils.peer

import java.io.ByteArrayOutputStream

/**
 * Splits payloads larger than one record into [PeerGattRecords.FRAG] payloads and reassembles them
 * (`innerType u8 ‖ msgId u16 ‖ index u16 ‖ count u16 ‖ bytes`, big-endian). One instance per link:
 * the link is an ordered byte stream, so fragments of a message arrive in index order. Not thread-safe.
 */
class PeerLeFragments {
    sealed interface Result {
        data object Pending : Result
        /** The message is incomplete or inconsistent and was dropped. */
        data class Rejected(val reason: String) : Result
        class Complete(val innerType: Int, val payload: ByteArray) : Result
    }

    private class Partial(val innerType: Int, val count: Int, val startedMs: Long) {
        val bytes = ByteArrayOutputStream()
        var next = 0
    }

    private val partials = HashMap<Int, Partial>()

    /** Feeds one FRAG payload. [nowMs] is a monotonic clock in milliseconds. */
    fun accept(payload: ByteArray, nowMs: Long): Result {
        expire(nowMs)
        if (payload.size < HEADER) return Result.Rejected("frag_truncated")
        val innerType = payload[0].toInt() and 0xFF
        val msgId = u16(payload, 1)
        val index = u16(payload, 3)
        val count = u16(payload, 5)
        if (innerType == 0 || innerType == PeerGattRecords.FRAG) return Result.Rejected("frag_inner_type")
        if (count < 1 || index >= count) return Result.Rejected("frag_index")
        val chunk = payload.size - HEADER
        var partial = partials[msgId]
        if (partial == null) {
            if (index != 0) return Result.Rejected("frag_out_of_order")
            if (partials.size >= MAX_PARTIALS) return Result.Rejected("frag_too_many")
            partial = Partial(innerType, count, nowMs)
            partials[msgId] = partial
        } else if (partial.innerType != innerType || partial.count != count || partial.next != index) {
            partials.remove(msgId)
            return Result.Rejected("frag_inconsistent")
        }
        if (partial.bytes.size() + chunk > MAX_MESSAGE) {
            partials.remove(msgId)
            return Result.Rejected("frag_too_large")
        }
        partial.bytes.write(payload, HEADER, chunk)
        partial.next++
        if (partial.next < partial.count) return Result.Pending
        partials.remove(msgId)
        return Result.Complete(partial.innerType, partial.bytes.toByteArray())
    }

    /** Drops every incomplete message whose first fragment is older than [TIMEOUT_MS]. */
    fun expire(nowMs: Long) {
        partials.values.removeAll { nowMs - it.startedMs > TIMEOUT_MS }
    }

    fun clear() = partials.clear()

    val pendingCount: Int get() = partials.size

    companion object {
        const val HEADER = 7
        const val MAX_MESSAGE = 160 * 1024
        const val CHUNK = PeerGattRecords.MAX_PAYLOAD - HEADER
        const val TIMEOUT_MS = 10_000L
        private const val MAX_PARTIALS = 4

        /**
         * FRAG payloads carrying [payload] as an [innerType] record, or null if it exceeds [MAX_MESSAGE].
         * An empty payload still yields one fragment.
         */
        fun split(innerType: Int, msgId: Int, payload: ByteArray): List<ByteArray>? {
            if (payload.size > MAX_MESSAGE || innerType !in 1..0xFF || innerType == PeerGattRecords.FRAG) return null
            val count = maxOf(1, (payload.size + CHUNK - 1) / CHUNK)
            return (0 until count).map { index ->
                val from = index * CHUNK
                val to = minOf(from + CHUNK, payload.size)
                val out = ByteArray(HEADER + (to - from))
                out[0] = innerType.toByte()
                out[1] = (msgId ushr 8).toByte(); out[2] = msgId.toByte()
                out[3] = (index ushr 8).toByte(); out[4] = index.toByte()
                out[5] = (count ushr 8).toByte(); out[6] = count.toByte()
                payload.copyInto(out, HEADER, from, to)
                out
            }
        }

        private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    }
}
