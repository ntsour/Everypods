/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEvidenceTest {
    private fun c(d: Double, acc: Double = 20.0, age: Long = 1_000) = PresenceEvidence.classify(d, acc, age, 150, 200)

    @Test fun wholeAccuracyCircleInsideArrivalRadius() {
        assertEquals(Presence.INSIDE, c(100.0, 50.0))
        assertEquals(Presence.UNKNOWN, c(100.0, 51.0))
    }

    @Test fun wholeAccuracyCircleBeyondOuterBoundary() {
        assertEquals(Presence.OUTSIDE, c(250.0, 50.0))
        assertEquals(Presence.UNKNOWN, c(249.0, 50.0))
    }

    @Test fun betweenBoundariesIsAmbiguous() {
        assertEquals(Presence.UNKNOWN, c(175.0, 5.0))
    }

    @Test fun staleFutureOrInaccurateFixesAreUnknown() {
        assertEquals(Presence.UNKNOWN, c(10.0, age = PresenceEvidence.MAX_FIX_AGE_MS + 1))
        assertEquals(Presence.UNKNOWN, c(10.0, age = -1))
        assertEquals(Presence.UNKNOWN, c(10.0, acc = 0.0))
        assertEquals(Presence.UNKNOWN, c(10.0, acc = Double.NaN))
        assertEquals(Presence.UNKNOWN, c(5_000.0, acc = 6_000.0))
    }

    @Test fun requestsAreBoundedNoLoop() {
        assertTrue(PresenceEvidence.canRequest(0, null, inFlight = false))
        assertFalse(PresenceEvidence.canRequest(0, null, inFlight = true))
        assertFalse(PresenceEvidence.canRequest(PresenceEvidence.FIX_COOLDOWN_MS - 1, 0, inFlight = false))
        assertTrue(PresenceEvidence.canRequest(PresenceEvidence.FIX_COOLDOWN_MS, 0, inFlight = false))
    }
}
