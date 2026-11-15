package io.automated.ventures.everypods.utils

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HeadMotionRecorderTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun tearDown() { HeadMotionRecorder.stop(); HeadMotionRecorder.setLabel(HeadMotionRecorder.LABELS.first()) }

    // Independent SensorDataWX wire fixture, as in HeadMotionProtocolTest.
    private fun command(service: Int, payload: ByteArray) =
        byteArrayOf(0x3A, (payload.size + 4).toByte(), 8, service.toByte(), 0x1A, payload.size.toByte()) + payload

    private fun frame(vararg commands: ByteArray): ByteArray {
        val body = byteArrayOf(8, 1) + commands.reduce { a, b -> a + b }
        return byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, body.size.toByte(), 0) + body
    }

    @Test fun `records every command with label, service and untouched payload`() {
        val motion = ByteArray(58) { it.toByte() }
        val other = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        val file = HeadMotionRecorder.start(folder.root, now = 1000, name = "capture.csv")!!
        HeadMotionRecorder.setLabel("tilt_left_first")
        HeadMotionRecorder.record(frame(command(16, motion), command(11, other)), now = 1040)
        HeadMotionRecorder.mark("detected:NOD", now = 1080)
        assertEquals(3, HeadMotionRecorder.state.value.rows)
        HeadMotionRecorder.stop()

        val hex = motion.joinToString("") { "%02x".format(it) }
        assertEquals(listOf(
            "elapsed_ms,label,service,payload_hex",
            "40,tilt_left_first,16,$hex",
            "40,tilt_left_first,11,abcd",
            "80,tilt_left_first,-1,detected:NOD",
        ), file.readLines())
    }

    @Test fun `ignores frames when stopped and malformed frames while recording`() {
        val valid = frame(command(16, ByteArray(58)))
        HeadMotionRecorder.record(valid, now = 0)
        val file = HeadMotionRecorder.start(folder.root, now = 0, name = "capture.csv")!!
        HeadMotionRecorder.record(valid.copyOf(20), now = 40)
        HeadMotionRecorder.stop()
        HeadMotionRecorder.record(valid, now = 80)
        assertFalse(HeadMotionRecorder.state.value.recording)
        assertEquals(1, file.readLines().size)
    }
}
