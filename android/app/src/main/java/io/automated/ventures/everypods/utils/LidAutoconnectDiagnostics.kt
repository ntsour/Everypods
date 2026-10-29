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
import android.os.SystemClock
import java.util.concurrent.Executors
import android.util.Log
import io.automated.ventures.everypods.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Persistent, separate 48-hour case/connection journal plus a small live snapshot.
 * Export is independent of logcat and survives process death and app updates.
 */
object LidAutoconnectDiagnostics {
    private const val TAG = "AirPodsService"
    private const val MARKER = "<LogCollector:LidLease>"
    private const val CONN_MARKER = "<LogCollector:Conn>"
    private val journalExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "case-connection-journal").apply { isDaemon = true }
    }
    @Volatile private var journal: CaseConnectionJournal? = null
    @Volatile private var journalError: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (journal != null) return
        journal = CaseConnectionJournal(File(context.filesDir, "case-connection-history"))
        logEvent("process_start", mapOf("version" to BuildConfig.VERSION_NAME,
            "sdk" to Build.VERSION.SDK_INT, "pid" to android.os.Process.myPid()))
    }

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
    private var caseCycle = 0L
    private var bleSignature: String? = null
    private var bleLastRecordedAt = 0L
    private var blePackets = 0L

    /** Log transitions immediately, plus a once-per-minute observation heartbeat. */
    @Synchronized
    fun recordBleObservation(leftInEar: Boolean, rightInEar: Boolean,
                             leftCharging: Boolean, rightCharging: Boolean, connectionState: String) {
        blePackets++
        val signature = "$leftInEar/$rightInEar/$leftCharging/$rightCharging/$connectionState"
        val now = SystemClock.elapsedRealtime()
        if (signature == bleSignature && now - bleLastRecordedAt < 60_000L) return
        bleSignature = signature
        bleLastRecordedAt = now
        logEvent("ble_observation", mapOf("leftInEar" to leftInEar, "rightInEar" to rightInEar,
            "leftCharging" to leftCharging, "rightCharging" to rightCharging,
            "connectionState" to connectionState, "packets" to blePackets))
    }
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
        val safeLine = redactIdentifiers(line).take(1_000)
        if (!isRelevant(safeLine)) return
        val now = System.currentTimeMillis()
        ring.addLast(RingEntry(now, safeLine))
        pruneLocked(now)
        val elapsed = SystemClock.elapsedRealtime()
        val destination = journal ?: return
        journalExecutor.execute {
            try {
                destination.append(now, elapsed, safeLine)
                journalError = null
            } catch (e: Exception) {
                journalError = e.javaClass.simpleName
                Log.e(TAG, "Case connection history write failed", e)
            }
        }
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
    @Synchronized
    fun logEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (event == "lid_open") caseCycle++
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(event).append(" cycle=").append(caseCycle)
        for ((k, v) in fields) {
            if (v == null) continue
            sb.append(' ').append(k).append('=').append(v)
        }
        val line = sb.toString()
        Log.d(TAG, line)
        recordRawLine(line)
        updateSnapshotFromEvent(event, fields)
    }

    /** Safe lifecycle telemetry for peer/DIRCON recovery; address and payload fields are never admitted. */
    fun logConnectionEvent(component: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        val allowed = setOf(
            "state", "phase", "error", "cause", "attempt", "retryMs", "retryAtElapsedMs",
            "generation", "protocol", "clients", "subscriptions", "sent", "requests", "queued",
            "ageMs", "sampleAgeMs", "listener", "decision", "reason",
        )
        val line = buildString {
            append(CONN_MARKER).append(' ').append(component.take(16)).append(" connection ").append(event.take(24))
            for ((key, value) in fields) {
                if (key !in allowed || value == null) continue
                val safe = redactIdentifiers(value.toString())
                    .filter { it.isLetterOrDigit() || it in "._:-[] " }
                    .take(128)
                append(' ').append(key).append('=').append(safe)
            }
        }
        Log.d(TAG, line)
        recordRawLine(line)
    }

    fun fingerprint(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

    private fun redactIdentifiers(value: String): String = value
        .replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"), "[redacted-mac]")
        .replace(Regex("(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b"), "[redacted-id]")
        .replace(Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])"), "[redacted-address]")

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
        initialize(context)
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
            append(redactIdentifiers(snapshot.prettyPrint()))
            appendLine()
            appendLine("=== RING_BUFFER (oldest-first) ===")
            if (lines.isEmpty()) {
                appendLine("(empty — no lid/conn events since process start)")
            } else {
                lines.forEach { appendLine(it) }
            }
        })
        // Run on the same executor so all preceding events are included, streaming
        // segments to avoid loading 48 hours of history into memory.
        journalExecutor.submit {
            file.appendText("\n=== PERSISTENT CASE / CONNECTION HISTORY (48h minimum) ===\n")
            file.appendText("journalWriteError=${journalError ?: "none"}\n")
            file.appendText("=== LIVE SNAPSHOT (identifiers redacted) ===\n" +
                redactIdentifiers(snapshot.prettyPrint()) + "\n")
            file.appendText("=== EVENTS ===\n")
            file.appendText("timestamps=wall-clock Unix ms and monotonic elapsed ms; process_start marks restarts\n")
            file.appendText("retention=48h minimum; no line-count or size eviction\n")
            java.io.FileOutputStream(file, true).bufferedWriter().use { writer ->
                journal?.copyTo(writer)
            }
        }.get()
        return file
    }
}
