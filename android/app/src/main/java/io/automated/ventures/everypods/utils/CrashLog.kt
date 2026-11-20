package io.automated.ventures.everypods.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import io.automated.ventures.everypods.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps crash reports in filesDir/logs (the FileProvider path) so they survive a reboot and
 * can be shared from a release build through the Debug screen's report export.
 *
 * - Uncaught JVM exceptions are written by a default handler before the previous handler runs.
 * - Native crashes and ANRs can't be caught in-process, so they are read back from
 *   [ApplicationExitInfo] on the next process start.
 */
object CrashLog {
    const val MAX_FILES = 10
    private const val TAG = "CrashLog"
    private const val CRASH_PREFIX = "crash_"
    private const val EXIT_PREFIX = "exit_"
    private const val MAX_TRACE_CHARS = 128 * 1024
    private const val PREFS = "crash_log"
    private const val KEY_LAST_EXIT_MS = "last_exit_timestamp_ms"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val dir = logsDir(app)
        val header = deviceHeader()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = writeCrash(dir, System.currentTimeMillis(), header, Process.myPid(), thread.name, error)
                Log.e(TAG, "crash report written to ${file.name}")
            } catch (_: Throwable) {
                // Never let the logger replace the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        Thread({ captureAbnormalExits(app, dir, header) }, "CrashLogExitScan").start()
    }

    fun logsDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    internal fun writeCrash(
        dir: File,
        nowMs: Long,
        header: String,
        pid: Int,
        threadName: String,
        error: Throwable,
    ): File {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val body = buildString {
            appendLine("=== EveryPods crash ===")
            appendLine("timestamp=${iso(nowMs)} ($nowMs)")
            append(header)
            appendLine("pid=$pid thread=$threadName")
            appendLine()
            append(redactIdentifiers(trace))
        }
        return writeAndPrune(dir, "$CRASH_PREFIX${fileStamp(nowMs)}.txt", body)
    }

    internal fun writeExit(
        dir: File,
        timestampMs: Long,
        header: String,
        reason: String,
        description: String?,
        trace: String?,
    ): File {
        val body = buildString {
            appendLine("=== EveryPods abnormal exit ===")
            appendLine("timestamp=${iso(timestampMs)} ($timestampMs)")
            append(header)
            appendLine("reason=$reason")
            appendLine("description=${description?.let(::redactIdentifiers) ?: "none"}")
            if (!trace.isNullOrBlank()) {
                appendLine()
                append(redactIdentifiers(trace.take(MAX_TRACE_CHARS)))
                if (trace.length > MAX_TRACE_CHARS) appendLine("\n[trace truncated at $MAX_TRACE_CHARS chars]")
            }
        }
        return writeAndPrune(dir, "$EXIT_PREFIX${fileStamp(timestampMs)}.txt", body)
    }

    /** Crash and exit files, newest first. */
    fun reports(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && isReport(f.name) } ?: emptyArray())
            .sortedByDescending { it.name.substringAfter('_') }

    /** Append every kept report, newest first, to an exported diagnostics report. */
    fun appendRecent(dir: File, writer: Writer) {
        val files = reports(dir)
        writer.write("=== RECENT CRASHES (newest first, max $MAX_FILES) ===\n")
        if (files.isEmpty()) {
            writer.write("(none recorded)\n")
            return
        }
        files.forEach { file ->
            writer.write("--- ${file.name} ---\n")
            file.reader().use { it.copyTo(writer) }
            writer.write("\n")
        }
    }

    private fun captureAbnormalExits(context: Context, dir: File, header: String) {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_EXIT_MS, 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            exits.filter { it.timestamp > lastSeen && it.reason in CAPTURED_REASONS }
                .forEach { info ->
                    val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) {
                        info.traceInputStream?.bufferedReader()?.use { readCapped(it) }
                    } else {
                        null // native tombstones are binary protobufs; the description is enough
                    }
                    val file = writeExit(dir, info.timestamp, header, reasonName(info.reason), info.description, trace)
                    Log.w(TAG, "previous ${reasonName(info.reason)} recorded in ${file.name}")
                }
            exits.maxOfOrNull { it.timestamp }?.let { newest ->
                if (newest > lastSeen) prefs.edit().putLong(KEY_LAST_EXIT_MS, newest).apply()
            }
        } catch (_: Throwable) {
            // Best effort: diagnostics must never break startup.
        }
    }

    private fun readCapped(reader: java.io.Reader): String {
        val buffer = CharArray(MAX_TRACE_CHARS + 1)
        var read = 0
        while (read < buffer.size) {
            val n = reader.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read)
    }

    private val CAPTURED_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> "REASON_$reason"
    }

    private fun isReport(name: String) =
        (name.startsWith(CRASH_PREFIX) || name.startsWith(EXIT_PREFIX)) && name.endsWith(".txt")

    private fun writeAndPrune(dir: File, name: String, body: String): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(body)
        reports(dir).drop(MAX_FILES).forEach { it.delete() }
        return file
    }

    private fun deviceHeader(): String = buildString {
        appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("deviceModel=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
    }

    private fun fileStamp(ms: Long) = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(ms))
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))
}
