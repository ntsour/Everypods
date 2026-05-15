package me.kavishdevar.librepods.bluetooth.connection

import android.util.Log

/**
 * Structured, attempt-scoped logger for connection events. The same line format is
 * used in production (Log.d via [AndroidAttemptLogger]) and in tests
 * ([RecordingAttemptLogger] in the test source set).
 *
 * Event format: `[Conn-#42] event=socket_connect_start k1=v1 k2=v2`. The leading
 * `<LogCollector:...>` marker is preserved so the in-app log collector keeps
 * working.
 */
interface AttemptLogger {
    fun log(attemptId: Int, event: String, fields: Map<String, Any?> = emptyMap())
}

class AndroidAttemptLogger(private val tag: String = "ConnectionEngine") : AttemptLogger {
    override fun log(attemptId: Int, event: String, fields: Map<String, Any?>) {
        val sb = StringBuilder()
        sb.append("<LogCollector:Conn> [Conn-#")
        sb.append(attemptId)
        sb.append("] event=")
        sb.append(event)
        for ((k, v) in fields) {
            sb.append(' ').append(k).append('=').append(v)
        }
        Log.d(tag, sb.toString())
    }
}
