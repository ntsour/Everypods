package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerV2FrameTest {
    @Test
    fun `ping empty payload has stable big endian golden frame`() {
        assertArrayEquals(
            byteArrayOf(
                'E'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte(),
                2, 3, 0, 0, 0, 0,
            ),
            PeerV2Frame.Frame(PeerV2Frame.Type.PING, byteArrayOf()).encode(),
        )
    }

    @Test
    fun `frame owns its payload bytes`() {
        val source = byteArrayOf(7, 8)
        val frame = PeerV2Frame.Frame(PeerV2Frame.Type.PING, source)
        source[0] = 0
        val read = frame.payload
        read[1] = 0
        assertArrayEquals(byteArrayOf(7, 8), frame.payload)
    }

    @Test
    fun `every message type survives every two chunk split`() {
        PeerV2Frame.Type.entries.forEach { type ->
            val original = PeerV2Frame.Frame(type, byteArrayOf(0, 1, 2, 3, 4, 5)).encode()
            for (split in 0..original.size) {
                val decoder = PeerV2Frame.Decoder()
                val first = decoder.feed(original.copyOfRange(0, split))
                val second = decoder.feed(original.copyOfRange(split, original.size))
                val result = first + second
                assertEquals("type=$type split=$split", 1, result.size)
                assertEquals(type, result.single().type)
                assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), result.single().payload)
                assertEquals(0, decoder.bufferedByteCount)
            }
        }
    }

    @Test
    fun `decoder emits concatenated frames from one read`() {
        val expected = PeerV2Frame.Type.entries.map { type ->
            PeerV2Frame.Frame(type, byteArrayOf(type.wireId.toByte()))
        }
        val bytes = expected.fold(ByteArray(0)) { acc, frame -> acc + frame.encode() }
        val decoded = PeerV2Frame.Decoder().feed(bytes)
        assertEquals(expected.map { it.type }, decoded.map { it.type })
        expected.indices.forEach { index -> assertArrayEquals(expected[index].payload, decoded[index].payload) }
    }

    @Test
    fun `decoder bounds retained partial frame`() {
        val encoded = PeerV2Frame.Frame(PeerV2Frame.Type.RELAY_DATA, ByteArray(PeerV2Frame.MAX_PAYLOAD_SIZE)).encode()
        val decoder = PeerV2Frame.Decoder()
        assertTrue(decoder.feed(encoded.copyOfRange(0, encoded.size - 1)).isEmpty())
        assertEquals(encoded.size - 1, decoder.bufferedByteCount)
        assertEquals(1, decoder.feed(encoded.copyOfRange(encoded.size - 1, encoded.size)).size)
        assertEquals(0, decoder.bufferedByteCount)
    }

    @Test
    fun `invalid magic version type and length fail closed`() {
        val valid = PeerV2Frame.Frame(PeerV2Frame.Type.PING, byteArrayOf()).encode()
        val cases = listOf(
            valid.copyOf().also { it[0] = 'X'.code.toByte() },
            valid.copyOf().also { it[4] = 3 },
            valid.copyOf().also { it[5] = 99 },
            valid.copyOf().also { it[8] = 0x10; it[9] = 0x01 },
        )
        cases.forEach { malformed ->
            var failed = false
            try {
                PeerV2Frame.Decoder().feed(malformed)
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue("malformed frame should fail", failed)
        }
    }
}
