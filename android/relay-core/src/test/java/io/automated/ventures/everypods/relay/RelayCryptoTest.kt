package io.automated.ventures.everypods.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun session(): Pair<RelayAead, RelayAead> {
        val src = X25519.generate(); val rcv = X25519.generate()
        val sp = X25519.rawPublic(src); val rp = X25519.rawPublic(rcv)
        val t = RelaySessionKeys.transcript(sp, rp)
        val ks = RelaySessionKeys.derive(listOf(X25519.agree(src.private, rp)), t)
        val kr = RelaySessionKeys.derive(listOf(X25519.agree(rcv.private, sp)), t)
        assertArrayEquals(ks.sourceToReceiver, kr.sourceToReceiver)
        assertArrayEquals(ks.receiverToSource, kr.receiverToSource)
        assertFalse(ks.sourceToReceiver.contentEquals(ks.receiverToSource))
        return RelayAead(ks, RelayRole.SOURCE) to RelayAead(kr, RelayRole.RECEIVER)
    }

    private fun p(seq: Long) = RelayPacket(seq = seq, timestamp = seq * 960, payload = "frame$seq".toByteArray())

    @Test fun hkdfRfc5869Case1() {
        val okm = Hkdf.derive(hex("000102030405060708090a0b0c"), hex("0b".repeat(22)), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test fun x25519AgreementIsSymmetric() {
        val a = X25519.generate(); val b = X25519.generate()
        assertArrayEquals(X25519.agree(a.private, X25519.rawPublic(b)), X25519.agree(b.private, X25519.rawPublic(a)))
        assertEquals(32, X25519.rawPublic(a).size)
    }

    @Test(expected = SecurityException::class) fun lowOrderPointRejected() {
        X25519.agree(X25519.generate().private, ByteArray(32))
    }

    @Test fun sealOpenBothDirections() {
        val (s, r) = session()
        val got = r.open(s.seal(p(1)))
        assertEquals(OpenResult.Ok(p(1)), got)
        assertEquals(OpenResult.Ok(p(5)), s.open(r.seal(p(5))))
    }

    @Test fun ciphertextHidesPayload() {
        val (s, _) = session()
        val d = s.seal(p(1))
        assertEquals(RelayPacket.HEADER_SIZE + "frame1".length + 16, d.size)
        assertFalse(String(d, Charsets.ISO_8859_1).contains("frame1"))
    }

    @Test fun tamperedPayloadHeaderOrTagRejected() {
        val (s, r) = session()
        val d = s.seal(p(1))
        for (i in listOf(RelayPacket.HEADER_SIZE, d.size - 1, 2 /* flags in AAD */, 12 /* timestamp */)) {
            val t = d.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertTrue("byte $i", r.open(t) is OpenResult.Rejected)
        }
        assertTrue(r.open(d) is OpenResult.Ok) // original still accepted: failures don't advance window
    }

    @Test fun replayRejected() {
        val (s, r) = session()
        val d = s.seal(p(1))
        assertTrue(r.open(d) is OpenResult.Ok)
        assertEquals(OpenResult.Rejected("replay or too old seq=1"), r.open(d))
    }

    @Test fun reorderWithinWindowAccepted_tooOldRejected() {
        val (s, r) = session()
        val d = (0L..2000L).associateWith { s.seal(p(it)) }
        assertTrue(r.open(d[10]!!) is OpenResult.Ok)
        assertTrue(r.open(d[3]!!) is OpenResult.Ok) // late but in window
        assertTrue(r.open(d[1500]!!) is OpenResult.Ok)
        assertTrue(r.open(d[600]!!) is OpenResult.Ok) // 900 behind < 1024
        assertTrue(r.open(d[400]!!) is OpenResult.Rejected) // 1100 behind: outside window
        assertTrue(r.open(d[600]!!) is OpenResult.Rejected) // duplicate
        assertTrue(r.open(d[1499]!!) is OpenResult.Ok)
    }

    @Test fun wrongKeyAndReflectionRejected() {
        val (s, _) = session()
        val (_, otherReceiver) = session()
        assertTrue(otherReceiver.open(s.seal(p(1))) is OpenResult.Rejected)
        val (s2, _) = session()
        // Reflection: source's own datagram fed back to a source must fail (direction byte differs).
        assertTrue(s2.open(s2.seal(p(2))) is OpenResult.Rejected)
    }

    @Test fun nonceReuseRefused() {
        val (s, _) = session()
        s.seal(p(4))
        try { s.seal(p(4)); throw AssertionError("expected refusal") } catch (_: IllegalArgumentException) {}
    }

    @Test fun staticKeysChangeSessionKeys() {
        val e1 = X25519.generate(); val e2 = X25519.generate()
        val t = RelaySessionKeys.transcript(X25519.rawPublic(e1), X25519.rawPublic(e2))
        val dh = X25519.agree(e1.private, X25519.rawPublic(e2))
        val a = RelaySessionKeys.derive(listOf(dh), t)
        val b = RelaySessionKeys.derive(listOf(dh, ByteArray(32) { 7 }), t)
        assertNotEquals(a.sourceToReceiver.toList(), b.sourceToReceiver.toList())
    }

    @Test fun replayWindowUnit() {
        val w = ReplayWindow(8)
        assertTrue(w.check(0)); w.commit(0)
        assertFalse(w.check(0))
        w.commit(20)
        assertFalse(w.check(12)) // 8 behind
        assertTrue(w.check(13)); w.commit(13); assertFalse(w.check(13))
        assertFalse(w.check(-1))
    }
}
