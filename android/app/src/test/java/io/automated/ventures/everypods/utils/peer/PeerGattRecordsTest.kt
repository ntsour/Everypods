package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PeerGattRecordsTest {
    private val status = byteArrayOf(0x00, 0x01, 0x00, 0x01)

    @Test
    fun `encodes type, big-endian length and payload`() {
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x04, 0x00, 0x01, 0x00, 0x01), PeerGattRecords.encode(PeerGattRecords.LEGACY, status))
        val big = ByteArray(300)
        val encoded = PeerGattRecords.encode(PeerGattRecords.V2, big)
        assertEquals(0x01, encoded[1].toInt())
        assertEquals(0x2C, encoded[2].toInt())
        assertEquals(303, encoded.size)
    }

    @Test
    fun `rejects oversized payloads when encoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerGattRecords.encode(PeerGattRecords.V2, ByteArray(PeerGattRecords.MAX_PAYLOAD + 1))
        }
    }

    @Test
    fun `decodes records split at every byte boundary`() {
        val stream = PeerGattRecords.encode(PeerGattRecords.LEGACY, status) +
            PeerGattRecords.encode(PeerGattRecords.PING, byteArrayOf(0, 0, 0, 7)) +
            PeerGattRecords.encode(PeerGattRecords.V2, ByteArray(0))
        for (split in 1 until stream.size) {
            val decoder = PeerGattRecords.Decoder()
            val records = decoder.feed(stream.copyOfRange(0, split)) + decoder.feed(stream.copyOfRange(split, stream.size))
            assertEquals("split at $split", 3, records.size)
            assertEquals(PeerGattRecords.Record(PeerGattRecords.LEGACY, status), records[0])
            assertEquals(PeerGattRecords.Record(PeerGattRecords.PING, byteArrayOf(0, 0, 0, 7)), records[1])
            assertEquals(PeerGattRecords.Record(PeerGattRecords.V2, ByteArray(0)), records[2])
        }
    }

    @Test
    fun `decodes records fed one byte at a time through MTU-sized chunks`() {
        val stream = PeerGattRecords.encode(PeerGattRecords.V2, ByteArray(1000) { it.toByte() })
        val decoder = PeerGattRecords.Decoder()
        val chunks = PeerGattRecords.chunk(stream, 20)
        assertEquals(51, chunks.size)
        val records = chunks.flatMap { decoder.feed(it) }
        assertEquals(1, records.size)
        assertArrayEquals(ByteArray(1000) { it.toByte() }, records[0].payload)
    }

    @Test
    fun `unknown record types are returned so the caller can skip them`() {
        val records = PeerGattRecords.Decoder().feed(PeerGattRecords.encode(0x7F, byteArrayOf(1, 2)))
        assertEquals(0x7F, records.single().type)
    }

    @Test
    fun `a length above the limit or a zero type breaks the stream`() {
        assertThrows(PeerGattRecords.ProtocolException::class.java) {
            PeerGattRecords.Decoder().feed(byteArrayOf(0x02, 0x10, 0x01))
        }
        assertThrows(PeerGattRecords.ProtocolException::class.java) {
            PeerGattRecords.Decoder().feed(byteArrayOf(0x00, 0x00, 0x00))
        }
    }

    @Test
    fun `hello round-trips and rejects bad identities`() {
        val hello = PeerGattRecords.Hello("0b7b2c43-5a0f-4c1e-9d2b-1f6f2d3c4b5a", "Pixel 10", listOf("legacy1"))
        val record = PeerGattRecords.Decoder().feed(PeerGattRecords.encodeHello(hello)).single()
        assertEquals(PeerGattRecords.HELLO, record.type)
        assertEquals(hello, PeerGattRecords.parseHello(record.payload))

        assertThrows(PeerGattRecords.ProtocolException::class.java) { PeerGattRecords.parseHello("not json".toByteArray()) }
        assertThrows(PeerGattRecords.ProtocolException::class.java) {
            PeerGattRecords.parseHello("""{"v":2,"id":"0b7b2c43-5a0f-4c1e-9d2b-1f6f2d3c4b5a","name":"x"}""".toByteArray())
        }
        assertThrows(PeerGattRecords.ProtocolException::class.java) {
            PeerGattRecords.parseHello("""{"v":1,"id":"not-a-uuid","name":"x"}""".toByteArray())
        }
        val noCaps = PeerGattRecords.parseHello("""{"v":1,"id":"0b7b2c43-5a0f-4c1e-9d2b-1f6f2d3c4b5a","name":"Mac"}""".toByteArray())
        assertTrue(noCaps.capabilities.isEmpty())
    }

    @Test
    fun `chunking covers the data exactly`() {
        val data = ByteArray(45) { it.toByte() }
        val chunks = PeerGattRecords.chunk(data, 20)
        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
        assertArrayEquals(data, chunks.reduce { a, b -> a + b })
        assertTrue(PeerGattRecords.chunk(ByteArray(0), 20).isEmpty())
    }

    @Test
    fun setupRecordsRoundTripTheSetupJson() {
        val json = """{"version":1,"revision":1,"origin":"0f0e0d0c-0000-4000-8000-000000000001","enabled":true}"""
        val decoder = PeerGattRecords.Decoder()
        val bytes = PeerGattRecords.encode(PeerGattRecords.SETUP_REQ, json.toByteArray()) +
            PeerGattRecords.encode(PeerGattRecords.SETUP_ACK, json.toByteArray())
        val records = decoder.feed(bytes)
        assertEquals(listOf(0x06, 0x07), records.map { it.type })
        records.forEach { assertEquals(json, it.payload.toString(Charsets.UTF_8)) }
    }
}
