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

import android.content.Context
import android.os.Build
import android.util.Log
import io.automated.ventures.everypods.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring + last-attempt snapshot for lid-open autoconnect diagnostics.
 * Lines matching LidLease / relevant Conn / BLE lid are kept for ~5 minutes or
 * the last ~200 entries so the Debug "Report lid-open connect failure" button
 * can export without relying on logcat.
 */
object LidAutoconnectDiagnostics {
    private const val TAG = "AirPodsService"
    private const val MARKER = "<LogCollector:LidLease>"
    private const val CONN_MARKER = "<LogCollector:Conn>"
    private const val MAX_LINES = 200
    private const val MAX_AGE_MS = 5 * 60_000L

    data class LastLidAttemptSnapshot(
        var wallClockMs: Long = 0L,
        var lastEvent: String = "none",
        var lastSkipReason: String? = null,
        var featureEnabled: Boolean? = null,
        var holder: Boolean? = null,
        var leaseEverSet: Boolean? = null,
        var shared: Boolean? = null,
        var peerHolding: Boolean? = null,
        var holders: String = "",
        var fluxAgeMs: Long? = null,
        var cooldownLeftMs: Long? = null,
        var macPresent: Boolean? = null,
        var appVersionName: String = BuildConfig.VERSION_NAME,
        var versionCode: Int = BuildConfig.VERSION_CODE,
    ) {
        fun prettyPrint(): String = buildString {
            appendLine("wallClockMs=$wallClockMs")
            appendLine("lastEvent=$lastEvent")
            appendLine("lastSkipReason=${lastSkipReason ?: "-"}")
            appendLine("featureEnabled=${featureEnabled ?: "-"}")
            appendLine("holder=${holder ?: "-"}")
            appendLine("leaseEverSet=${leaseEverSet ?: "-"}")
            appendLine("shared=${shared ?: "-"}")
            appendLine("peerHolding=${peerHolding ?: "-"}")
            appendLine("holders=$holders")
            appendLine("fluxAgeMs=${fluxAgeMs ?: "-"}")
            appendLine("cooldownLeftMs=${cooldownLeftMs ?: "-"}")
            appendLine("macPresent=${macPresent ?: "-"}")
            appendLine("appVersionName=$appVersionName")
            appendLine("versionCode=$versionCode")
        }
    }

    private data class RingEntry(val wallClockMs: Long, val line: String)

    private val ring = ConcurrentLinkedDeque<RingEntry>()
    @Volatile
    var snapshot: LastLidAttemptSnapshot = LastLidAttemptSnapshot()
        private set

    @Synchronized
    private fun pruneLocked(now: Long = System.currentTimeMillis()) {
        while (ring.size > MAX_LINES) {
            ring.pollFirst()
        }
        while (true) {
            val oldest = ring.peekFirst() ?: break
            if (now - oldest.wallClockMs <= MAX_AGE_MS) break
            ring.pollFirst()
        }
    }

    /** Record a raw log line if it looks lid/conn-related. */
    @Synchronized
    fun recordRawLine(line: String) {
        if (!isRelevant(line)) return
        val now = System.currentTimeMillis()
        ring.addLast(RingEntry(now, line))
        pruneLocked(now)
    }

    private fun isRelevant(line: String): Boolean {
        if (line.contains(MARKER)) return true
        if (line.contains(CONN_MARKER) && (
                line.contains("lid", ignoreCase = true) ||
                    line.contains("A2DP", ignoreCase = true) ||
                    line.contains("connect", ignoreCase = true) ||
                    line.contains("lease", ignoreCase = true)
                )
        ) return true
        if (line.contains("Lid opened") || line.contains("Lid closed")) return true
        return false
    }

