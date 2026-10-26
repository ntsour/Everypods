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
    )

    fun decodeAll(frame: ByteArray): List<Sample> {
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
            val samples = mutableListOf<Sample>()
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0x3A) {
                    val command = SensorCommand.parseFrom(input.readByteArray())
                    if (command.serviceValue !in 14..16) continue
                    val payload = command.payload.toByteArray()
                    if (payload.size != 58) continue
                    fun signed16(offset: Int): Int = ((payload[offset].toInt() and 255) or
                        ((payload[offset + 1].toInt() and 255) shl 8)).toShort().toInt()
                    samples += Sample(signed16(20), signed16(22), signed16(24), signed16(28), signed16(30))
                } else if (!input.skipField(tag)) {
                    return emptyList()
                }
            }
            samples
        } catch (_: InvalidProtocolBufferException) {
            emptyList()
        }
    }
}
