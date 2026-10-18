package io.automated.ventures.everypods.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayPacketTest {
    private fun pkt(seq: Long = 7, payload: ByteArray = byteArrayOf(1, 2, 3)) =
        RelayPacket(flags = RelayPacket.FLAG_SILENCE, seq = seq, timestamp = seq / 2, payload = payload)

    @Test fun roundTrip() {
        val p = pkt()
        val bytes = p.encode()
        assertEquals(RelayPacket.HEADER_SIZE + 3, bytes.size)
        assertEquals(p, RelayPacket.decode(bytes))
    }

    @Test fun largeSeqAndMaxPayloadRoundTrip() {
        val p = pkt(seq = Long.MAX_VALUE - 1, payload = ByteArray(RelayPacket.MAX_PAYLOAD) { it.toByte() })
        assertEquals(p, RelayPacket.decode(p.encode()))
    }

    @Test fun headerLayoutIsBigEndian() {
        val b = RelayPacket(seq = 0x0102030405060708, timestamp = 1, payload = ByteArray(0)).encode()
        assertArrayEquals(byteArrayOf(1, 1, 0, 2, 1, 2, 3, 4, 5, 6, 7, 8), b.copyOfRange(0, 12))
    }

    @Test fun rejectsMalformed() {
        val good = pkt().encode()
        fun bad(b: ByteArray) = assertThrows(MalformedPacketException::class.java) { RelayPacket.decode(b) }
        bad(ByteArray(0))
        bad(good.copyOf(RelayPacket.HEADER_SIZE - 1))
        bad(good.copyOf(good.size - 1)) // truncated payload
        bad(good + 0) // trailing byte
        bad(good.copyOf().also { it[0] = 2 }) // version
        bad(good.copyOf().also { it[1] = 9 }) // codec
        bad(good.copyOf().also { it[3] = 3 }) // channels
        bad(good.copyOf().also { it[4] = 0x80.toByte() }) // negative seq
        bad(good.copyOf().also { it[20] = 0xff.toByte(); it[21] = 0xff.toByte() }) // huge length
    }

    @Test fun constructorRejectsOversizePayload() {
        assertThrows(IllegalArgumentException::class.java) { pkt(payload = ByteArray(RelayPacket.MAX_PAYLOAD + 1)) }
    }
}
