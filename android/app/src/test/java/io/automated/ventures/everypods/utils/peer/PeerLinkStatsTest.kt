package io.automated.ventures.everypods.utils.peer

import io.automated.ventures.everypods.utils.peer.PeerLinkStats.Channel
import org.junit.Assert.*
import org.junit.Test

class PeerLinkStatsTest {
    @Test fun `an empty peer line lists every key with the Windows address format`() {
        assertEquals(
            "STATS peer=98597A1041E9 window_s=3600" +
                " legacy_in=0 legacy_in_bytes=0 legacy_out=0 legacy_out_bytes=0 legacy_dials=0" +
                " legacy_dial_failures=0 legacy_up=0 legacy_down=0 legacy_pings=0" +
                " v2_in=0 v2_in_bytes=0 v2_out=0 v2_out_bytes=0 v2_dials=0" +
                " v2_dial_failures=0 v2_up=0 v2_down=0 v2_pings=0" +
                " le_in=0 le_in_bytes=0 le_out=0 le_out_bytes=0 le_dials=0" +
                " le_dial_failures=0 le_up=0 le_down=0 le_pings=0",
            PeerLinkStats.format("98:59:7a:10:41:e9", 3600, emptyMap()),
        )
    }

    @Test fun `counters, lifetimes and a fresh window`() {
        val stats = PeerLinkStats(1_000L)
        assertNull(stats.takeIfDue(1_000L + PeerLinkStats.REPORT_INTERVAL_MS - 1))
        stats.linkUp("98:59:7A:10:41:E9", Channel.LEGACY)
        stats.frameIn("98:59:7a:10:41:e9", Channel.LEGACY, 4)  // case and colons do not split a peer
        stats.frameOut("98:59:7A:10:41:E9", Channel.LEGACY, 4)
        stats.ping("98:59:7A:10:41:E9", Channel.LEGACY)
        stats.linkDown("98:59:7A:10:41:E9", Channel.LEGACY, 45_000L)
        stats.linkDown("98:59:7A:10:41:E9", Channel.LEGACY, 15_000L)
        stats.frameOut("98:59:7A:10:41:E9", Channel.V2, 200)
        stats.dial("30:E0:44:86:61:2C", Channel.V2, ok = false)

        val lines = stats.takeIfDue(1_000L + PeerLinkStats.REPORT_INTERVAL_MS)!!
        assertEquals(3, lines.size)  // two peers, sorted, then the window line
        assertTrue(lines[0], lines[0].startsWith("STATS peer=30E04486612C window_s=3600"))
        assertTrue(lines[0], lines[0].contains(" v2_dials=1 v2_dial_failures=1 "))
        val pc = lines[1]
        assertTrue(pc, pc.startsWith("STATS peer=98597A1041E9 window_s=3600 legacy_in=1 legacy_in_bytes=4 legacy_out=1"))
        assertTrue(pc, pc.contains(" legacy_up=1 legacy_down=2 legacy_pings=1 legacy_life_min_s=15 legacy_life_avg_s=30 legacy_life_max_s=45"))
        assertTrue(pc, pc.contains(" v2_out=1 v2_out_bytes=200 ") && !pc.contains("v2_life"))
        assertEquals("STATS window_s=3600", lines[2])

        assertEquals(listOf("STATS window_s=60"), stats.take(1_000L + PeerLinkStats.REPORT_INTERVAL_MS + 60_000L))
    }
}
