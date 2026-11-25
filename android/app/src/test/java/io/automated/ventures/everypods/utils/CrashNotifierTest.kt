package io.automated.ventures.everypods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashNotifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val header = "appVersion=test (1)\nr8MapId=none\n"
    private val crashAt = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test fun `crash report is summarised by exception class and time`() {
        val dir = temporaryFolder.newFolder()
        val file = CrashLog.writeCrash(dir, crashAt, header, 1, "DefaultDispatcher-worker-9", NullPointerException("device was null"))

        val summary = CrashNotifier.summarize(file.readText())

        assertEquals(CrashNotifier.Summary(CrashNotifier.Kind.Exception("NullPointerException"), crashAt), summary)
    }

    @Test fun `exit reports are summarised by reason`() {
        val dir = temporaryFolder.newFolder()
        val anr = CrashLog.writeExit(dir, crashAt, header, "ANR", "Input dispatching timed out", "\"main\" tid=1")
        val native = CrashLog.writeExit(dir, crashAt + 1, header, "CRASH_NATIVE", "signal 11", null)

        assertEquals(CrashNotifier.Kind.Anr, CrashNotifier.summarize(anr.readText())?.kind)
        assertEquals(CrashNotifier.Kind.Native, CrashNotifier.summarize(native.readText())?.kind)
    }

    @Test fun `newest unannounced report is picked once`() {
        val dir = temporaryFolder.newFolder()
        CrashLog.writeCrash(dir, crashAt - 5_000L, header, 1, "t", RuntimeException("older"))
        val newest = CrashLog.writeCrash(dir, crashAt, header, 1, "t", RuntimeException("newer"))
        val reports = CrashLog.reports(dir)

        assertEquals(newest, CrashNotifier.nextToAnnounce(reports, null, crashAt + 2_000L))
        assertNull(CrashNotifier.nextToAnnounce(reports, newest.name, crashAt + 2_000L))
    }

    @Test fun `a later crash is announced after an earlier one was`() {
        val dir = temporaryFolder.newFolder()
        val first = CrashLog.writeCrash(dir, crashAt, header, 1, "t", RuntimeException("first"))
        val second = CrashLog.writeExit(dir, crashAt + hour, header, "ANR", null, null)

        assertEquals(second, CrashNotifier.nextToAnnounce(CrashLog.reports(dir), first.name, crashAt + hour + 1_000L))
    }

    @Test fun `old reports are not announced`() {
        val dir = temporaryFolder.newFolder()
        CrashLog.writeCrash(dir, crashAt, header, 1, "t", RuntimeException("stale"))

        assertNull(CrashNotifier.nextToAnnounce(CrashLog.reports(dir), null, crashAt + CrashNotifier.MAX_AGE_MS + 1))
        assertNull(CrashNotifier.nextToAnnounce(emptyList(), null, crashAt))
    }

    @Test fun `r8 map id is read from the obfuscated source file name`() {
        val id = "4523163a5ddad422f5d049aec7c5b5ed61a50c0a2153e67b90f6bfa93099d0a0"
        val r8Frame = StackTraceElement("b7", "u", "r8-map-id-$id", 37)
        val plainFrame = StackTraceElement("Foo", "bar", "Foo.kt", 1)

        assertEquals(id, CrashLog.r8MapId(arrayOf(r8Frame)))
        assertEquals("none", CrashLog.r8MapId(arrayOf(plainFrame)))
        assertEquals("none", CrashLog.r8MapId(emptyArray()))
    }
}
