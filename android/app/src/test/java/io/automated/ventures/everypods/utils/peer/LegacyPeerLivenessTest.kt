package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.*
import org.junit.Test

class LegacyPeerLivenessTest {
    @Test fun `new connection gets a full response window independent of old peer history`() {
        val old = LegacyPeerLiveness(0L)
        assertTrue(old.expired(100_000L))
        val fresh = LegacyPeerLiveness(100_000L)
        assertFalse(fresh.expired(120_000L))
        assertFalse(fresh.expired(144_999L))
        assertTrue(fresh.expired(145_000L))
    }
    @Test fun `healthy status replies keep a multi-minute connection alive`() {
        val link = LegacyPeerLiveness(0L)
        for (now in 20_000L..600_000L step 20_000L) {
            assertFalse(link.expired(now))
            link.onStatusResponse(now + 150L)
        }
        assertFalse(link.expired(620_000L))
    }
    @Test fun `stale or out of order replies cannot move the clock backwards`() {
        val link = LegacyPeerLiveness(100_000L)
        link.onStatusResponse(130_000L)
        link.onStatusResponse(90_000L)
        assertEquals(5_000L, link.responseAgeMs(135_000L))
        assertFalse(link.expired(174_999L))
        assertTrue(link.expired(175_000L))
    }
    @Test fun `unresponsive link still expires even when another link responds`() {
        val dead = LegacyPeerLiveness(0L)
        val live = LegacyPeerLiveness(0L)
        live.onStatusResponse(40_000L)
        assertTrue(dead.expired(60_000L))
        assertFalse(live.expired(60_000L))
    }
}
