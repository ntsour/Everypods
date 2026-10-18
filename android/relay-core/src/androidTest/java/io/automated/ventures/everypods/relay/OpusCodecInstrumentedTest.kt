package io.automated.ventures.everypods.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class OpusCodecInstrumentedTest {
    private val n = RelayAudioFormat.FRAME_SAMPLES

    private fun sineFrame(i: Int) = ShortArray(n * 2) { k ->
        val t = (i * n + k / 2).toDouble() / RelayAudioFormat.SAMPLE_RATE
        (sin(2 * PI * 440 * t) * 12_000).toInt().toShort()
    }

    private fun rms(a: ShortArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size)

    @Test fun roundTripPreservesEnergyAndSize() {
        OpusEncoder().use { enc ->
            OpusDecoder().use { dec ->
                assertTrue(OpusEncoder.libraryVersion().startsWith("libopus 1.5"))
                var bytes = 0
                var lastOut = ShortArray(0)
                repeat(50) { i ->
                    val pkt = enc.encode(sineFrame(i))
                    assertTrue(pkt.isNotEmpty() && pkt.size <= RelayAudioFormat.MAX_OPUS_PACKET)
                    bytes += pkt.size
                    lastOut = dec.decode(pkt)
                    assertEquals(n * 2, lastOut.size)
                }
                val kbps = bytes * 8.0 / 1.0 / 1000 // 50 frames = 1 s
                assertTrue("bitrate $kbps", kbps in 64.0..260.0)
                val ratio = rms(lastOut) / rms(sineFrame(49))
                assertTrue("rms ratio $ratio", ratio in 0.7..1.3)
                assertEquals(n * 2, dec.decode(null).size) // PLC
            }
        }
    }
}
