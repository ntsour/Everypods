package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerSnapshotRevisionTest {
    @Test fun `unchanged heartbeat reuses revision while state transition increments it`() {
        val revisions = PeerSnapshotRevision()
        val released = mapOf("ownership" to "released", "audioUse" to "idle")
        val held = mapOf("ownership" to "held", "audioUse" to "media")
        assertEquals(1L, revisions.forState(released))
        assertEquals(1L, revisions.forState(released))
        assertEquals(2L, revisions.forState(held))
        assertEquals(2L, revisions.forState(held))
        assertEquals(3L, revisions.forState(released))
    }
}
