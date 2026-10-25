/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.automation

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GymAutomationRepositoryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun zone(id: String, lat: Double = 40.4, radius: Int = 160) = GymZone(id = id, name = id, latitude = lat, longitude = -3.7, radiusMeters = radius)

    @Test fun ruleRoundTripsAndBumpsRevisionOnChange() {
        val saved = GymAutomationRepository.saveRule(ctx, GymAutomationRule(zones = listOf(zone("a")), enabled = true))
        assertEquals(saved, GymAutomationRepository.rule(ctx))
        val edited = GymAutomationRepository.saveRule(ctx, saved.copy(zones = listOf(saved.zones[0].copy(radiusMeters = 240))))
        assertEquals(saved.revision + 1, edited.revision)
        assertEquals(saved.zones[0].revision + 1, edited.zones[0].revision)
        assertEquals(edited.revision, GymAutomationRepository.saveRule(ctx, edited).revision)
    }

    @Test fun renameKeepsZoneRevisionSoGeofencesAndVisitAreUntouched() {
        val saved = GymAutomationRepository.saveRule(ctx, GymAutomationRule(zones = listOf(zone("a"))))
        val renamed = GymAutomationRepository.saveRule(ctx, saved.copy(zones = listOf(saved.zones[0].copy(name = "Downtown"))))
        assertEquals(saved.zones[0].revision, renamed.zones[0].revision)
        assertEquals(saved.zoneRevisions, renamed.zoneRevisions)
    }

    @Test fun ruleWithoutLocationCannotBeEnabledAndRadiusIsClamped() {
        val saved = GymAutomationRepository.saveRule(ctx, GymAutomationRule(enabled = true, zones = listOf(GymZone(id = "x", radiusMeters = 5))))
        assertFalse(saved.enabled)
        assertEquals(GymAutomationRule.MIN_RADIUS_M, saved.zones[0].radiusMeters)
        val big = GymAutomationRepository.saveRule(ctx, saved.copy(zones = listOf(zone("y", radius = 5000))))
        assertEquals(GymAutomationRule.MAX_RADIUS_M, big.zones[0].radiusMeters)
    }

    @Test fun zoneCountIsCapped() {
        val many = (1..15).map { zone("z$it") }
        val saved = GymAutomationRepository.saveRule(ctx, GymAutomationRule(zones = many))
        assertEquals(GymAutomationRule.MAX_ZONES, saved.zones.size)
        assertFalse(saved.canAddZone)
    }

    @Test fun geofenceRadiiRespectPlayServicesMinimum() {
        assertEquals(100, GymZone(radiusMeters = 20).geofenceRadiusMeters)
        assertEquals(150, GymZone(radiusMeters = 20).exitRadiusMeters)
        assertTrue(GymZone(radiusMeters = 80).isBelowReliableRadius)
        assertEquals(150, GymZone(radiusMeters = 100).exitRadiusMeters)
        assertEquals(160 + 50, GymZone(radiusMeters = 160).exitRadiusMeters)
        assertEquals(375, GymZone(radiusMeters = 300).exitRadiusMeters)
        assertFalse(GymZone(radiusMeters = 100).isBelowReliableRadius)
    }

    @Test fun schemaOneSingleZoneMigratesWithSameIdRevisionAndSnappedRadius() {
        val v1 = """{"schema":1,"id":"rule-1","revision":7,"enabled":true,"label":"","lat":40.4,"lon":-3.7,"radius":150,"hr":false,"restore":true}"""
        val r = GymAutomationRepository.decodeRule(v1)
        assertEquals(1, r.zones.size)
        val z = r.zones[0]
        assertEquals("rule-1", z.id); assertEquals(7L, z.revision); assertEquals(160, z.radiusMeters)
        assertTrue(r.enabled); assertFalse(r.hrActionEnabled)
        // Same geofence request id shape as schema 1, so already-registered fences still parse.
        assertEquals("gym:rule-1:7:in", GymGeofenceRegistrar.innerId(z))
        assertEquals(GymGeofenceRegistrar.Parsed("rule-1", 7, false), GymGeofenceRegistrar.parse(r, "gym:rule-1:7:out"))
        assertEquals(300, GymAutomationRepository.decodeRule(v1.replace("\"radius\":150", "\"radius\":1000")).zones[0].radiusMeters)
        assertEquals(300, GymAutomationRepository.decodeRule(v1.replace("\"radius\":150", "\"radius\":500")).zones[0].radiusMeters)
        assertEquals(100, GymAutomationRepository.decodeRule(v1.replace("\"radius\":150", "\"radius\":100")).zones[0].radiusMeters)
        // Schema 1 without a location migrates to an empty list.
        assertTrue(GymAutomationRepository.decodeRule("""{"schema":1,"id":"r","revision":1,"enabled":false,"radius":250}""").zones.isEmpty())
    }

    @Test fun legacyJournalInsideKeepsTheMigratedZone() {
        val rule = GymAutomationRepository.decodeRule("""{"schema":1,"id":"rule-1","revision":3,"enabled":true,"lat":1.0,"lon":2.0,"radius":250}""")
        val legacy = GymAutomationRepository.decodeJournal("""{"rev":3,"presence":"INSIDE","visit":"v","boot":"b","applied":true}""")
        assertTrue(legacy.legacyInside)
        val migrated = GymAutomationRepository.migrateJournal(legacy, rule)
        assertEquals(mapOf("rule-1" to 3L), migrated.insideZones)
        assertFalse(migrated.legacyInside)
    }

    @Test fun malformedStorageFallsBackToDefaults() {
        assertEquals(Journal(), GymAutomationRepository.decodeJournal("{not json"))
        assertFalse(GymAutomationRepository.decodeRule("garbage").enabled)
    }

    @Test fun journalRoundTripsForProcessDeath() {
        val j = Journal(3, Presence.INSIDE, "v", "b", 42L, gymWasOn = false, ownsGym = true, ownsHr = true,
            applied = true, gymSuppressed = true, hrSuppressed = false, locationUnavailable = true,
            insideZones = mapOf("a" to 2L, "b" to 5L))
        GymAutomationRepository.saveJournal(ctx, j)
        assertEquals(j, GymAutomationRepository.journal(ctx))
    }
}
