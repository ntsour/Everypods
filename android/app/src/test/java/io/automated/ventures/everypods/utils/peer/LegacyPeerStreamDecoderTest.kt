package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.CrossDevicePackets
import org.junit.Assert.*
import org.junit.Test

class LegacyPeerStreamDecoderTest {
    private val connected = CrossDevicePackets.AIRPODS_CONNECTED.packet
    private val released = CrossDevicePackets.AIRPODS_DISCONNECTED.packet
    private val request = CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet
    private fun assertPackets(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun `announcement and status request survive every split point and single-byte reads`() {
        val wire = connected + request + released
        for (split in 0..wire.size) {
            val decoder = LegacyPeerStreamDecoder()
            assertPackets(listOf(connected, request, released),
                decoder.feed(wire.copyOfRange(0, split)) + decoder.feed(wire.copyOfRange(split, wire.size)))
        }
        val decoder = LegacyPeerStreamDecoder()
        assertPackets(listOf(connected, request, released), wire.flatMap { decoder.feed(byteArrayOf(it)) })
    }

    @Test fun `all legacy controls can coalesce without disappearing`() {
        val packets = CrossDevicePackets.entries.filter { it != CrossDevicePackets.AIRPODS_DATA_HEADER }.map { it.packet }
        assertPackets(packets, LegacyPeerStreamDecoder().feed(packets.fold(byteArrayOf()) { all, p -> all + p }))
    }

    @Test fun `relay payload control-like bytes are never interpreted as status messages`() {
        val payload = byteArrayOf(4, 0, 4, 0, 4, 0) + connected + request + ByteArray(8)
        assertEquals(22, payload.size)
        val relay = CrossDevicePackets.AIRPODS_DATA_HEADER.packet + payload
        val wire = request + relay + released
        for (split in 0..wire.size) {
            val decoder = LegacyPeerStreamDecoder()
            assertPackets(listOf(request, relay, released), decoder.feed(wire.copyOfRange(0, split)) +
                decoder.feed(wire.copyOfRange(split, wire.size)))
        }
    }

    @Test fun `battery ANC and ear payloads preserve boundaries before controls`() {
        for ((type, length) in listOf(4 to 22, 6 to 8, 9 to 11)) {
            val payload = byteArrayOf(4, 0, 4, 0, type.toByte(), 0) + ByteArray(length - 6)
            val relay = CrossDevicePackets.AIRPODS_DATA_HEADER.packet + payload
            val decoder = LegacyPeerStreamDecoder()
            assertPackets(listOf(relay, connected), (relay + connected).flatMap { decoder.feed(byteArrayOf(it)) })
            assertPackets(listOf(payload, request), LegacyPeerStreamDecoder().feed(payload + request))
        }
    }

    @Test fun `new socket cannot inherit old fragmented message`() {
        assertTrue(LegacyPeerStreamDecoder().feed(connected.copyOfRange(0, 2)).isEmpty())
        assertPackets(listOf(released), LegacyPeerStreamDecoder().feed(released))
    }

    @Test fun `unknown payload remains opaque and decoder memory is bounded`() {
        val opaque = byteArrayOf(9, 8, 7, 6) + connected + request
        assertPackets(listOf(opaque), LegacyPeerStreamDecoder().feed(opaque))
        assertThrows(IllegalArgumentException::class.java) { LegacyPeerStreamDecoder().feed(ByteArray(4097)) }
    }
}
