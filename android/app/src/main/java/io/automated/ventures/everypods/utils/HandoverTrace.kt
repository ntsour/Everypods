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
import android.os.Process
import android.util.Log
import io.automated.ventures.everypods.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in, full-detail trace for handover debugging, kept apart from logcat and
 * from the redacted case/connection journal.
 *
 * While enabled, it streams this process's own logcat (every tag and level,
 * unredacted, so peer addresses can be matched with the Windows trace) into
 * `<external app files>/handover-trace/android-<yyyyMMdd>.log`. Files are
 * append-only and never rotated while running; old files are pruned only when
 * recording starts: older than [RETENTION_DAYS], then oldest first above
 * [MAX_TOTAL_BYTES]. While recording, [TraceUploadReceiver] also accepts the
 * Windows client's traces into `handover-trace/windows/`. The folder is
 * app-private but adb-readable:
 * `adb pull /sdcard/Android/data/<package>/files/handover-trace`.
 */
object HandoverTrace {
    const val PREF_ENABLED = "handover_trace_enabled"
    private const val TAG = "HandoverTrace"
    private const val RETENTION_DAYS = 14
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024

    @Volatile private var worker: Thread? = null
    @Volatile private var logcat: java.lang.Process? = null
    @Volatile private var running = false
    @Volatile private var generation = 0

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "handover-trace")

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, enabled).apply()
        apply(context)
    }

    /** Start or stop recording to match the saved preference. Idempotent. */
    @Synchronized
    fun apply(context: Context) {
        val app = context.applicationContext
        if (isEnabled(app)) start(app) else stop()
    }

    /** Android traces, then traces uploaded by the Windows client. */
    fun files(context: Context): List<File> {
        fun logs(dir: File, prefix: String) = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.extension == "log" }
            ?.sortedBy { it.name }
            .orEmpty()
        val dir = directory(context)
        return logs(dir, "android-") + logs(File(dir, "windows"), "windows-")
    }

    private fun start(context: Context) {
        if (running) return
        val dir = directory(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create ${dir.path}")
            return
        }
        prune(dir)
        prune(File(dir, "windows"))
        running = true
        val gen = ++generation
        worker = Thread({ pump(dir, gen) }, "handover-trace").apply { isDaemon = true; start() }
        TraceUploadReceiver.start(context)
    }

    private fun stop() {
        if (!running) return
        running = false
        TraceUploadReceiver.stop()
        logcat?.destroy()
        worker?.interrupt()
        worker = null
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3_600_000L
        val kept = dir.listFiles()?.filter { it.isFile && it.extension == "log" }.orEmpty()
            .filter { file -> if (file.lastModified() < cutoff) !file.delete() else true }
            .sortedBy { it.lastModified() }
            .toMutableList()
        var total = kept.sumOf { it.length() }
        while (total > MAX_TOTAL_BYTES && kept.size > 1) {
            val oldest = kept.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) total -= size
        }
    }

    private fun pump(dir: File, gen: Int) {
        fun active() = running && generation == gen
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        var day = ""
        var out: BufferedWriter? = null
        fun write(line: String) {
            val today = dayFormat.format(Date())
            if (today != day || out == null) {
                out?.runCatching { close() }
                out = BufferedWriter(OutputStreamWriter(
                    FileOutputStream(File(dir, "android-$today.log"), true), Charsets.UTF_8))
                day = today
            }
            out!!.write(line)
            out!!.newLine()
            out!!.flush()
        }
        try {
            write("=== ${stampFormat.format(Date())} trace start pid=${Process.myPid()} " +
                "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            var restarts = 0
            while (active()) {
                // A fresh process's own-pid buffer starts with this process, so the
                // first read loses nothing. After a logcat restart, resume from now.
                val args = mutableListOf("logcat", "-v", "threadtime", "-v", "year", "-v", "zone", "--pid=${Process.myPid()}")
                if (restarts > 0) args += listOf("-T", "1")
                val proc = ProcessBuilder(args).redirectErrorStream(true).start()
                logcat = proc
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!active()) break
                        write(line)
                    }
                }
                proc.destroy()
                if (!active()) break
                restarts++
                write("=== ${stampFormat.format(Date())} logcat reader ended; restarting (#$restarts)")
                Thread.sleep(1_000L)
            }
            write("=== ${stampFormat.format(Date())} trace stopped")
        } catch (_: InterruptedException) {
            // stop() interrupts the sleep between restarts.
        } catch (e: Exception) {
            Log.e(TAG, "Handover trace stopped", e)
        } finally {
            out?.runCatching { close() }
            if (generation == gen) running = false
        }
    }
}
