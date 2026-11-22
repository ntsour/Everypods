package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerLeFragmentsTest {
    private fun payload(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun roundTrip(size: Int) {
        val original = payload(size)
        val fragments = PeerLeFragments.split(0x05, 77, original)!!
        assertTrue(fragments.all { it.size <= PeerGattRecords.MAX_PAYLOAD })
        val reassembler = PeerLeFragments()
        var result: PeerLeFragments.Result = PeerLeFragments.Result.Pending
        fragments.forEach { result = reassembler.accept(it, 0) }
        val complete = result as PeerLeFragments.Result.Complete
        assertEquals(0x05, complete.innerType)
        assertArrayEquals(original, complete.payload)
        assertEquals(0, reassembler.pendingCount)
    }

    @Test fun `round trips at the record boundaries and at 128 KiB`() {
        listOf(0, 1, PeerGattRecords.MAX_PAYLOAD, PeerGattRecords.MAX_PAYLOAD + 1, 128 * 1024).forEach(::roundTrip)
    }

    @Test fun `a payload at the message limit splits and one above it is refused`() {
        roundTrip(PeerLeFragments.MAX_MESSAGE)
        assertNull(PeerLeFragments.split(0x05, 1, payload(PeerLeFragments.MAX_MESSAGE + 1)))
    }

    @Test fun `an out of order or unknown continuation is rejected`() {
        val fragments = PeerLeFragments.split(0x05, 9, payload(10_000))!!
        val reassembler = PeerLeFragments()
        assertTrue(reassembler.accept(fragments[1], 0) is PeerLeFragments.Result.Rejected)
        assertEquals(PeerLeFragments.Result.Pending, reassembler.accept(fragments[0], 0))
        assertTrue(reassembler.accept(fragments[2], 0) is PeerLeFragments.Result.Rejected)
        assertEquals(0, reassembler.pendingCount)
    }

    @Test fun `an inconsistent count drops the message`() {
        val fragments = PeerLeFragments.split(0x05, 9, payload(10_000))!!
        val reassembler = PeerLeFragments()
        reassembler.accept(fragments[0], 0)
        val bad = fragments[1].copyOf().also { it[6] = (it[6] + 1).toByte() }
        assertTrue(reassembler.accept(bad, 0) is PeerLeFragments.Result.Rejected)
        assertEquals(0, reassembler.pendingCount)
    }

    @Test fun `an incomplete message is dropped ten seconds after its first fragment`() {
        val fragments = PeerLeFragments.split(0x05, 9, payload(10_000))!!
        val reassembler = PeerLeFragments()
        reassembler.accept(fragments[0], 1_000)
        reassembler.expire(1_000 + PeerLeFragments.TIMEOUT_MS)
        assertEquals(1, reassembler.pendingCount)
        reassembler.expire(1_001 + PeerLeFragments.TIMEOUT_MS)
        assertEquals(0, reassembler.pendingCount)
        // A late fragment cannot revive it.
        assertTrue(reassembler.accept(fragments[1], 20_000) is PeerLeFragments.Result.Rejected)
    }

    @Test fun `a message growing past the limit is dropped and nested fragments are refused`() {
        val reassembler = PeerLeFragments()
        val huge = ByteArray(PeerLeFragments.HEADER + PeerLeFragments.CHUNK)
        val count = 100
        var rejected = false
        for (index in 0 until count) {
            huge[0] = 0x05; huge[1] = 0; huge[2] = 1
            huge[3] = (index ushr 8).toByte(); huge[4] = index.toByte(); huge[5] = 0; huge[6] = count.toByte()
            if (reassembler.accept(huge.copyOf(), 0) is PeerLeFragments.Result.Rejected) { rejected = true; break }
        }
        assertTrue(rejected)
        val nested = byteArrayOf(PeerGattRecords.FRAG.toByte(), 0, 2, 0, 0, 0, 1, 1)
        assertTrue(PeerLeFragments().accept(nested, 0) is PeerLeFragments.Result.Rejected)
        assertNotNull(PeerLeFragments.split(0x09, 3, ByteArray(5)))
        assertNull(PeerLeFragments.split(PeerGattRecords.FRAG, 3, ByteArray(5)))
    }
}
