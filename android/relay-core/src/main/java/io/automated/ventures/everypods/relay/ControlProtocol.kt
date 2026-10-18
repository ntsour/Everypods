package io.automated.ventures.everypods.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Reliable control channel (TCP) messages for start/stop/keepalive/stats.
 * Frame = u16 big-endian length ‖ body; body = type(u8) ‖ fields. Max body [MAX_FRAME].
 * In P3 each body is sealed with the session AEAD before framing.
 */
sealed class ControlMessage {
    abstract val type: Int

    data class Hello(val protocolVersion: Int, val deviceId: String, val ephemeralPublic: ByteArray) : ControlMessage() {
        override val type = TYPE_HELLO
        override fun equals(other: Any?) = other is Hello && protocolVersion == other.protocolVersion &&
            deviceId == other.deviceId && ephemeralPublic.contentEquals(other.ephemeralPublic)
        override fun hashCode() = deviceId.hashCode() * 31 + ephemeralPublic.contentHashCode()
    }
    data class Start(val codec: Int, val sampleRate: Int, val channels: Int, val frameMs: Int, val bitrate: Int, val udpPort: Int) : ControlMessage() {
        override val type = TYPE_START
    }
    data class Stop(val reason: Int) : ControlMessage() { override val type = TYPE_STOP }
    data class Keepalive(val sentAtMicros: Long, val echoOfMicros: Long = 0) : ControlMessage() { override val type = TYPE_KEEPALIVE }
    data class Stats(val received: Long, val lost: Long, val late: Long, val underruns: Long, val jitterBufferMs: Int, val latencyMs: Int) : ControlMessage() {
        override val type = TYPE_STATS
    }

    companion object {
        const val TYPE_HELLO = 1
        const val TYPE_START = 2
        const val TYPE_STOP = 3
        const val TYPE_KEEPALIVE = 4
        const val TYPE_STATS = 5
        const val STOP_USER = 0
        const val STOP_CAPTURE_REVOKED = 1
        const val STOP_FOCUS_LOST = 2
        const val STOP_ROUTE_LOST = 3
        const val STOP_ERROR = 4
    }
}

class ControlProtocolException(msg: String) : Exception(msg)

object ControlCodec {
    const val MAX_FRAME = 4096

    fun encodeBody(m: ControlMessage): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeByte(m.type)
            when (m) {
                is ControlMessage.Hello -> {
                    o.writeByte(m.protocolVersion)
                    val id = m.deviceId.toByteArray(Charsets.UTF_8)
                    require(id.size <= 128) { "deviceId too long" }
                    require(m.ephemeralPublic.size == 32) { "public key must be 32 bytes" }
                    o.writeByte(id.size); o.write(id); o.write(m.ephemeralPublic)
                }
                is ControlMessage.Start -> {
                    o.writeByte(m.codec); o.writeInt(m.sampleRate); o.writeByte(m.channels)
                    o.writeByte(m.frameMs); o.writeInt(m.bitrate); o.writeShort(m.udpPort)
                }
                is ControlMessage.Stop -> o.writeByte(m.reason)
                is ControlMessage.Keepalive -> { o.writeLong(m.sentAtMicros); o.writeLong(m.echoOfMicros) }
                is ControlMessage.Stats -> {
                    o.writeLong(m.received); o.writeLong(m.lost); o.writeLong(m.late); o.writeLong(m.underruns)
                    o.writeInt(m.jitterBufferMs); o.writeInt(m.latencyMs)
                }
            }
        }
        return bos.toByteArray()
    }

    fun decodeBody(body: ByteArray): ControlMessage {
        if (body.isEmpty()) throw ControlProtocolException("empty body")
        val i = DataInputStream(body.inputStream())
        val msg = try {
            when (val type = i.readUnsignedByte()) {
                ControlMessage.TYPE_HELLO -> {
                    val v = i.readUnsignedByte()
                    val n = i.readUnsignedByte()
                    if (n > 128) throw ControlProtocolException("deviceId too long")
                    val id = ByteArray(n).also { i.readFully(it) }
                    val pub = ByteArray(32).also { i.readFully(it) }
                    ControlMessage.Hello(v, String(id, Charsets.UTF_8), pub)
                }
                ControlMessage.TYPE_START -> ControlMessage.Start(
                    i.readUnsignedByte(), i.readInt(), i.readUnsignedByte(), i.readUnsignedByte(), i.readInt(), i.readUnsignedShort()
                )
                ControlMessage.TYPE_STOP -> ControlMessage.Stop(i.readUnsignedByte())
                ControlMessage.TYPE_KEEPALIVE -> ControlMessage.Keepalive(i.readLong(), i.readLong())
                ControlMessage.TYPE_STATS -> ControlMessage.Stats(
                    i.readLong(), i.readLong(), i.readLong(), i.readLong(), i.readInt(), i.readInt()
                )
                else -> throw ControlProtocolException("unknown type $type")
            }
        } catch (e: java.io.EOFException) {
            throw ControlProtocolException("truncated body")
        }
        if (i.available() != 0) throw ControlProtocolException("trailing bytes")
        return msg
    }

    fun frame(m: ControlMessage): ByteArray {
        val body = encodeBody(m)
        require(body.size <= MAX_FRAME)
        return byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()) + body
    }
}

/** Incremental deframer for a TCP byte stream: feed arbitrary chunks, get whole messages. */
class ControlFrameDecoder {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray, off: Int = 0, len: Int = chunk.size): List<ControlMessage> {
        buf += chunk.copyOfRange(off, off + len)
        val out = mutableListOf<ControlMessage>()
        while (buf.size >= 2) {
            val n = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
            if (n == 0 || n > ControlCodec.MAX_FRAME) throw ControlProtocolException("bad frame length $n")
            if (buf.size < 2 + n) break
            out += ControlCodec.decodeBody(buf.copyOfRange(2, 2 + n))
            buf = buf.copyOfRange(2 + n, buf.size)
        }
        return out
    }

    val pendingBytes: Int get() = buf.size
}
