package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.bluetooth.HeadMotionProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File

/** Debug-only capture of raw sensor payloads for offline gesture research.
 *  Rows are `elapsed_ms,label,service,payload_hex`; marker rows use service -1
 *  and carry their text in the last column. Nothing is interpreted here, so
 *  undecoded payload bytes survive for analysis. */
object HeadMotionRecorder {
    data class State(val file: File? = null, val rows: Int = 0, val label: String = LABELS.first()) {
        val recording: Boolean get() = file != null
    }

    val LABELS = listOf(
        "idle", "nod", "shake", "tilt_left_first", "tilt_right_first", "tilt_hold",
        "walking", "running", "talking", "looking_around", "other",
    )
    const val MAX_ROWS = 200_000 // about two hours at 25 samples per second

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var writer: BufferedWriter? = null
    private var startedAt = 0L
    @Volatile private var active = false

    @Synchronized fun start(directory: File, now: Long, name: String = "head-motion-${System.currentTimeMillis()}.csv"): File? {
        stop()
        return try {
            directory.mkdirs()
            val file = File(directory, name)
            writer = file.bufferedWriter().apply { write("elapsed_ms,label,service,payload_hex\n") }
            startedAt = now
            _state.value = _state.value.copy(file = file, rows = 0)
            active = true
            file
        } catch (_: java.io.IOException) {
            writer = null
            null
        }
    }

    @Synchronized fun stop() {
        active = false
        try { writer?.close() } catch (_: java.io.IOException) { }
        writer = null
        _state.value = _state.value.copy(file = null)
    }

    @Synchronized fun setLabel(label: String) {
        _state.value = _state.value.copy(label = label)
    }

    /** Called for every head-tracking frame; a no-op unless a recording is running. */
    fun record(frame: ByteArray, now: Long) {
        if (!active) return
        val commands = HeadMotionProtocol.decodeCommands(frame)
        if (commands.isEmpty()) return
        synchronized(this) {
            for (command in commands) write(now, command.service, command.payload.joinToString("") { "%02x".format(it) })
            flush()
        }
    }

    /** Ground-truth events, such as a gesture the live detector recognised. */
    fun mark(text: String, now: Long) {
        if (!active) return
        synchronized(this) { write(now, -1, text.replace(',', ' ').replace('\n', ' ')); flush() }
    }

    private fun write(now: Long, service: Int, last: String) {
        val out = writer ?: return
        val current = _state.value
        try {
            out.write("${now - startedAt},${current.label},$service,$last\n")
            _state.value = current.copy(rows = current.rows + 1)
            if (current.rows + 1 >= MAX_ROWS) stop()
        } catch (_: java.io.IOException) {
            stop()
        }
    }

    private fun flush() {
        try { writer?.flush() } catch (_: java.io.IOException) { stop() }
    }
}
