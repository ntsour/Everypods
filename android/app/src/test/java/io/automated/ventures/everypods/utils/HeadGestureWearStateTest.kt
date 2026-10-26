package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.data.*
import org.junit.Assert.*
import org.junit.Test

class HeadGestureWearStateTest {
    @Test fun `early wear report survives handshake until Connected allows automatic tracking`() {
        val wear = HeadGestureWearState()
        val settings = HeadGestureSettings(enabled = true, normal = mapOf(HeadGesture.NOD to StemAction.NEXT_TRACK))
        val stream = HeadGestureStream()
        wear.report(generation = 4, currentGeneration = 4, socketInstalled = true, worn = true)
        // The engine is still handshaking; the socket and its report are already valid.
        wear.reconcile(currentGeneration = 4, socketInstalled = true)
        var owners = HeadGesturePolicy.owners(settings, HeadGestureContext.NORMAL, false, wear.isWorn(4), false)
        assertFalse(owners.track)
        assertEquals(HeadGestureStream.Command.NONE, stream.reconcile(owners.track, 4, true, 0))
        // A later Connected publication must not require a second ear event or an app restart.
        wear.reconcile(currentGeneration = 4, socketInstalled = true)
        owners = HeadGesturePolicy.owners(settings, HeadGestureContext.NORMAL, true, wear.isWorn(4), false)
        assertTrue(owners.track)
        assertEquals(HeadGestureStream.Command.START, stream.reconcile(owners.track, 4, true, 100))
    }
    @Test fun `new socket requires its own wear report`() {
        val wear = HeadGestureWearState()
        wear.report(4, 4, true, true)
        wear.reconcile(5, true)
        assertFalse(wear.isWorn(5))
        assertNull(wear.reportGeneration)
        wear.report(5, 5, true, true)
        assertTrue(wear.isWorn(5))
    }
    @Test fun `late old socket report cannot overwrite current wear state`() {
        val wear = HeadGestureWearState()
        wear.report(5, 5, true, false)
        wear.report(4, 5, true, true)
        assertEquals(5L, wear.reportGeneration)
        assertFalse(wear.isWorn(5))
    }
    @Test fun `disconnect discards wear evidence and rejects a queued report`() {
        val wear = HeadGestureWearState()
        wear.report(4, 4, true, true)
        wear.reconcile(4, false)
        wear.report(4, 4, false, true)
        assertFalse(wear.isWorn(4))
        assertNull(wear.reportGeneration)
    }
    @Test fun `ear removal disables gesture execution on the same connection`() {
        val wear = HeadGestureWearState()
        wear.report(4, 4, true, true)
        wear.report(4, 4, true, false)
        assertFalse(wear.isWorn(4))
        wear.report(4, 4, true, true)
        assertTrue(wear.isWorn(4))
    }
    @Test fun `unknown wear never permits gestures`() {
        val wear = HeadGestureWearState()
        wear.reconcile(4, true)
        assertFalse(wear.isWorn(4))
    }
    @Test fun `removing one bud keeps wearing true but changes the motion epoch evidence`() {
        val wear = HeadGestureWearState()
        wear.report(4, 4, true, 3)
        val bothRevision = wear.revision
        wear.report(4, 4, true, 1)
        assertTrue(wear.isWorn(4))
        assertTrue(wear.revision > bothRevision)
        val singleRevision = wear.revision
        wear.report(4, 4, true, 1)
        assertEquals(singleRevision, wear.revision)
        wear.report(4, 4, true, 2)
        assertTrue(wear.isWorn(4))
        assertTrue(wear.revision > singleRevision)
        wear.report(4, 4, true, 0)
        assertTrue(wear.isKnown(4))
        assertFalse(wear.isWorn(4))
    }
    @Test fun `missing wear reports get bounded refreshes and known state ends polling`() {
        val wear = HeadGestureWearState()
        wear.reconcile(4, true)
        assertTrue(wear.shouldRefresh(4, 0, true, true))
        assertFalse(wear.shouldRefresh(4, 3000, true, true))
        assertTrue(wear.shouldRefresh(4, 4000, true, true))
        assertTrue(wear.shouldRefresh(4, 10000, true, true))
        assertFalse(wear.shouldRefresh(4, 100000, true, true))
        wear.report(4, 4, true, 0)
        assertFalse(wear.shouldRefresh(4, 200000, true, true))
    }
    @Test fun `BLE hint requests AACP refresh without granting wear evidence`() {
        val wear = HeadGestureWearState()
        wear.reconcile(4, true)
        assertTrue(wear.shouldRefresh(4, 0, true, true, hint = true))
        assertFalse(wear.isKnown(4))
        assertFalse(wear.isWorn(4))
        assertFalse(wear.shouldRefresh(4, 500, true, true, hint = true))
        assertTrue(wear.shouldRefresh(4, 1000, true, true, hint = true))
    }
    @Test fun `BLE silence does not invalidate current AACP wear evidence`() {
        val wear = HeadGestureWearState()
        wear.report(4, 4, true, 3)
        repeat(20) { wear.reconcile(4, true) }
        assertTrue(wear.isWorn(4))
        assertEquals(3, wear.wornMask)
    }
    @Test fun `no refresh without a local transport or enabled owner`() {
        val wear = HeadGestureWearState()
        assertFalse(wear.shouldRefresh(4, 0, true, false, true))
        assertFalse(wear.shouldRefresh(4, 0, false, true, true))
    }

}
