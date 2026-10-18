package io.automated.ventures.everypods.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFramerClockPskTest {
    @Test fun framerEmitsExactFrames() {
        val f = PcmFramer(4)
        val got = mutableListOf<List<Short>>()
        f.push(shortArrayOf(1, 2, 3)) { got += it.toList() }
        f.push(shortArrayOf(4, 5, 6, 7, 8, 9), 6) { got += it.toList() }
        assertEquals(listOf(listOf<Short>(1, 2, 3, 4), listOf<Short>(5, 6, 7, 8)), got)
    }

    @Test fun clockSyncUsesMinRttSample() {
        val c = ClockSync()
        c.add(1_000, 501_000, 1_200) // rtt 200, offset 500_000-100 = 499_900
        c.add(2_000, 503_000, 2_900) // rtt 900 (queued)
        assertEquals(499_900L, c.offsetMicros)
        assertEquals(200L, c.rttMicros)
    }

    @Test fun pskSessionAgreesAndWrongPskFails() {
        val a = X25519.generate(); val b = X25519.generate()
        val ap = X25519.rawPublic(a); val bp = X25519.rawPublic(b)
        val psk = DevPsk.fromPassphrase("correct horse")
        val ks = DevPsk.sessionKeys(X25519.agree(a.private, bp), psk, ap, bp)
        val kr = DevPsk.sessionKeys(X25519.agree(b.private, ap), psk, ap, bp)
        assertArrayEquals(ks.sourceToReceiver, kr.sourceToReceiver)
        val wrong = DevPsk.sessionKeys(X25519.agree(b.private, ap), DevPsk.fromPassphrase("battery staple"), ap, bp)
        val src = RelayAead(ks, RelayRole.SOURCE)
        val rcv = RelayAead(wrong, RelayRole.RECEIVER)
        assertTrue(rcv.open(src.seal(RelayPacket(seq = 0, timestamp = 0, payload = byteArrayOf(1)))) is OpenResult.Rejected)
    }
}
