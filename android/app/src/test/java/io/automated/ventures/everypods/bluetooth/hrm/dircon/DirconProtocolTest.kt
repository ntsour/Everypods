package io.automated.ventures.everypods.bluetooth.hrm.dircon

import org.junit.Assert.*
import org.junit.Test

class DirconProtocolTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val hr = hex("0000180d00001000800000805f9b34fb")
    private val measurement = hex("00002a3700001000800000805f9b34fb")
    @Test fun `discovery matches independent wire fixture and preserves request sequence`() {
        val request = DirconProtocol.Decoder().feed(hex("01012a000000")).single()
        val reply = DirconProtocol.respond(request)
        assertArrayEquals(hex("01012a000020") + hr + hex("0000180a00001000800000805f9b34fb"), reply.frame.encode())
        assertNull(reply.subscription)
    }
    @Test fun `characteristics enumerate notify measurement and readable other location`() {
        val r = DirconProtocol.respond(DirconProtocol.Frame(2, 3, payload = hr)).frame
        assertArrayEquals(hex("010203000032") + hr + measurement + byteArrayOf(4) +
            hex("00002a3800001000800000805f9b34fb01"), r.encode())
    }
    @Test fun `subscribe and unsubscribe acknowledge without inventing a sample`() {
        for (enabled in listOf(0, 1)) {
            val reply = DirconProtocol.respond(DirconProtocol.Frame(5, 17, payload = measurement + enabled.toByte()))
            assertEquals(enabled == 1, reply.subscription)
            assertArrayEquals(hex("010511000010") + measurement, reply.frame.encode())
        }
    }
    @Test fun `notifications encode unsigned BPM above 127 without invented RR or contact flags`() {
        assertArrayEquals(hex("010600000012") + measurement + hex("00c8"), DirconProtocol.measurement(200))
        assertThrows(IllegalArgumentException::class.java) { DirconProtocol.measurement(0) }
        assertThrows(IllegalArgumentException::class.java) { DirconProtocol.measurement(256) }
    }
    @Test fun `unsupported and malformed requests get bounded errors`() {
        assertEquals(3, DirconProtocol.respond(DirconProtocol.Frame(2, 1, payload = ByteArray(16))).frame.code)
        assertEquals(2, DirconProtocol.respond(DirconProtocol.Frame(2, 1, payload = hr + byteArrayOf(0))).frame.code)
        assertEquals(5, DirconProtocol.respond(DirconProtocol.Frame(3, 1, payload = measurement)).frame.code)
        assertEquals(5, DirconProtocol.respond(DirconProtocol.Frame(4, 1, payload = measurement + byteArrayOf(0))).frame.code)
        assertEquals(2, DirconProtocol.respond(DirconProtocol.Frame(5, 1, payload = measurement + byteArrayOf(2))).frame.code)
        assertEquals(1, DirconProtocol.respond(DirconProtocol.Frame(99, 1)).frame.code)
        assertEquals(0, DirconProtocol.respond(DirconProtocol.Frame(7, 1)).frame.code)
        assertEquals(0, DirconProtocol.respond(DirconProtocol.Frame(3, 1, payload = DirconProtocol.uuid(0x2A38))).frame.payload.last().toInt())
    }
    @Test fun `TCP fragments and consecutive frames preserve boundaries`() {
        val wire = hex("010509000011") + measurement + byteArrayOf(1)
        for (split in 1 until wire.size) {
            val d = DirconProtocol.Decoder()
            assertTrue(d.feed(wire.copyOfRange(0, split)).isEmpty())
            assertEquals(5, d.feed(wire.copyOfRange(split, wire.size)).single().id)
        }
        val combined = DirconProtocol.Decoder().feed(wire + hex("01010a000000"))
        assertEquals(listOf(5, 1), combined.map { it.id })
        assertEquals(listOf(9, 10), combined.map { it.sequence })
    }
    @Test fun `corrupt versions and oversized lengths terminate parser`() {
        assertThrows(IllegalArgumentException::class.java) { DirconProtocol.Decoder().feed(hex("020100000000")) }
        assertThrows(IllegalArgumentException::class.java) { DirconProtocol.Decoder().feed(hex("01010000ffff")) }
    }
}
