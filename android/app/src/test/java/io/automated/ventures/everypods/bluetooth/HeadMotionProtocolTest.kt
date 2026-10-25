package io.automated.ventures.everypods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class HeadMotionProtocolTest {
    // Independent SensorDataWX wire fixture: field 1 sequence, field 7 command,
    // command field 1 service / field 3 payload. No production encoder is used.
    private fun frame(sequence: ByteArray, service: Int = 16, extra: ByteArray = byteArrayOf()): ByteArray {
        val payload = ByteArray(58)
        byteArrayOf(0xE8.toByte(), 3, 0x38, 0xFF.toByte(), 0x2C, 1).copyInto(payload, 20)
        byteArrayOf(0x2E, 0xFB.toByte(), 0x29, 9).copyInto(payload, 28)
        val body = byteArrayOf(8) + sequence + extra +
            byteArrayOf(0x3A, 0x3E, 8, service.toByte(), 0x1A, 0x3A) + payload
        return byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, body.size.toByte(), 0) + body
    }

    private fun decode(frame: ByteArray) = HeadMotionProtocol.decodeAll(frame).singleOrNull()

    @Test fun `sequence varint growth never shifts motion sample fields`() {
        val expected = HeadMotionProtocol.Sample(1000, -200, 300, -1234, 2345)
        listOf(byteArrayOf(0x7F), byteArrayOf(0x80.toByte(), 1),
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 1)).forEach {
            assertEquals(expected, decode(frame(it)))
        }
    }

    @Test fun `additional protobuf fields do not become acceleration bytes`() {
        val baseline = decode(frame(byteArrayOf(1)))
        assertEquals(baseline, decode(frame(byteArrayOf(1),
            extra = byteArrayOf(0x10, 2, 0x1A, 3, 9, 8, 7))))
    }

    @Test fun `only known motion services produce samples`() {
        for (service in listOf(14, 15, 16)) assertNotNull(decode(frame(byteArrayOf(1), service)))
        for (service in listOf(11, 19, 20, 84)) assertNull(decode(frame(byteArrayOf(1), service)))
    }

    @Test fun `batched motion commands retain every sample in wire order`() {
        val first = frame(byteArrayOf(1))
        val second = frame(byteArrayOf(1)).apply {
            this[size - 58 + 28] = 100
            this[size - 58 + 29] = 0
        }
        val body = first.copyOfRange(12, first.size) + second.copyOfRange(14, second.size)
        val envelope = first.copyOfRange(0, 12).apply { this[10] = body.size.toByte() }
        val samples = HeadMotionProtocol.decodeAll(envelope + body)
        assertEquals(listOf(-1234, 100), samples.map { it.horizontal })
        assertEquals(listOf(2345, 2345), samples.map { it.vertical })
    }

    @Test fun `invalid framing and truncated protobuf never produce motion`() {
        val valid = frame(byteArrayOf(1))
        assertNull(decode(valid.copyOf(40)))
        assertNull(decode(valid + byteArrayOf(0)))
        assertNull(decode(valid.copyOf().apply { this[8] = 0x20 }))
        assertNull(decode(valid.copyOf().apply { this[17] = 0xFF.toByte() }))
    }
}
