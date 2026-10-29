package io.automated.ventures.everypods.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter

class CaseConnectionJournalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `busy day does not evict events and restart retains history`() {
        val directory = temporaryFolder.newFolder()
        val journal = CaseConnectionJournal(directory)
        val start = 1_800_000_000_000L
        repeat(4_000) { index ->
            journal.append(start + index * 21_600L, index.toLong(), "event-$index")
        }
        val output = StringWriter()
        CaseConnectionJournal(directory).copyTo(output)
        val lines = output.toString().lines().filter { it.isNotBlank() }
        assertEquals(4_000, lines.size)
        assertTrue(lines.first().endsWith("event-0"))
        assertTrue(lines.last().endsWith("event-3999"))
    }

    @Test fun `retention expires only whole segments older than 48 hours`() {
        val directory = temporaryFolder.newFolder()
        val journal = CaseConnectionJournal(directory)
        val start = 100 * CaseConnectionJournal.HOUR_MS
        journal.append(start, 0, "old")
        journal.append(start + 1, 1, "same-hour")
        journal.append(start + CaseConnectionJournal.RETENTION_MS, 2, "boundary")
        val atBoundary = StringWriter().also(journal::copyTo).toString()
        assertTrue(atBoundary.contains("same-hour"))
        assertTrue(atBoundary.contains(" old"))
        journal.append(start + CaseConnectionJournal.RETENTION_MS + CaseConnectionJournal.HOUR_MS, 3, "next-hour")
        val afterBoundary = StringWriter().also(journal::copyTo).toString()
        assertFalse(afterBoundary.contains("same-hour"))
        assertTrue(afterBoundary.contains("boundary"))
        assertTrue(afterBoundary.contains("next-hour"))
    }

    @Test fun `clock rollback retains future segments and records monotonic timestamps`() {
        val journal = CaseConnectionJournal(temporaryFolder.newFolder())
        journal.append(200_000_000L, 10L, "before")
        journal.append(100_000_000L, 20L, "after\nforged")
        val lines = StringWriter().also(journal::copyTo).toString().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("elapsedMs=20 after forged") })
        assertTrue(lines.any { it.contains("elapsedMs=10 before") })
    }
}
