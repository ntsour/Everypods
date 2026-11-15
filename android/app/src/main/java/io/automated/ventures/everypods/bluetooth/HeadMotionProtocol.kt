package io.automated.ventures.everypods.bluetooth

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.CodedInputStream
import io.automated.ventures.everypods.bluetooth.rtbuddy.proto.SensorCommand

/** Motion payload layout verified against LibrePods android/rewrite 80aa7461.
 *  Protobuf sequence/field lengths vary; offsets belong to the 58-byte sensor
 *  payload, never to the enclosing AACP packet. Health/unknown reports are ignored. */
object HeadMotionProtocol {
    data class Sample(
        val orientation1: Int,
        val orientation2: Int,
        val orientation3: Int,
        val horizontal: Int,
        val vertical: Int,
        /** Head-turning rate, payload offset 26: strong in a shake, weak in a side tilt (2026-11-15 captures). */
        val lateral: Int = 0,
        /** Sensor service the sample came from. The lateral field is verified on [VERIFIED_SERVICE] only. */
        val service: Int = VERIFIED_SERVICE,
    )
    const val VERIFIED_SERVICE = 16

    /** One sensor command as sent by the earbuds, before any field interpretation. */
    class RawCommand(val service: Int, val payload: ByteArray)

    fun decodeAll(frame: ByteArray): List<Sample> = decodeCommands(frame).mapNotNull { command ->
        val payload = command.payload
        if (command.service !in 14..16 || payload.size != 58) return@mapNotNull null
        fun signed16(offset: Int): Int = ((payload[offset].toInt() and 255) or
            ((payload[offset + 1].toInt() and 255) shl 8)).toShort().toInt()
        Sample(signed16(20), signed16(22), signed16(24), signed16(28), signed16(30), signed16(26), command.service)
    }

    /** Every sensor command in wire order, whatever its service or payload length. */
    fun decodeCommands(frame: ByteArray): List<RawCommand> {
        if (frame.size < 12 || !frame.copyOfRange(0, 10).contentEquals(
                byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0)
            )) return emptyList()
        val length = (frame[10].toInt() and 255) or ((frame[11].toInt() and 255) shl 8)
        if (length + 12 != frame.size) return emptyList()
        return try {
            // The wire stream batches multiple command fields in one envelope.
            // SensorDataWX's singular command accessor merges them, retaining
            // only the final payload. Visit every command in wire order instead.
            val input = CodedInputStream.newInstance(frame, 12, length)
            val commands = mutableListOf<RawCommand>()
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0x3A) {
                    val command = SensorCommand.parseFrom(input.readByteArray())
                    commands += RawCommand(command.serviceValue, command.payload.toByteArray())
                } else if (!input.skipField(tag)) {
                    return emptyList()
                }
            }
            commands
        } catch (_: InvalidProtocolBufferException) {
            emptyList()
        }
    }
}
