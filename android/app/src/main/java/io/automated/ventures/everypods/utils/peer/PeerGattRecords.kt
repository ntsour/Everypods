package io.automated.ventures.everypods.utils.peer

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Records carried over the peer GATT link (docs/peer-ble-transport.md §4.2):
 * `type (u8) ‖ length (u16, big-endian) ‖ payload`. Both characteristics carry a byte
 * stream of these; a record may be split across writes or notifications.
 */
object PeerGattRecords {
    const val HELLO: Int = 0x01
    const val LEGACY: Int = 0x02
    const val PING: Int = 0x03
    const val PONG: Int = 0x04
    const val V2: Int = 0x05
    /** Setup exchange (`docs/bt-handover-sync.md` JSON), central → phone; accepted before the central is authorized. */
    const val SETUP_REQ: Int = 0x06
    /** The phone's winning setup state, phone → central. */
    const val SETUP_ACK: Int = 0x07
    /** One fragment of a payload larger than [MAX_PAYLOAD] (see [PeerLeFragments]). */
    const val FRAG: Int = 0x08
    /** Case evidence: `role (u8) ‖ exchangeId (u16) ‖ one EPC1 frame`. */
    const val CASE: Int = 0x09
    /** Relay signalling: `kind (u8: 1 OPEN, 2 OPEN_ACK, 3 DATA, 4 CLOSE) ‖ bytes (DATA only)`. */
    const val RELAY: Int = 0x0A

    const val MAX_PAYLOAD: Int = 4096
    private const val HEADER: Int = 3

    data class Record(val type: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Record && other.type == type && other.payload.contentEquals(payload)
        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    data class Hello(val installationId: String, val name: String, val capabilities: List<String>)

    class ProtocolException(message: String) : Exception(message)

    fun encode(type: Int, payload: ByteArray): ByteArray {
        require(type in 1..0xFF) { "record_type_out_of_range" }
        require(payload.size <= MAX_PAYLOAD) { "record_payload_too_large" }
        val out = ByteArray(HEADER + payload.size)
        out[0] = type.toByte()
        out[1] = (payload.size ushr 8).toByte()
        out[2] = payload.size.toByte()
        payload.copyInto(out, HEADER)
        return out
    }

    fun encodeHello(hello: Hello): ByteArray {
        val json = JSONObject()
            .put("v", 1)
            .put("id", hello.installationId)
            .put("name", hello.name)
            .put("caps", JSONArray(hello.capabilities))
        return encode(HELLO, json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Parses a HELLO payload; throws [ProtocolException] if it is not a valid v1 HELLO. */
    fun parseHello(payload: ByteArray): Hello {
        val json = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (e: Exception) {
            throw ProtocolException("hello_not_json")
        }
        if (json.optInt("v", -1) != 1) throw ProtocolException("hello_version")
        val id = json.optString("id", "")
        val canonical = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        if (!canonical) throw ProtocolException("hello_id")
        val name = json.optString("name", "")
        if (name.length > 248) throw ProtocolException("hello_name")
        val caps = json.optJSONArray("caps")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, null) }
        } ?: emptyList()
        return Hello(id, name, caps)
    }

    /** Splits [data] into chunks of at most [chunkSize] bytes (MTU − 3). */
    fun chunk(data: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0) { "chunk_size" }
        if (data.isEmpty()) return emptyList()
        return (data.indices step chunkSize).map { data.copyOfRange(it, minOf(it + chunkSize, data.size)) }
    }

    /**
     * Reassembles records from a byte stream. Unknown types are returned too (the caller
     * skips them). A length above [MAX_PAYLOAD] throws [ProtocolException]: the stream can
     * no longer be trusted and the session must be dropped.
     */
    class Decoder {
        private val pending = ByteArrayOutputStream()

        fun feed(bytes: ByteArray): List<Record> {
            pending.write(bytes)
            val buffer = pending.toByteArray()
            val records = mutableListOf<Record>()
            var offset = 0
            while (buffer.size - offset >= HEADER) {
                val type = buffer[offset].toInt() and 0xFF
                val length = ((buffer[offset + 1].toInt() and 0xFF) shl 8) or (buffer[offset + 2].toInt() and 0xFF)
                if (type == 0) throw ProtocolException("record_type_zero")
                if (length > MAX_PAYLOAD) throw ProtocolException("record_too_large")
                if (buffer.size - offset - HEADER < length) break
                records += Record(type, buffer.copyOfRange(offset + HEADER, offset + HEADER + length))
                offset += HEADER + length
            }
            pending.reset()
            pending.write(buffer, offset, buffer.size - offset)
            return records
        }

        fun reset() = pending.reset()
    }
}
