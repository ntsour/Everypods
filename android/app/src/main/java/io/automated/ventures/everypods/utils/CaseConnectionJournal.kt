package io.automated.ventures.everypods.utils

import java.io.File
import java.io.Writer

/** Dedicated journal. Age, never line count or byte count, controls retention. */
internal class CaseConnectionJournal(private val directory: File) {
    companion object {
        const val HOUR_MS = 3_600_000L
        const val RETENTION_MS = 48 * HOUR_MS
    }

    @Synchronized
    fun append(wallMs: Long, elapsedMs: Long, line: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create case journal" }
        val hour = Math.floorDiv(wallMs, HOUR_MS)
        File(directory, "case-$hour.log").appendText("wallMs=$wallMs elapsedMs=$elapsedMs ${line.replace('\n', ' ').replace('\r', ' ')}\n")
        // Keep the entire boundary hour: every event is retained for at least 48h.
        files().forEach { file ->
            val bucket = file.name.removePrefix("case-").removeSuffix(".log").toLongOrNull()
            if (bucket != null && bucket < Math.floorDiv(wallMs - RETENTION_MS, HOUR_MS)) {
                check(file.delete()) { "Cannot expire case journal segment" }
            }
        }
    }

    @Synchronized
    fun copyTo(writer: Writer) {
        files().sortedBy { it.name.removePrefix("case-").removeSuffix(".log").toLongOrNull() ?: 0L }
            .forEach { file -> file.reader().use { it.copyTo(writer) } }
    }

    private fun files(): List<File> = directory.listFiles()?.filter {
        it.name.startsWith("case-") && it.extension == "log"
    }.orEmpty()
}
