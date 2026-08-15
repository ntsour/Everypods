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
    along with this program, if not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import io.automated.ventures.everypods.services.ServiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// RFCOMM client used when this device acts as the client side of an Android↔Android
// pair (role election: the lower-name device clients to the higher-name peer). Each
// configured peer where we are the client gets its own [PeerLink] — independent
// socket, retry/backoff loop, and keep-alive — so one peer dropping (Doze, range,
// OEM kill) never tears down the links to the others. Exchanges the same 4-byte
// packet protocol used by the Windows tray client and the server side.
object CrossDeviceClient {
    private const val TAG = "CrossDeviceClient"
    private val RFCOMM_UUID = java.util.UUID.fromString("1abbb9a4-10e4-4000-a75c-8953c5471342")

    // Interval for keep-alive pings while an RFCOMM channel is idle.
    // Sends REQUEST_CONNECTION_STATUS so Android's BT power-management doesn't
    // drop the ACL link between handover events.
    private const val KEEPALIVE_INTERVAL_MS = 20_000L

    // While the AirPods are connected to THIS device we must not page any peer:
    // a failed RFCOMM connect pages the BT radio for several seconds, and doing
    // that repeatedly on the same single radio that carries the AirPods
    // A2DP+AACP link causes RF contention that drops the headset. We don't need
    // to reach a peer while we hold the AirPods anyway. Re-check on this
    // interval; resume normal retries once the AirPods are no longer ours.
    private const val HEADSET_HELD_RECHECK_MS = 10_000L

    private const val INITIAL_BACKOFF_MS = 1_500L
    // W5-B1: a COORDINATION link must recover fast — a long backoff has no upside here
    // (unlike a data sync). Capped at 10 s (was 60 s) so a transient drop doesn't leave
    // the mesh blind for up to a minute. retryNow() still short-circuits on ACL_CONNECTED.
    private const val MAX_BACKOFF_MS = 10_000L
    // When SDP lookup fails immediately (peer has no RFCOMM server, e.g. Windows before
    // it starts), back off for 5 min instead of the normal 10s. The answer won't change
    // until the peer starts its service; paging every 10s wastes radio. Once Windows
    // connects to our server, pauseLink / self-renewal takes over completely.
    private const val SDP_BACKOFF_MS = 5 * 60 * 1000L

    /** One per peer we client to. All mutable fields are touched only from the
     *  link's own coroutine except [isConnected]/[socket] (read by send paths) and
     *  [backoffJob] (cancelled by retryNow) — hence @Volatile. */
    private class PeerLink(val mac: String) {
        @Volatile var socket: BluetoothSocket? = null
        @Volatile var isConnected: Boolean = false
        @Volatile var job: Job? = null
        @Volatile var backoffJob: Job? = null
    }

    private val links = ConcurrentHashMap<String, PeerLink>()

    /** True when at least one peer link is live. Kept as a no-arg property for the
     *  existing `isPeerConnected` / UI-poll call-sites. */
    val isConnected: Boolean get() = links.values.any { it.isConnected }

    fun isConnected(mac: String): Boolean = links[mac]?.isConnected == true

    val connectedPeers: Set<String>
        get() = links.values.filter { it.isConnected }.map { it.mac }.toSet()

    /**
     * Reconcile the set of live client links against [peers]: stop links to peers no
     * longer in the set, start links to newly added peers, and leave already-running
     * links untouched. Idempotent — safe to call on every [CrossDevice.init].
     */
    @SuppressLint("MissingPermission")
    fun start(adapter: BluetoothAdapter, peers: Set<String>) {
        (links.keys - peers).forEach { stop(it) }
        peers.forEach { mac -> if (!links.containsKey(mac)) startLink(adapter, mac) }
    }

