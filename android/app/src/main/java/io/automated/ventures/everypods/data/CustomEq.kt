/*
    EveryPods - AirPods liberated from Apple's ecosystem
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

import io.automated.ventures.everypods.bluetooth.AACPManager

enum class CustomEqBand { LOW, MID, HIGH }

data class CustomEq(val state: Int, val low: Int, val mid: Int, val high: Int) {

    fun isEnabled(): Boolean {
        return state == 2
    }

    fun toPacket(): ByteArray {
        return byteArrayOf(
            AACPManager.Companion.Opcodes.CUSTOM_EQ, 0x00,
            0x05, 0x00, // length (LE)
            0x01, state.toByte(),
            low.toByte(), mid.toByte(), high.toByte()
        )
    }

    init {
        require(low in 0..100) { "low must be between 0 and 100, was $low" }
        require(mid in 0..100) { "mid must be between 0 and 100, was $mid" }
        require(high in 0..100) { "high must be between 0 and 100, was $high" }
    }
}
