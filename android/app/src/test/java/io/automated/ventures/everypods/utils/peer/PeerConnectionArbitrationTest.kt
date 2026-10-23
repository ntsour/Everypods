package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerConnectionArbitrationTest {
    @Test fun `both peers select same stable connection tuple regardless of arrival order`() {
        val first = PeerConnectionKey("00000000-0000-0000-0000-000000000001", "ffffffff-ffff-ffff-ffff-ffffffffffff")
        val second = PeerConnectionKey("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000001")
        assertEquals(first, PeerConnectionArbitration.preferred(listOf(first, second)))
        assertEquals(first, PeerConnectionArbitration.preferred(listOf(second, first)))
    }
}
