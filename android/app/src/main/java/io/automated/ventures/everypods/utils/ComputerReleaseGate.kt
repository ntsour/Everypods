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

package io.automated.ventures.everypods.utils

/**
 * Explicit takeover from a computer peer must not connect while the computer
 * is still tearing down its own AirPods link. On 2026-11-20/21 the phone
 * connected the moment it sent REQUEST_DISCONNECT; the Windows client had
 * released within ~0.4 s, but the phone's page landed during the teardown,
 * the AirPods never answered it, and Android only retried after a 30 s
 * timeout (handover took ~41 s instead of ~4 s).
 *
 * The phone therefore waits for each computer holder's AIRPODS_DISCONNECTED
 * (bounded by [ACK_TIMEOUT_MS]) and connects as soon as it arrives.
 * Phone peers keep the immediate connect.
 *
 * The Windows client acks only once its link is actually down (up to 5 s),
 * so [ACK_TIMEOUT_MS] must outlast that: at 2 s the phone gave up while the
 * PC was still tearing down and the handover took 29 s. Because the ack means
 * the link is down, no extra settle delay is added after it.
 */
object ComputerReleaseGate {
    const val ACK_TIMEOUT_MS = 6_000L
    const val POLL_MS = 100L

    /** Online handover peers that hold the AirPods and are computers. */
    fun computerHolders(
        onlineHandoverPeers: Collection<String>,
        holders: Collection<String>,
        isComputer: (String) -> Boolean,
    ): Set<String> {
        val held = holders.map { it.uppercase() }.toSet()
        return onlineHandoverPeers.map { it.uppercase() }.filter { it in held && isComputer(it) }.toSet()
    }
}
