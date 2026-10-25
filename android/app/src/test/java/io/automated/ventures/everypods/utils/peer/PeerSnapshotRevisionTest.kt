package io.automated.ventures.everypods.utils.peer

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerSnapshotRevisionTest {
    @Test fun `reservation preparation and grant do not invalidate reserved ownership revisions`() {
        val revisions = PeerSnapshotRevision()
        val released = mapOf("ownership" to "released", "audioUse" to "idle", "acquisition" to null)
        val revision = revisions.forState(released)
        assertEquals(revision, revisions.forState(released + ("acquisition" to mapOf("phase" to "preparing"))))
        assertEquals(revision, revisions.forState(released + ("acquisition" to mapOf("phase" to "in_progress"))))
        assertEquals(revision + 1, revisions.forState(released + ("ownership" to "held")))
    }

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
