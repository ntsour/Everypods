package io.automated.ventures.everypods.utils.peer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Strict JSON payload validation. Duplicate keys, unknown fields and trailing bytes fail closed. */
object PeerV2Message {
    data class Validated(val type: PeerV2Frame.Type, val fields: Map<String, Any?>)

    fun decode(frame: PeerV2Frame.Frame): Validated {
        val json = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(frame.payload)).toString()
        val value = JsonParser(json).parse()
        require(value is Map<*, *>) { "payload_not_object" }
        @Suppress("UNCHECKED_CAST")
        val fields = value as Map<String, Any?>
        validate(frame.type, fields)
        return Validated(frame.type, fields)
    }

    fun encode(type: PeerV2Frame.Type, fields: Map<String, Any?>): PeerV2Frame.Frame {
        validate(type, fields)
        val payload = JsonWriter.write(fields).toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= PeerV2Frame.MAX_PAYLOAD_SIZE) { "oversized_payload" }
        return PeerV2Frame.Frame(type, payload)
    }

    private fun validate(type: PeerV2Frame.Type, f: Map<String, Any?>) {
        when (type) {
            PeerV2Frame.Type.HELLO -> {
                exact(f, setOf("installationId", "sessionId", "capabilities", "initiatorId", "connectionId"))
                listOf("installationId", "sessionId", "initiatorId", "connectionId").forEach { uuid(f, it) }
                val caps = f["capabilities"] as? List<*> ?: invalid("invalid_capabilities")
                require(caps.all { it is String && it in CAPABILITIES }) { "invalid_capabilities" }
                val strings = caps.filterIsInstance<String>()
                require(strings == strings.distinct().sorted()) { "invalid_capabilities" }
            }
            PeerV2Frame.Type.SNAPSHOT -> snapshot(f)
            PeerV2Frame.Type.PING -> { exact(f, setOf("nonce")); uuid(f, "nonce") }
            PeerV2Frame.Type.PONG -> {
                exact(f, setOf("nonce", "snapshot")); uuid(f, "nonce")
                val s = f["snapshot"] as? Map<*, *> ?: invalid("invalid_snapshot")
                @Suppress("UNCHECKED_CAST")
                snapshot(s as Map<String, Any?>)
            }
            PeerV2Frame.Type.ACQUIRE_REQUEST -> {
                exact(f, REQUEST_FIELDS); acquireIdentity(f)
                val membership = membership(f)
                val revisions = f["snapshotRevisions"] as? Map<*, *> ?: invalid("invalid_snapshot_revisions")
                require(revisions.keys.all { it is String && isUuid(it) }) { "invalid_snapshot_revisions" }
                require(revisions.values.all { it is Long && it >= 0L }) { "invalid_snapshot_revisions" }
                require(revisions.keys == membership.toSet()) { "invalid_snapshot_revisions" }
            }
            PeerV2Frame.Type.ACQUIRE_GRANT -> {
                exact(f, REQUEST_FIELDS - "snapshotRevisions" + GRANTOR_FIELDS + "grantId")
                acquireIdentity(f, grantor = true); uuid(f, "grantId"); membership(f)
            }
            PeerV2Frame.Type.ACQUIRE_DENY -> {
                exact(f, REQUEST_FIELDS - "snapshotRevisions" + GRANTOR_FIELDS + "reason")
                acquireIdentity(f, grantor = true); membership(f)
                require(f["reason"] in DENIALS) { "invalid_deny_reason" }
            }
            PeerV2Frame.Type.ACQUIRE_CANCEL -> {
                exact(f, setOf("requestId", "requesterId", "requesterSessionId", "membershipDigest"))
                listOf("requestId", "requesterId", "requesterSessionId").forEach { uuid(f, it) }; digest(f)
            }
            PeerV2Frame.Type.RELAY_DATA -> {
                exact(f, setOf("subtype", "data")); require(f["subtype"] in RELAY_TYPES) { "invalid_relay_subtype" }
                val raw = f["data"] as? String ?: invalid("invalid_relay_data")
                val bytes = try { Base64.getDecoder().decode(raw) } catch (_: IllegalArgumentException) { invalid("invalid_relay_data") }
                require(bytes.size <= 2048 && Base64.getEncoder().encodeToString(bytes) == raw) { "invalid_relay_data" }
            }
            PeerV2Frame.Type.ACQUIRE_START, PeerV2Frame.Type.ACQUIRE_START_ACK -> {
                exact(f, START_FIELDS); acquireIdentity(f, grantor = true); uuid(f, "grantId")
            }
        }
    }

    private fun snapshot(f: Map<String, Any?>) {
        exact(f, SNAPSHOT_FIELDS); uuid(f, "sessionId"); nonnegative(f, "revision")
        nullableUuid(f, "headsetId")
        require(f["ownership"] in OWNERSHIP) { "invalid_ownership" }
        require(f["audioUse"] in AUDIO_USE) { "invalid_audio_use" }
        nullableUuid(f, "lastOwnerId")
        val ownerRevision = f["lastOwnerRevision"]
        require((f["lastOwnerId"] == null && ownerRevision == null) ||
            (f["lastOwnerId"] is String && ownerRevision is Long && ownerRevision >= 0L)) { "invalid_last_owner" }
        val a = f["acquisition"]
        if (a != null) {
            require(a is Map<*, *>) { "invalid_acquisition" }
            @Suppress("UNCHECKED_CAST")
            val acq = a as Map<String, Any?>
            exact(acq, setOf("requestId", "requesterId", "phase")); uuid(acq, "requestId"); uuid(acq, "requesterId")
            require(acq["phase"] in setOf("preparing", "in_progress")) { "invalid_acquisition_phase" }
        }
    }

    private fun acquireIdentity(f: Map<String, Any?>, grantor: Boolean = false) {
        listOf("requestId", "headsetId", "requesterId", "requesterSessionId").forEach { uuid(f, it) }
        digest(f)
        if (grantor) listOf("grantorId", "grantorSessionId").forEach { uuid(f, it) }
    }

    private fun membership(f: Map<String, Any?>): List<String> {
        val ids = f["membership"] as? List<*> ?: invalid("invalid_membership")
        require(ids.all { it is String && isUuid(it) }) { "invalid_membership" }
        val values = ids.filterIsInstance<String>()
        require(values.isNotEmpty() && values == values.distinct().sorted()) { "invalid_membership" }
        require(f["requesterId"] in values && (f["grantorId"] == null || f["grantorId"] in values)) { "invalid_membership" }
        val canonical = values.joinToString(separator = "\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
        require(f["membershipDigest"] == hash) { "membership_digest_mismatch" }
        return values
    }

    private fun invalid(code: String): Nothing = throw IllegalArgumentException(code)

    private fun exact(f: Map<String, Any?>, keys: Set<String>) { require(f.keys == keys) { "invalid_fields" } }
    private fun uuid(f: Map<String, Any?>, key: String) {
        val v = f[key] as? String ?: invalid("invalid_$key")
        require(isUuid(v)) { "invalid_$key" }
    }
    private fun nullableUuid(f: Map<String, Any?>, key: String) { if (f[key] != null) uuid(f, key) }
    private fun isUuid(v: String): Boolean = runCatching { UUID.fromString(v).toString() == v }.getOrDefault(false)
    private fun nonnegative(f: Map<String, Any?>, key: String) {
        val value = f[key] as? Long ?: invalid("invalid_$key"); require(value >= 0) { "invalid_$key" }
    }
    private fun digest(f: Map<String, Any?>) {
        val value = f["membershipDigest"] as? String ?: invalid("invalid_membership_digest")
        require(value.matches(Regex("[0-9a-f]{64}"))) { "invalid_membership_digest" }
    }

    private val SNAPSHOT_FIELDS = setOf("sessionId", "revision", "headsetId", "ownership", "audioUse", "lastOwnerId", "lastOwnerRevision", "acquisition")
    private val REQUEST_FIELDS = setOf("requestId", "headsetId", "requesterId", "requesterSessionId", "membership", "membershipDigest", "snapshotRevisions")
    private val GRANTOR_FIELDS = setOf("grantorId", "grantorSessionId")
    private val START_FIELDS = (REQUEST_FIELDS - "snapshotRevisions") + GRANTOR_FIELDS + "grantId"
    private val CAPABILITIES = setOf("snapshot", "acquire-v1")
    private val OWNERSHIP = setOf("unknown", "held", "released")
    private val AUDIO_USE = setOf("unknown", "idle", "media", "call")
    private val DENIALS = setOf("HELD", "UNKNOWN", "STALE_SNAPSHOT", "MEMBERSHIP_MISMATCH", "BUSY", "UNSUPPORTED", "SESSION_CHANGED")
    private val RELAY_TYPES = setOf("battery", "anc", "ear_state")

    private class JsonParser(private val source: String) {
        private var i = 0
        fun parse(): Any? { val v = value(0); ws(); require(i == source.length) { "trailing_json" }; return v }
        private fun value(depth: Int): Any? {
            require(depth <= MAX_JSON_DEPTH) { "json_too_deep" }
            ws(); require(i < source.length) { "incomplete_json" }
            return when (source[i]) {
                '{' -> obj(depth + 1); '[' -> array(depth + 1); '"' -> string(); 't' -> literal("true", true); 'f' -> literal("false", false)
                'n' -> literal("null", null); '-', in '0'..'9' -> number(); else -> invalid("invalid_json")
            }
        }
        private fun obj(depth: Int): Map<String, Any?> {
            i++; ws(); val out = linkedMapOf<String, Any?>(); if (take('}')) return out
            while (true) {
                ws(); require(i < source.length && source[i] == '"') { "invalid_json_key" }; val k = string()
                require(!out.containsKey(k)) { "duplicate_json_key" }; ws(); require(take(':')) { "invalid_json" }
                out[k] = value(depth); ws(); if (take('}')) return out; require(take(',')) { "invalid_json" }
            }
        }
        private fun array(depth: Int): List<Any?> {
            i++; ws(); val out = mutableListOf<Any?>(); if (take(']')) return out
            while (true) { out += value(depth); ws(); if (take(']')) return out; require(take(',')) { "invalid_json" } }
        }
        private fun string(): String {
            require(take('"')) { "invalid_json_string" }; val out = StringBuilder()
            while (i < source.length) {
                val c = source[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(i < source.length) { "invalid_escape" }; when (val e = source[i++]) {
                            '"', '\\', '/' -> out.append(e); 'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> { require(i + 4 <= source.length) { "invalid_escape" }; val code = source.substring(i, i + 4).toIntOrNull(16) ?: invalid("invalid_escape"); out.append(code.toChar()); i += 4 }
                            else -> invalid("invalid_escape")
                        }
                    }
                    c.code < 0x20 -> invalid("invalid_control_character")
                    else -> out.append(c)
                }
            }
            invalid("unterminated_string")
        }
        private fun number(): Long {
            val start = i; if (source[i] == '-') i++
            require(i < source.length) { "invalid_number" }
            if (source[i] == '0') i++ else { require(source[i] in '1'..'9') { "invalid_number" }; while (i < source.length && source[i].isDigit()) i++ }
            require(i == source.length || source[i] !in listOf('.', 'e', 'E', '+')) { "integer_required" }
            return source.substring(start, i).toLongOrNull() ?: invalid("invalid_number")
        }
        private fun <T> literal(token: String, v: T): T { require(source.startsWith(token, i)) { "invalid_json" }; i += token.length; return v }
        private fun take(c: Char): Boolean { if (i < source.length && source[i] == c) { i++; return true }; return false }
        private fun ws() { while (i < source.length && source[i] in listOf(' ', '\t', '\r', '\n')) i++ }
    }
    private const val MAX_JSON_DEPTH = 32

    private object JsonWriter {
        fun write(value: Any?): String = when (value) {
            null -> "null"; is String -> quote(value); is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                require(k is String) { "invalid_json_key" }; "${quote(k)}:${write(v)}"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { write(it) }
            else -> invalid("unsupported_json_value")
        }
        private fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
    }
}
