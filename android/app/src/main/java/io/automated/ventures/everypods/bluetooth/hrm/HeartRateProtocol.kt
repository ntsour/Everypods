/* Copyright (C) 2025 LibrePods contributors; 2026 EveryPods contributors.
 * GPL-3.0. Adapted from LibrePods 5dd8446 RTBuddyManager and AACPManager. */
package io.automated.ventures.everypods.bluetooth.hrm

import com.google.protobuf.ByteString
import io.automated.ventures.everypods.bluetooth.rtbuddy.proto.SensorDataWX
import io.automated.ventures.everypods.bluetooth.rtbuddy.proto.SensorServiceSetting

object HeartRateProtocol {
    private const val SENSOR_DATA_WX = 0x00100000

    fun serviceForFirmware(firmware: String): Int =
        if ((Regex("""^\s*(\d+)""").find(firmware)?.groupValues?.get(1)?.toIntOrNull() ?: 8) >= 9) 84 else 19

    /** AACP body; sendDataPacket supplies the existing 04 00 04 00 header. */
    fun command(service: Int, intervalMicros: Int, sequence: Int): ByteArray {
        require(service == 19 || service == 84)
        require(intervalMicros >= 0)
        val config = byteArrayOf(1, intervalMicros.toByte(), (intervalMicros shr 8).toByte(),
            (intervalMicros shr 16).toByte(), (intervalMicros shr 24).toByte())
        val body = SensorDataWX.newBuilder().setSeq(sequence).setServiceSettings(
            SensorServiceSetting.newBuilder().setServiceValue(service).setSetting(2)
                .setConfiguration(ByteString.copyFrom(config))
        ).build().toByteArray()
        return byteArrayOf(0x17, 0, 0, 0, 0x10, 0, body.size.toByte(), (body.size shr 8).toByte()) + body
    }

    sealed interface Result {
        data class Sample(val bpm: Int) : Result
        data class Acknowledgment(val kind: String, val service: Int) : Result
        data object OtherSensor : Result
        data class Rejected(val reason: String) : Result
    }

    data class Metadata(val descriptor: Int, val sequence: Int? = null,
        val kind: String = "unknown_descriptor", val service: Int? = null)

    fun decode(frame: ByteArray, observe: (Metadata) -> Unit = {}): Result {
        if (frame.size < 12) return Result.Rejected("short_envelope")
        val descriptor = (0..3).fold(0) { n, i -> n or ((frame[6 + i].toInt() and 255) shl (8 * i)) }
        if (descriptor != SENSOR_DATA_WX) { observe(Metadata(descriptor)); return Result.OtherSensor }
        val length = (frame[10].toInt() and 255) or ((frame[11].toInt() and 255) shl 8)
        if (frame.size != length + 12) return Result.Rejected("invalid_length")
        val sensor = try { SensorDataWX.parseFrom(frame.copyOfRange(12, frame.size)) }
            catch (_: com.google.protobuf.InvalidProtocolBufferException) { return Result.Rejected("invalid_protobuf") }
        val metadata = when {
            sensor.hasStartAck() -> Metadata(descriptor, sensor.seq, "start_ack", sensor.startAck.serviceValue)
            sensor.hasCommandAck() -> Metadata(descriptor, sensor.seq, "command_ack", sensor.commandAck.serviceValue)
            sensor.hasCommand() -> Metadata(descriptor, sensor.seq, "command", sensor.command.serviceValue)
            sensor.hasSensorDescriptor() -> Metadata(descriptor, sensor.seq, "descriptor", sensor.sensorDescriptor.serviceValue)
            sensor.hasServiceSettings() -> Metadata(descriptor, sensor.seq, "settings", sensor.serviceSettings.serviceValue)
            else -> Metadata(descriptor, sensor.seq, "other")
        }
        observe(metadata)
        if (sensor.hasStartAck() || sensor.hasCommandAck())
            return Result.Acknowledgment(metadata.kind, metadata.service ?: 0)
        if (!sensor.hasCommand()) return Result.OtherSensor
        if (sensor.command.serviceValue != 19 && sensor.command.serviceValue != 20) return Result.OtherSensor
        val payload = sensor.command.payload.toByteArray()
        if (payload.size != 18) return Result.Rejected("invalid_measurement_length")
        if (payload[17].toInt() != 0) return Result.Rejected("invalid_measurement_status")
        val bpm = payload[1].toInt() and 255
        return if (bpm == 0) Result.Rejected("zero_bpm") else Result.Sample(bpm)
    }
}

/** Only Buddy frames have a length here; other AACP traffic follows its existing path. */
class BuddyFrameDecoder(private val reject: (String) -> Unit = {}) {
    private var pending = byteArrayOf()
    private val header = byteArrayOf(4, 0, 4, 0, 0x17, 0)

    @Synchronized fun reset() { pending = byteArrayOf() }

    @Synchronized fun feed(chunk: ByteArray): List<ByteArray> {
        val data = pending + chunk
        pending = byteArrayOf()
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            if (!(0 until minOf(remaining, 6)).all { data[offset + it] == header[it] }) {
                frames += data.copyOfRange(offset, data.size)
                break
            }
            if (remaining < 12) { pending = data.copyOfRange(offset, data.size); break }
            val length = (data[offset + 10].toInt() and 255) or ((data[offset + 11].toInt() and 255) shl 8)
            // Sensor reports are small; reject corrupt lengths instead of stalling all traffic.
            if (length > 4096) { reject("oversized_buddy_frame"); break }
            val end = offset + 12 + length
            if (end > data.size) { pending = data.copyOfRange(offset, data.size); break }
            frames += data.copyOfRange(offset, end)
            offset = end
        }
        return frames
    }
}
