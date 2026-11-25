package io.automated.ventures.everypods.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.automated.ventures.everypods.R
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tells the user about a crash that would otherwise go unnoticed.
 *
 * A crash while the screen is off shows no dialog, and Android restarts the services about a
 * second later, so the only visible symptom is odd behaviour (e.g. the AirPods jumping back).
 * On the next process start [CrashLog] has the report on disk; this posts one notification for
 * the newest report not announced yet, and tapping it shares the report file.
 */
object CrashNotifier {
    const val CHANNEL_ID = "crash_reports"
    private const val NOTIFICATION_ID = 0x0C2A54
    private const val TAG = "CrashNotifier"
    private const val PREFS = "crash_log"
    private const val KEY_LAST_ANNOUNCED = "last_announced_report"

    /** Reports older than this on first sight (e.g. right after an update) are not announced. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    sealed interface Kind {
        data class Exception(val simpleName: String) : Kind
        data object Anr : Kind
        data object Native : Kind
        data object Other : Kind
    }

    data class Summary(val kind: Kind, val timestampMs: Long)

    fun announcePending(context: Context, dir: File) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val report = nextToAnnounce(CrashLog.reports(dir), prefs.getString(KEY_LAST_ANNOUNCED, null), System.currentTimeMillis())
                ?: return
            val summary = summarize(report.readText()) ?: return
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "crash report ${report.name} not announced: notifications not allowed")
                return
            }
            post(context, report, summary)
            prefs.edit().putString(KEY_LAST_ANNOUNCED, report.name).apply()
            Log.w(TAG, "announced crash report ${report.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "could not announce crash report", t) // never break startup
        }
    }

    /** Newest report that is newer than [lastAnnounced] and at most [MAX_AGE_MS] old. */
    internal fun nextToAnnounce(reportsNewestFirst: List<File>, lastAnnounced: String?, nowMs: Long): File? {
        val newest = reportsNewestFirst.firstOrNull() ?: return null
        val stamp = stampOf(newest.name)
        if (lastAnnounced != null && stamp <= stampOf(lastAnnounced)) return null
        val at = parseStamp(stamp) ?: return null
        return newest.takeIf { nowMs - at in 0..MAX_AGE_MS }
    }

    /** What failed and when, from a report written by [CrashLog]. */
    internal fun summarize(text: String): Summary? {
        val lines = text.lineSequence().toList()
        val timestamp = lines.firstNotNullOfOrNull { line ->
            Regex("""^timestamp=.*\((\d+)\)$""").find(line)?.groupValues?.get(1)?.toLongOrNull()
        } ?: return null
        val kind = when {
            lines.firstOrNull() == "=== EveryPods crash ===" -> {
                // First line of the stack trace follows the blank line after the header.
                val traceStart = lines.indexOfFirst { it.isBlank() }
                val exceptionLine = lines.drop(traceStart + 1).firstOrNull { it.isNotBlank() }.orEmpty()
                val className = exceptionLine.substringBefore(':').trim()
                if (className.isEmpty()) Kind.Other else Kind.Exception(className.substringAfterLast('.'))
            }
            lines.any { it == "reason=ANR" } -> Kind.Anr
            lines.any { it == "reason=CRASH_NATIVE" } -> Kind.Native
            else -> Kind.Other
        }
        return Summary(kind, timestamp)
    }

    private fun post(context: Context, report: File, summary: Summary) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.crash_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val what = when (val kind = summary.kind) {
            is Kind.Exception -> kind.simpleName
            Kind.Anr -> context.getString(R.string.crash_kind_anr)
            Kind.Native -> context.getString(R.string.crash_kind_native)
            Kind.Other -> context.getString(R.string.crash_kind_other)
        }
        val time = DateFormat.getTimeFormat(context).format(Date(summary.timestampMs))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", report)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "EveryPods ${report.name}")
            clipData = ClipData.newRawUri(report.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(share, context.getString(R.string.crash_notification_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, NOTIFICATION_ID, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle(context.getString(R.string.crash_notification_title))
            .setContentText(context.getString(R.string.crash_notification_text, what, time))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun stampOf(name: String) = name.substringAfter('_').removeSuffix(".txt")

    private fun parseStamp(stamp: String): Long? = try {
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).parse(stamp)?.time
    } catch (_: ParseException) {
        null
    }
}
