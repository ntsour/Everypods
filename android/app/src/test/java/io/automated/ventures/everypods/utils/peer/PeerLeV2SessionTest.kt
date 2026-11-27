package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLeV2SessionTest {
    private val idA = "00000000-0000-4000-8000-00000000000a"
    private val idB = "00000000-0000-4000-8000-00000000000b"
    private val sessionA = "00000000-0000-4000-8000-0000000000a1"
    private val sessionB = "00000000-0000-4000-8000-0000000000b1"

    private class Side(val install: String, val session: String) : PeerLeV2Session.Hooks {
        var peer: PeerLeV2Session? = null
        val outbox = ArrayDeque<ByteArray>()
        var authorized = true
        var sendOk = true
        var accepted = true
        val ended = mutableListOf<String>()
        val ready = mutableListOf<Pair<String, Boolean>>()
        val bound = mutableListOf<String>()
        val acquisitions = mutableListOf<PeerV2Frame.Type>()
        var snapshots = 0
        val quiet = mutableListOf<Boolean>()
        override fun send(frame: PeerV2Frame.Frame): Boolean { if (sendOk) outbox += frame.encode(); return sendOk }
        override fun isAuthorized() = authorized
        override fun localInstallationId() = install
        override fun localSessionId() = session
        override fun localSnapshot(): Map<String, Any?> = mapOf(
            "sessionId" to session, "revision" to 1L, "headsetId" to null, "ownership" to "unknown",
            "audioUse" to "unknown", "lastOwnerId" to null, "lastOwnerRevision" to null, "acquisition" to null)
        override fun newConnectionId() = "00000000-0000-4000-8000-0000000000c1"
        override fun bindPeerIdentity(installationId: String) { bound += installationId }
        override fun observeSnapshot(remoteSessionId: String, snapshot: Map<String, Any?>): Boolean { snapshots++; return accepted }
        override fun markReady(remoteSessionId: String, acquisitionCapable: Boolean, intentCapable: Boolean, quietCapable: Boolean) {
            ready += remoteSessionId to acquisitionCapable
            quiet += quietCapable
        }
        override fun handleAcquisition(remoteSessionId: String, type: PeerV2Frame.Type, fields: Map<String, Any?>) { acquisitions += type }
        override fun onEnded(reason: String) { ended += reason }
    }

    private var now = 0L
    private val a = Side(idA, sessionA)
    private val b = Side(idB, sessionB)
    private val central = PeerLeV2Session(true, a) { now }
    private val peripheral = PeerLeV2Session(false, b) { now }

    private fun pump() {
        var guard = 0
        while ((a.outbox.isNotEmpty() || b.outbox.isNotEmpty()) && guard++ < 100) {
            a.outbox.removeFirstOrNull()?.let { peripheral.onRecord(it) }
            b.outbox.removeFirstOrNull()?.let { central.onRecord(it) }
        }
    }

    private fun handshake() { central.start(); pump() }

    @Test fun `two sessions back to back reach ready on both sides`() {
        handshake()
        assertTrue(central.isReady)
        assertTrue(peripheral.isReady)
        assertEquals(listOf(sessionB to true), a.ready)
        assertEquals(listOf(sessionA to true), b.ready)
        assertEquals(listOf(idB), a.bound)
        assertEquals(listOf(idA), b.bound)
        assertTrue(a.ended.isEmpty() && b.ended.isEmpty())
    }

    @Test fun `a hello that does not echo our connection identity fails the central`() {
        central.start()
        a.outbox.clear()
        val hello = PeerV2Message.encode(PeerV2Frame.Type.HELLO, mapOf(
            "installationId" to idB, "sessionId" to sessionB, "capabilities" to PeerLeV2Session.CAPABILITIES,
            "initiatorId" to idA, "connectionId" to "00000000-0000-4000-8000-0000000000c2"))
        central.onRecord(hello.encode())
        assertEquals(listOf("v2_connection_identity_mismatch"), a.ended)
        assertFalse(central.isReady)
    }

    @Test fun `the peripheral requires hello first`() {
        val snapshot = PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, a.localSnapshot())
        peripheral.onRecord(snapshot.encode())
        assertEquals(listOf("hello_required"), b.ended)
    }

    @Test fun `a peer that is not authorized fails the session`() {
        b.authorized = false
        handshake()
        assertEquals(listOf("unauthorized_coordination_peer"), b.ended)
        assertFalse(peripheral.isReady)
        assertTrue(b.ready.isEmpty())
    }

    @Test fun `acquire frames need the acquire capability and otherwise reach the coordinator`() {
        handshake()
        val cancel = PeerV2Message.encode(PeerV2Frame.Type.ACQUIRE_CANCEL, mapOf(
            "requestId" to idA, "requesterId" to idA, "requesterSessionId" to sessionA,
            "membershipDigest" to "0".repeat(64)))
        peripheral.onRecord(cancel.encode())
        assertEquals(listOf(PeerV2Frame.Type.ACQUIRE_CANCEL), b.acquisitions)

        // A peer whose HELLO lacks acquire-v1 may not send them.
        val c = Side(idB, sessionB)
        val noCap = PeerLeV2Session(false, c) { now }
        noCap.onRecord(PeerV2Message.encode(PeerV2Frame.Type.HELLO, mapOf(
            "installationId" to idA, "sessionId" to sessionA, "capabilities" to listOf("snapshot"),
            "initiatorId" to idA, "connectionId" to "00000000-0000-4000-8000-0000000000c1")).encode())
        noCap.onRecord(cancel.encode())
        assertEquals(listOf("peer_missing_acquire_v1"), c.ended)
        assertTrue(c.acquisitions.isEmpty())
    }

    // Both sides advertise quiet-v1 (PeerV2Message.LOCAL_CAPABILITIES): the idle probe replaces the 10 s PING.
    @Test fun `a quiet session sends nothing in its first silent minute`() {
        handshake()
        now += PeerLeV2Session.HEARTBEAT_MS
        central.tick(); peripheral.tick()
        assertTrue(a.outbox.isEmpty() && b.outbox.isEmpty())
        now += PeerIdleProbe.IDLE_PROBE_MS - PeerLeV2Session.HEARTBEAT_MS - 1
        central.tick()
        assertTrue(a.outbox.isEmpty())
    }

    @Test fun `two unanswered idle probes end the session`() {
        handshake()
        now += PeerIdleProbe.IDLE_PROBE_MS
        central.tick()
        assertEquals(1, a.outbox.size)          // probe sent
        a.outbox.clear()                        // ... and lost
        now += PeerIdleProbe.PROBE_RETRY_MS
        central.tick()
        assertEquals(1, a.outbox.size)          // one more probe
        a.outbox.clear()
        assertTrue(a.ended.isEmpty())
        now += PeerIdleProbe.PROBE_RETRY_MS
        central.tick()
        assertEquals(listOf("heartbeat_timeout"), a.ended)
        assertFalse(central.isReady)
    }

    @Test fun `an answered idle probe keeps the session up and delivers the snapshot`() {
        handshake()
        assertEquals(true, a.quiet.last())
        val before = a.snapshots
        repeat(4) {
            now += PeerIdleProbe.IDLE_PROBE_MS
            central.tick(); peripheral.tick(); pump()
        }
        assertTrue(a.ended.isEmpty() && b.ended.isEmpty() && central.isReady)
        assertTrue(a.snapshots > before)
    }

    @Test fun `no initial pong within five seconds ends the handshake`() {
        central.start()
        a.outbox.clear()
        now += PeerLeV2Session.HANDSHAKE_TIMEOUT_MS - 1
        central.tick()
        assertTrue(a.ended.isEmpty())
        now += 1
        central.tick()
        assertEquals(listOf("handshake_timeout"), a.ended)
    }

    @Test fun `a repeated hello restarts the peripheral handshake`() {
        handshake()
        central.end()
        val retry = PeerLeV2Session(true, a) { now }
        retry.start()
        a.outbox.removeFirstOrNull()?.let { peripheral.onRecord(it) }
        assertTrue(b.ended.isEmpty())
        assertFalse(peripheral.isReady)
        while (a.outbox.isNotEmpty() || b.outbox.isNotEmpty()) {
            a.outbox.removeFirstOrNull()?.let { peripheral.onRecord(it) }
            b.outbox.removeFirstOrNull()?.let { retry.onRecord(it) }
        }
        assertTrue(peripheral.isReady && retry.isReady)
    }

    @Test fun `frames larger than one record reach the decoder as a single payload`() {
        handshake()
        val big = PeerV2Frame.Frame(PeerV2Frame.Type.SNAPSHOT, ByteArray(10))
        assertEquals(PeerV2Frame.HEADER_SIZE + 10, big.encode().size)
        // Two frames in one record is not allowed.
        val two = a.localSnapshot().let { PeerV2Message.encode(PeerV2Frame.Type.SNAPSHOT, it).encode() }
        peripheral.onRecord(two + two)
        assertEquals(listOf("v2_record_not_one_frame"), b.ended)
    }
}
