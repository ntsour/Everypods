/*
    EveryPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.utils

import android.content.SharedPreferences
import android.util.Log
import io.automated.ventures.everypods.data.StemAction
import io.automated.ventures.everypods.data.StemPressDefaults

/**
 * One-time migration for the stem long-press default change (v1).
 *
 * Builds before this one fell back to `DIGITAL_ASSISTANT` for
 * `right_long_press_action` in the service when the key was missing, while the
 * Controls UI showed Listening Mode. The default is now
 * [StemPressDefaults.RIGHT_LONG] (CYCLE_NOISE_CONTROL_MODES) everywhere.
 *
 * - Existing install (prefs already hold any key besides the flag) AND key missing
 *   → write the legacy value explicitly so the user's behavior does not change.
 * - Fresh install (no prior keys) → only set the flag; new defaults apply.
 * - Never overwrites an existing key. Runs once (guarded by [FLAG_KEY]).
 *
 * Left long-press needs no entry: its legacy service fallback was already CYCLE.
 */
object StemPressDefaultMigration {
    private const val TAG = "StemDefaults"

    const val FLAG_KEY = "stem_default_migration_v1_done"

    /** Controls keys whose pre-v1 effective (service) default differs from today's. */
    val LEGACY_DEFAULTS: Map<String, StemAction> = mapOf(
        "right_long_press_action" to StemAction.DIGITAL_ASSISTANT,
    )

    data class Plan(
        /** key → action name to write. Empty for fresh installs / already-migrated. */
        val writes: Map<String, String>,
        /** True when the flag must be written (i.e. migration has not run yet). */
        val markDone: Boolean,
        val isExistingInstall: Boolean,
    )

    /** Pure decision logic. [existingKeys] = all keys currently in the `settings` prefs. */
    fun plan(existingKeys: Set<String>): Plan {
        if (FLAG_KEY in existingKeys) return Plan(emptyMap(), markDone = false, isExistingInstall = true)
        val isExistingInstall = existingKeys.any { it != FLAG_KEY }
        val writes = if (isExistingInstall) {
            LEGACY_DEFAULTS
                .filterKeys { it !in existingKeys }
                .filter { (key, legacy) -> legacy != StemPressDefaults.defaultForKey(key) }
                .mapValues { it.value.name }
        } else {
            emptyMap()
        }
        return Plan(writes, markDone = true, isExistingInstall = isExistingInstall)
    }

    /** Apply to the real prefs. Call early (Application.onCreate), before any stem config read. */
    fun run(prefs: SharedPreferences): Plan {
        val plan = plan(prefs.all.keys)
        if (!plan.markDone) return plan
        val editor = prefs.edit()
        plan.writes.forEach { (k, v) -> editor.putString(k, v) }
        editor.putBoolean(FLAG_KEY, true)
        editor.commit()
        Log.i(
            TAG,
            "stem default migration v1: existingInstall=${plan.isExistingInstall} writes=${plan.writes}"
        )
        return plan
    }
}
