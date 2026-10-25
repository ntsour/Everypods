package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.utils.StemResumeSessionPicker.Candidate
import io.automated.ventures.everypods.utils.StemResumeSessionPicker.Path
import io.automated.ventures.everypods.utils.StemResumeSessionPicker.STATE_BUFFERING
import io.automated.ventures.everypods.utils.StemResumeSessionPicker.STATE_NONE
import io.automated.ventures.everypods.utils.StemResumeSessionPicker.STATE_PAUSED
import io.automated.ventures.everypods.utils.StemResumeSessionPicker.STATE_PLAYING
import io.automated.ventures.everypods.utils.StemResumeSessionPicker.STATE_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemResumeSessionPickerTest {
    private val own = setOf("io.automated.ventures.everypods")
    private fun c(pkg: String, state: Int, t: Long = 0, usage: Int? = 1, remote: Boolean = false, play: Boolean = true) =
        Candidate(pkg, state, t, usage, remote, play)
    private fun pick(list: List<Candidate>, pref: String? = null) = StemResumeSessionPicker.pick(list, own, pref)

    @Test fun emptyListReturnsNull() = assertNull(pick(emptyList()))

    @Test fun pausedBeatsStopped() =
        assertEquals("au.com.shiftyjelly.pocketcasts", pick(listOf(c("a.stopped", STATE_STOPPED, 900), c("au.com.shiftyjelly.pocketcasts", STATE_PAUSED, 100)))?.packageName)

    @Test fun skipsOwnPackage() = assertNull(pick(listOf(c("io.automated.ventures.everypods", STATE_PAUSED))))

    @Test fun skipsCallsByPackageAndUsage() {
        assertNull(pick(listOf(c("com.viber.voip", STATE_PAUSED), c("x.voip", STATE_PAUSED, usage = 2))))
    }

    @Test fun skipsNonResumableStatesRemoteAndNoPlayAction() {
        assertNull(pick(listOf(c("a", STATE_NONE), c("b", STATE_PLAYING), c("c", STATE_PAUSED, remote = true), c("d", STATE_PAUSED, play = false))))
    }

    @Test fun mostRecentFirstThenPriorityOrder() {
        assertEquals("new", pick(listOf(c("old", STATE_PAUSED, 10), c("new", STATE_PAUSED, 50)))?.packageName)
        assertEquals("first", pick(listOf(c("first", STATE_PAUSED, 10), c("second", STATE_PAUSED, 10)))?.packageName)
    }

    @Test fun preferredLastPlayedWinsEvenIfStopped() =
        assertEquals("pc", pick(listOf(c("spotify", STATE_PAUSED, 99), c("pc", STATE_STOPPED, 1)), pref = "pc")?.packageName)

    @Test fun preferredIgnoredWhenNotEligible() =
        assertEquals("spotify", pick(listOf(c("spotify", STATE_PAUSED), c("pc", STATE_PLAYING)), pref = "pc")?.packageName)

    @Test fun decideDirectOnlyForResume() {
        val t = c("pc", STATE_PAUSED)
        assertEquals(Path.DIRECT_SESSION, StemResumeSessionPicker.decide(false, false, true, t))
        assertEquals(Path.KEY_EVENT, StemResumeSessionPicker.decide(true, false, true, t))
        assertEquals(Path.KEY_EVENT, StemResumeSessionPicker.decide(false, true, true, t))
        assertEquals(Path.KEY_EVENT, StemResumeSessionPicker.decide(false, false, false, t))
        assertEquals(Path.KEY_EVENT, StemResumeSessionPicker.decide(false, false, true, null))
    }

    @Test fun resumedStates() {
        assertTrue(StemResumeSessionPicker.isResumed(STATE_PLAYING))
        assertTrue(StemResumeSessionPicker.isResumed(STATE_BUFFERING))
        assertFalse(StemResumeSessionPicker.isResumed(STATE_PAUSED))
        assertFalse(StemResumeSessionPicker.isResumed(null))
    }
}
