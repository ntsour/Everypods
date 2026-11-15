package io.automated.ventures.everypods.relay

import io.automated.ventures.everypods.relay.transport.RelayJitterBuffer
import io.automated.ventures.everypods.relay.transport.RelayMediaPacket
import org.junit.Assert.*
import org.junit.Test

class RelayPcmCatchUpTest {
    private fun packet(sequence: Long) = RelayMediaPacket(sequence, sequence * 10_000_000, 10, byteArrayOf(sequence.toByte()))

    @Test
    fun normalFrameIsDecodedOnceWithoutAlteringPcm() {
        val pcm = shortArrayOf(123, -456)
        var calls = 0
        val result = RelayPcmCatchUp.decode(RelayJitterBuffer.Output.Frame(packet(1)), 2) {
            calls++; pcm
        }
        assertEquals(1, calls)
        assertSame(pcm, result)
    }

    @Test
    fun catchUpDecodesInOrderAndCrossfadesBothChannelsIntoOneFrame() {
        val calls = mutableListOf<Int>()
        val earlier = shortArrayOf(1000, -1000, 1000, -1000, 1000, -1000)
        val result = RelayPcmCatchUp.decode(RelayJitterBuffer.Output.Frame(packet(2), packet(1)), 2) {
            val sequence = it[0].toInt(); calls += sequence
            if (sequence == 1) earlier else shortArrayOf(3000, -3000, 3000, -3000, 3000, -3000)
        }
        assertEquals(listOf(1, 2), calls)
        assertArrayEquals(shortArrayOf(1000, -1000, 2000, -2000, 3000, -3000), result)
        assertArrayEquals(shortArrayOf(1000, -1000, 1000, -1000, 1000, -1000), earlier)
    }

    @Test
    fun fullScaleAndSilentAudioDoNotOverflowOrChangeLevel() {
        for (value in listOf(Short.MIN_VALUE, 0.toShort(), Short.MAX_VALUE)) {
            for (duration in listOf(10, 20)) {
                val result = RelayPcmCatchUp.decode(RelayJitterBuffer.Output.Frame(packet(2), packet(1)), 2) {
                    ShortArray(48 * duration * 2) { value }
                }
                assertEquals(48 * duration * 2, result.size)
                assertTrue(result.all { it == value })
            }
        }
    }
}
