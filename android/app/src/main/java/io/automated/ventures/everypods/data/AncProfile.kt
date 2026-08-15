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

import java.util.UUID

/**
 * A single auto-ANC rule. Time-based for now (start/end minutes since
 * midnight). Wi-Fi and other trigger types may be added later by extending
 * this class with a discriminator.
 *
 * Window may wrap past midnight (e.g. 22:00 → 07:00). When two profiles
 * overlap, the engine picks the first match in declaration order.
 */
data class AncProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startMinute: Int,         // minutes since midnight
    val endMinute: Int,
    val ancMode: NoiseControlMode,
) {
    fun matches(nowMinutes: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            nowMinutes in startMinute until endMinute
        } else {
            nowMinutes >= startMinute || nowMinutes < endMinute
        }
    }

    fun serialize(): String =
        "$id|${name.replace("|", "/")}|$startMinute|$endMinute|${ancMode.name}"

    companion object {
        fun deserialize(s: String): AncProfile? {
            val parts = s.split("|", limit = 5)
            if (parts.size != 5) return null
            return try {
                AncProfile(
                    id = parts[0],
                    name = parts[1],
                    startMinute = parts[2].toInt(),
                    endMinute = parts[3].toInt(),
                    ancMode = NoiseControlMode.valueOf(parts[4]),
                )
            } catch (e: Exception) { null }
        }
    }
}
