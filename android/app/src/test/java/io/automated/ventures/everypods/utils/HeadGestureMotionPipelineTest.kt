package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.bluetooth.HeadMotionProtocol
import io.automated.ventures.everypods.data.*
import org.junit.Assert.*
import org.junit.Test

/** Independent wire fixtures verify that main's variable protobuf framing feeds the new detector. */
class HeadGestureMotionPipelineTest {
    private fun command(horizontal: Int, vertical: Int): ByteArray {
        val payload = ByteArray(58)
        fun put(offset: Int, value: Int) {
            payload[offset] = value.toByte()
            payload[offset + 1] = (value shr 8).toByte()
        }
        put(28, horizontal); put(30, vertical)
        return byteArrayOf(0x3A, 0x3E, 8, 16, 0x1A, 0x3A) + payload
    }
    private fun frame(sequence: ByteArray, commands: List<ByteArray>): ByteArray {
        // Unknown fields deliberately shift payload positions in the outer AACP frame.
        val body = byteArrayOf(8) + sequence + byteArrayOf(0x10, 2, 0x1A, 3, 9, 8, 7) + commands.fold(byteArrayOf()) { result, next -> result + next }
        return byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, body.size.toByte(), (body.size shr 8).toByte()) + body
    }
    @Test fun `batched motion reports feed all nod repetitions despite growing sequence fields`() {
        listOf(byteArrayOf(0x7F), byteArrayOf(0x80.toByte(), 1), byteArrayOf(0x80.toByte(), 0x80.toByte(), 1)).forEach { sequence ->
            var clock = 0L
            val detector = GestureDetector { clock }
            val samples = HeadMotionProtocol.decodeAll(frame(sequence, listOf(800, -800, 0, 800, -800, 0).map { command(0, it) }))
            assertEquals(6, samples.size)
            val events = samples.mapNotNull { sample -> clock += 40; detector.process(sample.horizontal, sample.vertical, GestureTuning(), GestureTuning()) }
            assertEquals(listOf(HeadGesture.NOD), events)
            val settings = HeadGestureSettings(enabled = true, normal = mapOf(HeadGesture.NOD to StemAction.PLAY_PAUSE))
            assertEquals(HeadGestureDispatch.Action(StemAction.PLAY_PAUSE), settings.resolve(events.single(), HeadGestureContext.NORMAL))
        }
    }
    @Test fun `horizontal sensor samples cancel speech before Gym action`() {
        var clock = 0L
        val detector = GestureDetector { clock }
        val samples = HeadMotionProtocol.decodeAll(frame(byteArrayOf(1), listOf(800, -800, 0, 800, -800, 0).map { command(it, 0) }))
        val events = samples.mapNotNull { sample -> clock += 40; detector.process(sample.horizontal, sample.vertical, GestureTuning(), GestureTuning()) }
        assertEquals(listOf(HeadGesture.SHAKE), events)
        val settings = HeadGestureSettings(enabled = true, gym = mapOf(HeadGesture.SHAKE to StemAction.GYM_TIMER_LAP))
        assertEquals(HeadGestureDispatch.StopAnnouncement, settings.resolve(events.single(), HeadGestureContext.GYM, true))
    }
}
