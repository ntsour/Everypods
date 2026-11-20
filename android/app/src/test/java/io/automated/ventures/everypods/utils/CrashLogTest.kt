package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter

class CrashLogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val header = "appVersion=test (1)\n"

    @Test fun `crash file holds the stack trace with identifiers redacted`() {
        val dir = temporaryFolder.newFolder()
        val error = IllegalStateException("socket to AA:BB:CC:DD:EE:FF failed", RuntimeException("root cause"))

        val file = CrashLog.writeCrash(dir, 1_800_000_000_000L, header, 42, "main", error)

        val text = file.readText()
        assertTrue(file.name.startsWith("crash_"))
        assertTrue(text.contains("appVersion=test (1)"))
        assertTrue(text.contains("pid=42 thread=main"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: root cause"))
        assertTrue(text.contains("[redacted-mac]"))
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun `only the newest reports are kept`() {
        val dir = temporaryFolder.newFolder()
        val start = 1_800_000_000_000L
        repeat(CrashLog.MAX_FILES + 3) { i ->
            CrashLog.writeCrash(dir, start + i * 1_000L, header, 1, "t", RuntimeException("crash-$i"))
        }
        CrashLog.writeExit(dir, start + 60_000L, header, "ANR", "Input dispatching timed out", "\"main\" tid=1")

        val kept = CrashLog.reports(dir)
        assertEquals(CrashLog.MAX_FILES, kept.size)
        assertTrue(kept.first().name.startsWith("exit_"))
        assertFalse(kept.any { it.readText().contains("crash-0\n") || it.readText().contains("crash-3\n") })
    }

    @Test fun `export lists reports newest first and says when there are none`() {
        val dir = temporaryFolder.newFolder()
        assertTrue(StringWriter().also { CrashLog.appendRecent(dir, it) }.toString().contains("(none recorded)"))

        CrashLog.writeCrash(dir, 1_800_000_000_000L, header, 1, "t", RuntimeException("older"))
        CrashLog.writeCrash(dir, 1_800_000_005_000L, header, 1, "t", RuntimeException("newer"))
        val out = StringWriter().also { CrashLog.appendRecent(dir, it) }.toString()

        assertTrue(out.indexOf("newer") in 0 until out.indexOf("older"))
    }
}