    /**
     * Log a structured LidLease event, update the snapshot, and keep the ring.
     * [fields] are rendered as `k=v` on one line.
     */
    fun logEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(event)
        for ((k, v) in fields) {
            if (v == null) continue
            sb.append(' ').append(k).append('=').append(v)
        }
        val line = sb.toString()
        Log.d(TAG, line)
        recordRawLine(line)
        updateSnapshotFromEvent(event, fields)
    }

    private fun updateSnapshotFromEvent(event: String, fields: Map<String, Any?>) {
        val s = snapshot
        s.wallClockMs = System.currentTimeMillis()
        s.lastEvent = event
        s.appVersionName = BuildConfig.VERSION_NAME
        s.versionCode = BuildConfig.VERSION_CODE
        fields["reason"]?.let {
            if (event == "skip" || event == "lid_closed") s.lastSkipReason = it.toString()
        }
        fields["feature"]?.let { s.featureEnabled = it.toString().toBooleanStrictOrNull() ?: s.featureEnabled }
        fields["featureEnabled"]?.let { s.featureEnabled = it.toString().toBooleanStrictOrNull() ?: s.featureEnabled }
        fields["holder"]?.let { s.holder = it.toString().toBooleanStrictOrNull() ?: s.holder }
        fields["leaseEverSet"]?.let { s.leaseEverSet = it.toString().toBooleanStrictOrNull() ?: s.leaseEverSet }
        fields["shared"]?.let { s.shared = it.toString().toBooleanStrictOrNull() ?: s.shared }
        fields["peerHolding"]?.let { s.peerHolding = it.toString().toBooleanStrictOrNull() ?: s.peerHolding }
        fields["holders"]?.let { s.holders = it.toString() }
        fields["holdersCount"]?.let { /* keep holders string if present */ }
        fields["fluxAgeMs"]?.let { s.fluxAgeMs = it.toString().toLongOrNull() }
        fields["cooldownLeftMs"]?.let { s.cooldownLeftMs = it.toString().toLongOrNull() }
        fields["macPresent"]?.let { s.macPresent = it.toString().toBooleanStrictOrNull() ?: s.macPresent }
        snapshot = s
    }

    /** Apply lease/context fields into the live snapshot without a new event. */
    fun enrichSnapshot(
        featureEnabled: Boolean? = null,
        holder: Boolean? = null,
        leaseEverSet: Boolean? = null,
        shared: Boolean? = null,
        peerHolding: Boolean? = null,
        holders: String? = null,
        fluxAgeMs: Long? = null,
        cooldownLeftMs: Long? = null,
        macPresent: Boolean? = null,
    ) {
        val s = snapshot
        featureEnabled?.let { s.featureEnabled = it }
        holder?.let { s.holder = it }
        leaseEverSet?.let { s.leaseEverSet = it }
        shared?.let { s.shared = it }
        peerHolding?.let { s.peerHolding = it }
        holders?.let { s.holders = it }
        fluxAgeMs?.let { s.fluxAgeMs = it }
        cooldownLeftMs?.let { s.cooldownLeftMs = it }
        macPresent?.let { s.macPresent = it }
        snapshot = s
    }

    fun truncatedHolders(): String {
        val list = CrossDevice.holders.toList()
        if (list.isEmpty()) return ""
        val joined = list.joinToString(",")
        return if (joined.length <= 64) joined else joined.take(61) + "..."
    }

    @Synchronized
    fun ringLinesOldestFirst(): List<String> {
        pruneLocked()
        return ring.map { it.line }
    }

    fun isBufferEmpty(): Boolean = ring.isEmpty() && snapshot.lastEvent == "none"

    /**
     * Build `lid_open_failure_<timestamp>.txt` under filesDir/logs (FileProvider path).
     * Returns the file (always written, even if ring is empty).
     */
    fun writeFailureReport(context: Context): File {
        val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(logsDir, "lid_open_failure_$ts.txt")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val lines = ringLinesOldestFirst()
        file.writeText(buildString {
            appendLine("=== EveryPods lid-open failure report ===")
            appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("deviceModel=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("timestampIso=$iso")
            appendLine("ringOrder=oldest-first")
            appendLine("ringCount=${lines.size}")
            appendLine()
            appendLine("=== SNAPSHOT ===")
            append(snapshot.prettyPrint())
            appendLine()
            appendLine("=== RING_BUFFER (oldest-first) ===")
            if (lines.isEmpty()) {
                appendLine("(empty — no lid/conn events since process start)")
            } else {
                lines.forEach { appendLine(it) }
            }
        })
        return file
    }
}
