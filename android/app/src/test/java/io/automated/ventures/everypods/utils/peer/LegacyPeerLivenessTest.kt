package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test

class LegacyPeerLivenessTest {
    /** One keep-alive round as CrossDevice runs it: check, then ask. */
    private fun round(link: LegacyPeerLiveness, nowMs: Long): Boolean {
        if (link.staleBeforeRequest(nowMs)) return true
        link.onRequestSent(nowMs)
        return false
    }

    @Test fun `the first round after a long sleep does not close a link that answers`() {
        // 26 Nov trace: the link came up, the tablet slept, its first 20 s round ran 62 s later,
        // and the PC answered 3 ms after the request. The old check closed the link anyway.
        val link = LegacyPeerLiveness(0L)
        assertFalse(round(link, 62_000L))
        link.onStatusResponse(62_003L)
        assertFalse(round(link, 124_000L))
        link.onStatusResponse(124_004L)
        assertFalse(round(link, 600_000L))
    }

    @Test fun `a stretched round cannot expire a request it has not waited a second round for`() {
        val link = LegacyPeerLiveness(0L)
        assertFalse(round(link, 20_000L))
        assertFalse(round(link, 300_000L))  // one unanswered round, however long the sleep
        link.onStatusResponse(300_050L)
        assertFalse(round(link, 320_000L))
    }

    @Test fun `unanswered requests close the link after two rounds and the timeout`() {
        val link = LegacyPeerLiveness(0L)
        assertFalse(round(link, 20_000L))  // asks
        assertFalse(round(link, 40_000L))  // 1 unanswered round
        assertFalse(round(link, 60_000L))  // 2 rounds, but only 40 s since the first request
        assertTrue(round(link, 80_000L))   // 60 s unanswered
    }

    @Test fun `healthy status replies keep a multi-minute connection alive`() {
        val link = LegacyPeerLiveness(0L)
        for (now in 20_000L..600_000L step 20_000L) {
            assertFalse(round(link, now))
            link.onStatusResponse(now + 150L)
        }
    }

    @Test fun `any state packet counts as an answer`() {
        // The PC now sends its state when the link comes up, before any request.
        val link = LegacyPeerLiveness(0L)
        link.onStatusResponse(10L)
        assertFalse(round(link, 20_000L))
        link.onStatusResponse(20_100L)
        assertFalse(round(link, 40_000L))
    }

    @Test fun `stale or out of order replies cannot move the clock backwards`() {
        val link = LegacyPeerLiveness(100_000L)
        link.onStatusResponse(130_000L)
        link.onStatusResponse(90_000L)
        assertEquals(5_000L, link.responseAgeMs(135_000L))
    }

    @Test fun `unresponsive link still expires even when another link responds`() {
        val dead = LegacyPeerLiveness(0L)
        val live = LegacyPeerLiveness(0L)
        for (now in listOf(20_000L, 40_000L, 60_000L)) {
            assertFalse(round(dead, now))
            assertFalse(round(live, now))
            live.onStatusResponse(now + 100L)
        }
        assertTrue(round(dead, 80_000L))
        assertFalse(round(live, 80_000L))
    }
}