    @SuppressLint("MissingPermission")
    private fun startLink(adapter: BluetoothAdapter, peerMac: String) {
        val link = PeerLink(peerMac)
        links[peerMac] = link
        link.job = CoroutineScope(Dispatchers.IO).launch {
            var backoff = INITIAL_BACKOFF_MS
            // Set when the most recent connect failed almost instantly (SDP lookup returned
            // no RFCOMM service channel) — means this peer has no server listening. Drives
            // a long backoff so we stop paging a peer that can't accept us.
            var fastFail = false
            while (isActive) {
                // Don't page the peer while we hold the AirPods — the failed
                // RFCOMM connect attempts jam the radio and drop the headset.
                if (ServiceManager.getService()?.isConnected() == true) {
                    Log.d(TAG, "[$peerMac] AirPods connected here — deferring connect to protect the headset link")
                    delay(HEADSET_HELD_RECHECK_MS)
                    continue
                }
                // W5-C1: if this peer is already reachable via an inbound server socket
                // (e.g. Windows connected to our server), opening a redundant outbound
                // client link would just page their radio for no benefit. Sleep and
                // re-check. This makes the pause self-renewing: Windows stays connected
                // inbound → every 5 min we check → defer again → no radio waste.
                // If the inbound link drops, isConnectedTo returns false and we proceed.
                if (CrossDevice.isConnectedTo(peerMac)) {
                    Log.d(TAG, "[$peerMac] already reachable via inbound socket — deferring outbound client (5 min)")
                    delay(5 * 60 * 1000L)
                    continue
                }
                try {
                    val device = adapter.getRemoteDevice(peerMac)
                    // W5 instrumentation: resolve the peer's display name so logs make
                    // MAC↔device identity unambiguous (which MAC is the PC vs each phone).
                    val peerName = try { device.name } catch (_: Exception) { null } ?: "?"
                    Log.d(TAG, "[$peerMac] CLIENT connect attempt (name='$peerName', backoff=${backoff}ms)")
                    // Insecure RFCOMM: same rationale as the server side. Avoids
                    // a per-connect auth step that races with other BT activity
                    // on the same ACL and intermittently fails with "read ret: -1".
                    val s = device.createInsecureRfcommSocketToServiceRecord(RFCOMM_UUID)
                    val connectStartMs = System.currentTimeMillis()
                    try {
                        s.connect()
                    } catch (e: Exception) {
                        // Tag the elapsed time so the catch below can distinguish an SDP
                        // failure (peer has no server — fails in <2s) from a real connect
                        // that paged the radio and timed out.
                        fastFail = System.currentTimeMillis() - connectStartMs < 2_000L
                        throw e
                    }
                    fastFail = false
                    link.socket = s
                    link.isConnected = true
                    backoff = INITIAL_BACKOFF_MS
                    Log.d(TAG, "[$peerMac] connected (name='$peerName')")

                    val svc = ServiceManager.getService()
                    val announcement = if (svc?.holdsAirPods() == true)
                        CrossDevicePackets.AIRPODS_CONNECTED.packet
                    else
                        CrossDevicePackets.AIRPODS_DISCONNECTED.packet
                    s.outputStream.write(announcement)
                    s.outputStream.flush()

                    // Keep-alive: send a harmless REQUEST_CONNECTION_STATUS ping
                    // periodically so Android's BT power-management doesn't drop
                    // the ACL link between handover events.
                    val keepAliveJob = launch {
                        while (isActive) {
                            delay(KEEPALIVE_INTERVAL_MS)
                            if (!link.isConnected) break
                            try {
                                s.outputStream.write(CrossDevicePackets.REQUEST_CONNECTION_STATUS.packet)
                                s.outputStream.flush()
                                Log.d(TAG, "[$peerMac] keep-alive ping sent @ ${System.currentTimeMillis()}")
                            } catch (e: Exception) {
                                // W5: distinguish "ping failed (link dying)" from "process
                                // frozen, ping never attempted" — the latter shows as a GAP
                                // in these timestamps with no failure line.
                                Log.w(TAG, "[$peerMac] keep-alive ping FAILED: ${e.message}")
                                break
                            }
                        }
                    }

                    val buf = ByteArray(1024)
                    while (isActive) {
                        val n = try {
                            s.inputStream.read(buf)
                        } catch (e: Exception) {
                            Log.d(TAG, "[$peerMac] read error (peer disconnected): ${e.message}")
                            break
                        }
                        if (n == -1) break
                        CrossDevice.processPacket(buf.copyOf(n), peerMac)
                    }

                    keepAliveJob.cancel()
                    s.runCatching { close() }
                    link.socket = null
                    link.isConnected = false
                    Log.d(TAG, "[$peerMac] disconnected, will retry")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val bondState = try {
                        when (adapter.getRemoteDevice(peerMac).bondState) {
                            BluetoothDevice.BOND_BONDED -> "BONDED"
                            BluetoothDevice.BOND_BONDING -> "BONDING"
                            BluetoothDevice.BOND_NONE -> "NONE"
                            else -> "?"
                        }
                    } catch (_: Exception) { "unknown" }
                    val nextBackoff = if (fastFail) SDP_BACKOFF_MS else backoff
                    Log.d(
                        TAG,
                        "[$peerMac] connect failed [${e.javaClass.simpleName}: ${e.message}] " +
                            "bond=$bondState adapterEnabled=${adapter.isEnabled} " +
                            "discovering=${adapter.isDiscovering}, " +
                            (if (fastFail) "no RFCOMM server (SDP fast-fail) — backing off ${SDP_BACKOFF_MS / 60_000}min"
                             else "retrying in ${nextBackoff}ms")
                    )
                    link.socket?.runCatching { close() }
                    link.socket = null
                    link.isConnected = false
                }
                if (isActive) {
                    // Sleep with a cancellable child job so retryNow() can short-circuit
                    // the wait when the peer's ACL comes back up.
                    val sleepMs = if (fastFail) SDP_BACKOFF_MS else backoff
                    val bj = launch { delay(sleepMs) }
                    link.backoffJob = bj
                    bj.join()
                    link.backoffJob = null
                    // After an SDP fast-fail we reset to INITIAL so that if the peer comes
                    // up on the next attempt we connect promptly; if it's still down we'll
                    // fast-fail again and re-apply the 5-min backoff.
                    backoff = if (fastFail) INITIAL_BACKOFF_MS
                              else (backoff * 1.5).toLong().coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /**
     * W5-C fix: the server side just accepted an *inbound* connection from [mac], which
     * means that peer is acting as an RFCOMM server (e.g. Windows, which ALWAYS connects
     * to the phone's server and never listens itself). Suspend our outbound connect loop
     * for [durationMs] so we stop paging that peer's radio every ~10 s for a connection
     * that will never succeed. The loop resumes naturally after the pause, so recovery
     * works if the topology ever changes.
     */
    fun pauseLink(mac: String, durationMs: Long = 5 * 60 * 1000L) {
        val link = links[mac] ?: return
        if (link.isConnected) return   // already connected outbound — nothing to suspend
        Log.d(TAG, "[${mac}] server accepted inbound from this peer — suspending outbound client for ${durationMs / 1000}s (peer is server-only)")
        link.backoffJob?.cancel()
        // Inject a long artificial backoff by starting a new cancellable sleep job.
        // retryNow() can still short-circuit it if we ever need to reconnect sooner.
        val scope = CoroutineScope(Dispatchers.IO)
        val bj = scope.launch { delay(durationMs) }
        link.backoffJob = bj
    }

    /**
     * Cancel the backoff delay and retry immediately for the link to [mac] (or all
     * links if [mac] is null). Call this when ACL_CONNECTED fires for a peer, so we
     * don't wait out the backoff before attempting the RFCOMM layer on top.
     */
    fun retryNow(mac: String? = null) {
        val targets = if (mac != null) listOfNotNull(links[mac]) else links.values.toList()
        targets.forEach { link ->
            if (link.isConnected) return@forEach
            Log.d(TAG, "[${link.mac}] retryNow: short-circuiting backoff (ACL came up)")
            link.backoffJob?.cancel()
        }
    }

    /** Send to a single peer's link. No-op if that peer has no live socket. */
    fun send(mac: String, data: ByteArray) {
        if (data.isEmpty()) return
        val s = links[mac]?.socket ?: return
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
            Log.d(TAG, "[$mac] sent: ${data.joinToString("") { "%02x".format(it) }}")
        } catch (e: Exception) {
            Log.w(TAG, "[$mac] send failed: ${e.message}")
        }
    }

    /** Broadcast to every live client link. */
    fun sendAll(data: ByteArray) {
        if (data.isEmpty()) return
        links.values.forEach { link ->
            val s = link.socket ?: return@forEach
            try {
                s.outputStream.write(data)
                s.outputStream.flush()
                Log.d(TAG, "[${link.mac}] sent: ${data.joinToString("") { "%02x".format(it) }}")
            } catch (e: Exception) {
                Log.w(TAG, "[${link.mac}] send failed: ${e.message}")
            }
        }
    }

    /** Stop the link to a single peer (per-peer remove, no global teardown). */
    fun stop(mac: String) {
        val link = links.remove(mac) ?: return
        link.isConnected = false
        link.backoffJob?.cancel()
        link.job?.cancel()
        link.socket?.runCatching { close() }
        link.socket = null
    }

    /** Stop every client link. */
    fun stop() {
        links.keys.toList().forEach { stop(it) }
    }
}
