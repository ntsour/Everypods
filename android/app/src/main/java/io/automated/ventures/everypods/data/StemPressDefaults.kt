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

package io.automated.ventures.everypods.data

import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType

/**
 * Single source of truth for the AirPods Controls stem-press defaults
 * (`{left,right}_{single,double,triple,long}_press_action`).
 *
 * Used by the service config / prefs fallbacks, first-run seeding, the
 * ViewModel, and the Controls / Gym Press Actions UI. Pure Kotlin (no Android)
 * so it can be unit-tested on the JVM.
 */
object StemPressDefaults {
    const val SIDE_LEFT = "left"
    const val SIDE_RIGHT = "right"

    val LEFT_SINGLE: StemAction = StemAction.PLAY_PAUSE
    val RIGHT_SINGLE: StemAction = StemAction.PLAY_PAUSE
    val LEFT_DOUBLE: StemAction = StemAction.NEXT_TRACK
    val RIGHT_DOUBLE: StemAction = StemAction.NEXT_TRACK
    val LEFT_TRIPLE: StemAction = StemAction.PREVIOUS_TRACK
    val RIGHT_TRIPLE: StemAction = StemAction.PREVIOUS_TRACK
    val LEFT_LONG: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES
    val RIGHT_LONG: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES

    /**
     * Long-press action the AirPods firmware performs natively (listening-mode
     * cycling). Not a "default" — it is the value that lets the app leave long
     * press un-customized in the stem config packet.
     */
    val FIRMWARE_HANDLED_LONG_PRESS: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES

    private fun normSide(side: String): String =
        if (side.lowercase() == SIDE_LEFT) SIDE_LEFT else SIDE_RIGHT

    /** Default action for one bud + press type. Unknown side → right. */
    fun defaultFor(side: String, type: StemPressType): StemAction {
        val left = normSide(side) == SIDE_LEFT
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (left) LEFT_SINGLE else RIGHT_SINGLE
            StemPressType.DOUBLE_PRESS -> if (left) LEFT_DOUBLE else RIGHT_DOUBLE
            StemPressType.TRIPLE_PRESS -> if (left) LEFT_TRIPLE else RIGHT_TRIPLE
            StemPressType.LONG_PRESS -> if (left) LEFT_LONG else RIGHT_LONG
        }
    }

    /** Pref key, e.g. `left_long_press_action`. */
    fun prefKey(side: String, type: StemPressType): String =
        "${normSide(side)}_${type.name.lowercase()}_action"

    /** All 8 Controls keys → default action (first-run seeding). */
    val allDefaults: Map<String, StemAction> by lazy {
        buildMap {
            for (side in listOf(SIDE_LEFT, SIDE_RIGHT)) {
                for (type in StemPressType.entries) put(prefKey(side, type), defaultFor(side, type))
            }
        }
    }

    /** Default for a Controls pref key, or null when [key] is not one. */
    fun defaultForKey(key: String): StemAction? = allDefaults[key]
}
