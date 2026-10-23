/* Copyright (C) 2026 EveryPods contributors. GPL-3.0.
 * DIRCON wire format adapted from QZ/qdomyos-zwift contributors:
 * https://github.com/cagnulein/qdomyos-zwift/tree/master/src/devices/dircon */
package io.automated.ventures.everypods.bluetooth.hrm.dircon

import java.util.UUID
import java.nio.ByteBuffer

/** Server-side subset: a genuine HR sensor, with no invented trainer metrics. */
object DirconProtocol {
    const val HEART_RATE = 0x180D
    const val MEASUREMENT = 0x2A37
    const val MAX_PAYLOAD = 4096
    data class Frame(val id: Int, val sequence: Int, val code: Int = 0, val payload: ByteArray = byteArrayOf()) {
        fun encode(): ByteArray {
            require(payload.size <= MAX_PAYLOAD)
            return byteArrayOf(1, id.toByte(), sequence.toByte(), code.toByte(),
                (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        }
    }
    data class Reply(val frame: Frame, val subscription: Boolean? = null)
    fun uuid(short: Int): ByteArray {
        val u = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }
    private val services = mapOf(
        HEART_RATE to listOf(MEASUREMENT to 4, 0x2A38 to 1),
        0x180A to listOf(0x2A29 to 1, 0x2A24 to 1)
    )
    private val reads = mapOf(0x2A38 to byteArrayOf(0), 0x2A29 to "EveryPods".toByteArray(),
        0x2A24 to "AirPods heart rate bridge".toByteArray())

    fun respond(request: Frame): Reply {
        fun reply(code: Int = 0, body: ByteArray = byteArrayOf(), subscribed: Boolean? = null) =
            Reply(Frame(request.id, request.sequence, code, body), subscribed)
        if (request.code != 0) return reply(2)
        val p = request.payload
        val id = if (p.size >= 16) services.keys.plus(services.values.flatten().map { it.first })
            .firstOrNull { p.copyOfRange(0, 16).contentEquals(uuid(it)) } else null
        return when (request.id) {
            1 -> if (p.isNotEmpty()) reply(2) else reply(body = services.keys.fold(byteArrayOf()) { b, u -> b + uuid(u) })
            2 -> when {
                p.size != 16 -> reply(2)
                !services.containsKey(id) -> reply(3)
                else -> reply(body = uuid(id!!) + services.getValue(id).fold(byteArrayOf()) { b, (u, flags) -> b + uuid(u) + flags.toByte() })
            }
            3 -> when {
                p.size != 16 -> reply(2)
                id == null -> reply(4)
                !reads.containsKey(id) -> reply(5)
                else -> reply(body = uuid(id) + reads.getValue(id))
            }
            4 -> reply(if (id == null) 4 else 5) // No writable characteristics.
            5 -> when {
                p.size != 17 || p[16].toInt() !in 0..1 -> reply(2)
                id == null -> reply(4)
                id != MEASUREMENT -> reply(5)
                else -> reply(body = uuid(id), subscribed = p[16].toInt() == 1)
            }
            7 -> if (p.isEmpty()) reply() else reply(2) // QZ-compatible keepalive.
            else -> reply(1)
        }
    }
    fun measurement(bpm: Int): ByteArray {
        require(bpm in 1..255)
        return Frame(6, 0, payload = uuid(MEASUREMENT) + byteArrayOf(0, bpm.toByte())).encode()
    }

    class Decoder {
        private var pending = byteArrayOf()
        val hasPendingBytes: Boolean get() = pending.isNotEmpty()
        fun feed(bytes: ByteArray): List<Frame> {
            require(bytes.size <= 64 * 1024) { "input_chunk_too_large" }
            val data = pending + bytes
            pending = byteArrayOf()
            var offset = 0
            val out = mutableListOf<Frame>()
            while (data.size - offset >= 6) {
                require(data[offset].toInt() == 1) { "unsupported_protocol" }
                val size = ((data[offset + 4].toInt() and 255) shl 8) or (data[offset + 5].toInt() and 255)
                require(size <= MAX_PAYLOAD) { "oversized_request" }
                if (data.size - offset < 6 + size) break
                out += Frame(data[offset + 1].toInt() and 255, data[offset + 2].toInt() and 255,
                    data[offset + 3].toInt() and 255, data.copyOfRange(offset + 6, offset + 6 + size))
                offset += 6 + size
            }
            pending = data.copyOfRange(offset, data.size)
            require(pending.size <= MAX_PAYLOAD + 6) { "incomplete_frame_too_large" }
            return out
        }
    }
}
