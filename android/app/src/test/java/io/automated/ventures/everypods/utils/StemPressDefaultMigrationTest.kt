package io.automated.ventures.everypods.utils

import android.content.Context
import android.content.SharedPreferences
import io.automated.ventures.everypods.data.StemPressDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StemPressDefaultMigrationTest {

    private lateinit var prefs: SharedPreferences
    private val flag = StemPressDefaultMigration.FLAG_KEY

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // ---- pure plan() ----

    @Test
    fun freshInstallOnlySetsFlag() {
        val plan = StemPressDefaultMigration.plan(emptySet())
        assertTrue(plan.markDone)
        assertFalse(plan.isExistingInstall)
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun upgradeWithMissingRightLongPressPreservesDigitalAssistant() {
        val plan = StemPressDefaultMigration.plan(setOf("name", "automatic_ear_detection"))
        assertTrue(plan.isExistingInstall)
        assertEquals(mapOf("right_long_press_action" to "DIGITAL_ASSISTANT"), plan.writes)
    }

    @Test
    fun upgradeNeverTouchesLeftLongPress() {
        // Legacy left fallback was already CYCLE → nothing to preserve.
        val plan = StemPressDefaultMigration.plan(setOf("name"))
        assertFalse(plan.writes.containsKey("left_long_press_action"))
    }

    @Test
    fun explicitKeyIsNeverOverwrittenInPlan() {
        val plan = StemPressDefaultMigration.plan(setOf("name", "right_long_press_action"))
        assertTrue(plan.markDone)
        assertTrue(plan.writes.isEmpty())
    }

    @Test
    fun alreadyMigratedIsNoOp() {
        val plan = StemPressDefaultMigration.plan(setOf(flag, "name"))
        assertFalse(plan.markDone)
        assertTrue(plan.writes.isEmpty())
    }

    // ---- run() against real SharedPreferences ----

    @Test
    fun runFreshInstallGivesCycleOnBothBuds() {
        StemPressDefaultMigration.run(prefs)
        assertTrue(prefs.getBoolean(flag, false))
        assertFalse(prefs.contains("right_long_press_action"))
        assertFalse(prefs.contains("left_long_press_action"))
        // Service / UI fallback for missing key:
        assertEquals(
            "CYCLE_NOISE_CONTROL_MODES",
            prefs.getString("right_long_press_action", StemPressDefaults.RIGHT_LONG.name)
        )
    }

    @Test
    fun runUpgradeWritesDigitalAssistantWhenKeyMissing() {
        prefs.edit().putString("name", "AirPods Pro").putBoolean("automatic_ear_detection", true).commit()
        StemPressDefaultMigration.run(prefs)
        assertEquals("DIGITAL_ASSISTANT", prefs.getString("right_long_press_action", null))
        assertFalse(prefs.contains("left_long_press_action"))
        assertTrue(prefs.getBoolean(flag, false))
    }

    @Test
    fun runNeverOverwritesExplicitKeys() {
        // Nikos's Pixel: both long presses explicitly saved.
        prefs.edit()
            .putString("name", "AirPods Pro")
            .putString("right_long_press_action", "CYCLE_NOISE_CONTROL_MODES")
            .putString("left_long_press_action", "TOGGLE_GYM_MODE")
            .commit()
        val plan = StemPressDefaultMigration.run(prefs)
        assertTrue(plan.writes.isEmpty())
        assertEquals("CYCLE_NOISE_CONTROL_MODES", prefs.getString("right_long_press_action", null))
        assertEquals("TOGGLE_GYM_MODE", prefs.getString("left_long_press_action", null))
    }

    @Test
    fun runOnlyOnce() {
        prefs.edit().putString("name", "AirPods Pro").commit()
        StemPressDefaultMigration.run(prefs)
        assertEquals("DIGITAL_ASSISTANT", prefs.getString("right_long_press_action", null))
        // User later resets / removes the key; a second run must not write again.
        prefs.edit().remove("right_long_press_action").commit()
        val second = StemPressDefaultMigration.run(prefs)
        assertFalse(second.markDone)
        assertFalse(prefs.contains("right_long_press_action"))
    }

    @Test
    fun resetToDefaultsAfterMigrationGivesCycleOnBothBuds() {
        prefs.edit().putString("name", "AirPods Pro").commit()
        StemPressDefaultMigration.run(prefs)
        // ViewModel.resetPressActionsToDefaults() writes StemPressDefaults.resetValues().
        prefs.edit().apply { StemPressDefaults.resetValues().forEach { (k, v) -> putString(k, v) } }.commit()
        assertEquals("CYCLE_NOISE_CONTROL_MODES", prefs.getString("right_long_press_action", null))
        assertEquals("CYCLE_NOISE_CONTROL_MODES", prefs.getString("left_long_press_action", null))
        StemPressDefaultMigration.run(prefs)
        assertEquals("CYCLE_NOISE_CONTROL_MODES", prefs.getString("right_long_press_action", null))
    }
}
