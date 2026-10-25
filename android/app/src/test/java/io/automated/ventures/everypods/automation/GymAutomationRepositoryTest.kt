/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GymAutomationRepositoryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun ruleRoundTripsAndBumpsRevisionOnChange() {
        val saved = GymAutomationRepository.saveRule(ctx, GymAutomationRule(label = "Gym", latitude = 40.4, longitude = -3.7, enabled = true))
        assertEquals(saved, GymAutomationRepository.rule(ctx))
        val edited = GymAutomationRepository.saveRule(ctx, saved.copy(radiusMeters = 250))
        assertEquals(saved.revision + 1, edited.revision)
        assertEquals(edited.revision, GymAutomationRepository.saveRule(ctx, edited).revision)
    }

    @Test fun ruleWithoutLocationCannotBeEnabledAndRadiusIsClamped() {
        val saved = GymAutomationRepository.saveRule(ctx, GymAutomationRule(enabled = true, radiusMeters = 5))
        assertFalse(saved.enabled)
        assertEquals(GymAutomationRule.MIN_RADIUS_M, saved.radiusMeters)
        assertEquals(150, GymAutomationRule(radiusMeters = 100).exitRadiusMeters)
        assertEquals(1250, GymAutomationRule(radiusMeters = 1000).exitRadiusMeters)
    }

    @Test fun malformedStorageFallsBackToDefaults() {
        assertEquals(Journal(), GymAutomationRepository.decodeJournal("{not json"))
        assertFalse(GymAutomationRepository.decodeRule("garbage").enabled)
    }

    @Test fun journalRoundTripsForProcessDeath() {
        val j = Journal(3, Presence.INSIDE, "v", "b", 42L, gymWasOn = false, ownsGym = true, ownsHr = true,
            applied = true, gymSuppressed = true, hrSuppressed = false, locationUnavailable = true)
        GymAutomationRepository.saveJournal(ctx, j)
        assertEquals(j, GymAutomationRepository.journal(ctx))
    }
}
