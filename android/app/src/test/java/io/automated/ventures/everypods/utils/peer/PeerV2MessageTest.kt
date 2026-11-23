package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class PeerV2MessageTest {
    private val id1 = "00000000-0000-4000-8000-000000000001"
    private val id2 = "00000000-0000-4000-8000-000000000002"

    @Test
    fun `hello payload round trips and validates canonical identities`() {
        val fields = mapOf(
            "installationId" to id1,
            "sessionId" to id2,
            "capabilities" to listOf("acquire-v1", "snapshot"),
            "initiatorId" to id1,
            "connectionId" to id2,
        )
        val frame = PeerV2Message.encode(PeerV2Frame.Type.HELLO, fields)
        assertEquals(fields, PeerV2Message.decode(frame).fields)
    }

    @Test
    fun `snapshot validates ownership and revisions`() {
        val fields = mapOf(
            "sessionId" to id2,
            "revision" to 12L,
            "headsetId" to null,
            "ownership" to "unknown",
            "audioUse" to "unknown",
            "lastOwnerId" to null,
            "lastOwnerRevision" to null,
            "acquisition" to null,
        )
        assertEquals(fields, PeerV2Message.decode(PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, fields)).fields)
    }

    @Test
    fun `acquire request membership digest binds sorted members and snapshots`() {
        val membership = listOf(id1, id2)
        val canonical = (membership.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
        val fields = mapOf(
            "requestId" to id1,
            "headsetId" to id2,
            "requesterId" to id1,
            "requesterSessionId" to id2,
            "membership" to membership,
            "membershipDigest" to digest,
            "snapshotRevisions" to mapOf(id1 to 4L, id2 to 9L),
        )
        assertEquals(fields, PeerV2Message.decode(PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_REQUEST, fields)).fields)
    }

    @Test
    fun `acquire start and acknowledgement bind grantor session and grant`() {
        val fields = mapOf(
            "requestId" to id1, "headsetId" to id2, "requesterId" to id1,
            "requesterSessionId" to id2, "membership" to listOf(id1, id2),
            "membershipDigest" to "a".repeat(64),
            "grantorId" to id2, "grantorSessionId" to id1, "grantId" to id2,
        )
        for (type in listOf(PeerV2Frame.Type.ACQUIRE_START, PeerV2Frame.Type.ACQUIRE_START_ACK)) {
            assertEquals(fields, PeerV2Message.decode(PeerV2Message.encode(type, fields)).fields)
        }
    }

    @Test
    fun `unknown keys duplicate keys malformed utf8 and invalid schemas fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.decode(payload(PeerV2Frame.Type.PING, """{"nonce":"$id1","extra":true}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.decode(payload(PeerV2Frame.Type.PING, """{"nonce":"$id1","nonce":"$id2"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.decode(payload(PeerV2Frame.Type.PING, """{"nonce":"not-a-uuid"}"""))
        }
        assertThrows(Exception::class.java) {
            PeerV2Message.decode(PeerV2Frame.Frame(PeerV2Frame.Type.PING, byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.decode(payload(PeerV2Frame.Type.PING, """{"nonce":"$id1"} false"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.decode(payload(PeerV2Frame.Type.PING, "{".repeat(40) + "}".repeat(40)))
        }
    }

    @Test
    fun `relay payload has strict subtype canonical base64 and decoded limit`() {
        val fields = mapOf("subtype" to "battery", "data" to "AQID")
        assertEquals(fields, PeerV2Message.decode(PeerV2Message.encode(PeerV2Frame.Type.RELAY_DATA, fields)).fields)
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.encode(PeerV2Frame.Type.RELAY_DATA, mapOf("subtype" to "battery", "data" to "%%%"))
        }
    }

    private fun requestFields(): Map<String, Any?> {
        val membership = listOf(id1, id2)
        val canonical = (membership.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
        return mapOf("requestId" to id1, "headsetId" to id2, "requesterId" to id1, "requesterSessionId" to id2,
            "membership" to membership, "membershipDigest" to digest, "snapshotRevisions" to mapOf(id1 to 4L, id2 to 9L))
    }

    private fun roundTrip(type: PeerV2Frame.Type, fields: Map<String, Any?>) =
        PeerV2Message.decode(PeerV2Message.encode(type, fields))

    @Test
    fun `acquire request intent is optional and defaults to passive`() {
        val plain = requestFields()
        assertEquals(HandoverIntent.PASSIVE, PeerV2Message.intentOf(roundTrip(PeerV2Frame.Type.ACQUIRE_REQUEST, plain).fields))
        HandoverIntent.entries.forEach { intent ->
            val fields = plain + ("intent" to intent.wire)
            val decoded = roundTrip(PeerV2Frame.Type.ACQUIRE_REQUEST, fields)
            assertEquals(fields, decoded.fields)
            assertEquals(intent, PeerV2Message.intentOf(decoded.fields))
        }
    }

    @Test
    fun `acquire request rejects invalid intent and still rejects unknown fields`() {
        listOf("bogus", "CALL", "", 3L, null).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_REQUEST, requestFields() + ("intent" to it))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_REQUEST, requestFields() + ("extra" to "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_REQUEST, requestFields() + mapOf("intent" to "call", "extra" to "x"))
        }
    }

    @Test
    fun `deny accepts policy and call active and hello accepts acquire v2`() {
        val base = requestFields() - "snapshotRevisions" + mapOf("grantorId" to id2, "grantorSessionId" to id1)
        listOf("POLICY", "CALL_ACTIVE", "HELD").forEach {
            assertEquals(it, roundTrip(PeerV2Frame.Type.ACQUIRE_DENY, base + ("reason" to it)).fields["reason"])
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_DENY, base + ("reason" to "NOPE"))
        }
        val hello = mapOf("installationId" to id1, "sessionId" to id2, "initiatorId" to id1, "connectionId" to id2)
        val caps = listOf("acquire-v1", "acquire-v2", "snapshot")
        assertEquals(caps, roundTrip(PeerV2Frame.Type.HELLO, hello + ("capabilities" to caps)).fields["capabilities"])
        val future = listOf("acquire-v1", "acquire-v3", "snapshot")
        assertEquals(future, roundTrip(PeerV2Frame.Type.HELLO, hello + ("capabilities" to future)).fields["capabilities"])
        assertThrows(IllegalArgumentException::class.java) {
            PeerV2Message.encode(PeerV2Frame.Type.HELLO, hello + ("capabilities" to listOf("Bad Name", "snapshot")))
        }
    }

    private fun payload(type: PeerV2Frame.Type, json: String) =
        PeerV2Frame.Frame(type, json.toByteArray(StandardCharsets.UTF_8))
}
