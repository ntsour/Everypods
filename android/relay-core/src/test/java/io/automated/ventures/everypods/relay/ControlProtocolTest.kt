package io.automated.ventures.everypods.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProtocolTest {
    private val all = listOf(
        ControlMessage.Hello(1, "pixel-☃", ByteArray(32) { it.toByte() }),
        ControlMessage.Start(RelayPacket.CODEC_OPUS, 48_000, 2, 20, 192_000, 47474),
        ControlMessage.Stop(ControlMessage.STOP_FOCUS_LOST),
        ControlMessage.Keepalive(123_456_789L, 42L),
        ControlMessage.Stats(1000, 3, 2, 1, 200, 450),
    )

    @Test fun roundTripEachType() {
        for (m in all) assertEquals(m, ControlCodec.decodeBody(ControlCodec.encodeBody(m)))
    }

    @Test fun streamDecoderHandlesSplitAndCoalescedFrames() {
        val stream = all.map { ControlCodec.frame(it) }.reduce { a, b -> a + b }
        val dec = ControlFrameDecoder()
        val got = mutableListOf<ControlMessage>()
        stream.forEach { got += dec.feed(byteArrayOf(it)) } // one byte at a time
        assertEquals(all, got)
        assertEquals(0, dec.pendingBytes)
        assertEquals(all, ControlFrameDecoder().feed(stream)) // all at once
    }

    @Test fun partialFrameWaits() {
        val f = ControlCodec.frame(ControlMessage.Stop(0))
        val dec = ControlFrameDecoder()
        assertTrue(dec.feed(f.copyOf(f.size - 1)).isEmpty())
        assertEquals(listOf(ControlMessage.Stop(0)), dec.feed(f.copyOfRange(f.size - 1, f.size)))
    }

    @Test fun rejectsMalformed() {
        assertThrows(ControlProtocolException::class.java) { ControlFrameDecoder().feed(byteArrayOf(0, 0)) }
        assertThrows(ControlProtocolException::class.java) { ControlFrameDecoder().feed(byteArrayOf(0x7f, 0x7f)) }
        assertThrows(ControlProtocolException::class.java) { ControlCodec.decodeBody(byteArrayOf(99)) }
        assertThrows(ControlProtocolException::class.java) { ControlCodec.decodeBody(byteArrayOf()) }
        assertThrows(ControlProtocolException::class.java) { ControlCodec.decodeBody(byteArrayOf(ControlMessage.TYPE_STATS.toByte(), 1)) }
        val stop = ControlCodec.encodeBody(ControlMessage.Stop(1))
        assertThrows(ControlProtocolException::class.java) { ControlCodec.decodeBody(stop + 0) }
        val hello = ControlCodec.encodeBody(ControlMessage.Hello(1, "x", ByteArray(32)))
        assertThrows(ControlProtocolException::class.java) { ControlCodec.decodeBody(hello.copyOf(hello.size - 1)) }
    }
}
