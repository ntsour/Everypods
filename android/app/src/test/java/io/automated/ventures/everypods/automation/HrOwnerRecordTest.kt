/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HrOwnerRecordTest {
    @Test fun roundTripsEverySource() {
        HeartRateSource.entries.forEach {
            val r = HrOwnerRecord(it, "7", 123L)
            assertEquals(r, HrOwnerRecord.decode(HrOwnerRecord.encode(r)))
        }
    }

    @Test fun malformedOrMissingOwnsNothing() {
        assertNull(HrOwnerRecord.decode(null))
        assertNull(HrOwnerRecord.decode("{"))
        assertNull(HrOwnerRecord.decode("""{"source":"NOPE"}"""))
        assertNull(HrOwnerRecord.encode(null))
    }

    @Test fun liveOwnerNeedsARunningSessionInThisBoot() {
        val r = HrOwnerRecord(HeartRateSource.AUTOMATION, "7", 1)
        assertEquals(HeartRateSource.AUTOMATION, HrOwnerRecord.liveOwner(r, active = true, bootId = "7"))
        // Process death / stop: nothing is running, so the stale record owns nothing.
        assertNull(HrOwnerRecord.liveOwner(r, active = false, bootId = "7"))
        // Reboot: previous boot's owner never carries over.
        assertNull(HrOwnerRecord.liveOwner(r, active = true, bootId = "8"))
        assertNull(HrOwnerRecord.liveOwner(null, active = true, bootId = "7"))
    }
}
