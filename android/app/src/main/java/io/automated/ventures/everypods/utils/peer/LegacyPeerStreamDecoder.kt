package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.CrossDevicePackets

/** RFCOMM is a byte stream, not a sequence of read-sized packets. One instance per socket. */
class LegacyPeerStreamDecoder {
    private var pending = byteArrayOf()
    private val controls = CrossDevicePackets.entries.filter { it != CrossDevicePackets.AIRPODS_DATA_HEADER }.map { it.packet }
    private val relay = CrossDevicePackets.AIRPODS_DATA_HEADER.packet
    private val aacp = byteArrayOf(4, 0, 4, 0)

    fun feed(bytes: ByteArray): List<ByteArray> {
        require(bytes.size + pending.size <= 4096) { "legacy_peer_buffer_overflow" }
        pending += bytes
        val packets = mutableListOf<ByteArray>()
        while (pending.isNotEmpty()) {
            if (pending.size < 4) break
            val header = pending.copyOfRange(0, 4)
            val length = when {
                controls.any { it.contentEquals(header) } -> 4
                relay.contentEquals(header) -> payloadLength(4)?.let { if (it < 0) -1 else it + 4 }
                aacp.contentEquals(header) -> payloadLength(0)
                else -> -1
            } ?: break
            // Unknown legacy payloads have no length field. Preserve the old opaque
            // read behavior; never search inside arbitrary payloads for control bytes.
            val size = if (length < 0) pending.size else length
            if (pending.size < size) break
            packets += pending.copyOfRange(0, size)
            pending = pending.copyOfRange(size, pending.size)
        }
        return packets
    }

    private fun payloadLength(offset: Int): Int? {
        val available = pending.size - offset
        if (available == 0) return null
        for (i in 0 until minOf(available, 4)) if (pending[offset + i] != aacp[i]) return -1
        if (available < 6) return null
        return when (pending[offset + 4].toInt() and 0xff) {
            4 -> 22 // battery
            6 -> 8  // ear detection
            9 -> 11 // control/ANC
            else -> -1
        }
    }
}
