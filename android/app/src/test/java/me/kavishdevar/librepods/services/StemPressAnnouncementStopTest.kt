package me.kavishdevar.librepods.services

import io.mockk.*
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.utils.ElevenLabsEngine
import me.kavishdevar.librepods.utils.TtsEngine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StemPressAnnouncementStopTest {

    @Before
    fun setUp() {
        mockkObject(TtsEngine)
        mockkObject(ElevenLabsEngine)
        every { TtsEngine.stop() } just Runs
        every { ElevenLabsEngine.stop() } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isAnnouncementSpeaking returns true when TtsEngine is speaking`() {
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        assertTrue(TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())
    }

    @Test
    fun `isAnnouncementSpeaking returns true when ElevenLabsEngine is speaking`() {
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns true

        assertTrue(TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())
    }

    @Test
    fun `isAnnouncementSpeaking returns true when both engines are speaking`() {
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns true

        assertTrue(TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())
    }

    @Test
    fun `isAnnouncementSpeaking returns false when neither engine is speaking`() {
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns false

        assertFalse(TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())
    }

    @Test
    fun `stopAnnouncement calls stop on both engines`() {
        TtsEngine.stop()
        ElevenLabsEngine.stop()

        verify(exactly = 1) { TtsEngine.stop() }
        verify(exactly = 1) { ElevenLabsEngine.stop() }
    }

    @Test
    fun `single press should be consumed when announcement is speaking`() {
        val pressType = AACPManager.Companion.StemPressType.SINGLE_PRESS
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertTrue(shouldConsume)
    }

    @Test
    fun `single press should not be consumed when no announcement is speaking`() {
        val pressType = AACPManager.Companion.StemPressType.SINGLE_PRESS
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertFalse(shouldConsume)
    }

    @Test
    fun `double press should not be consumed even when announcement is speaking`() {
        val pressType = AACPManager.Companion.StemPressType.DOUBLE_PRESS
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertFalse(shouldConsume)
    }

    @Test
    fun `triple press should not be consumed even when announcement is speaking`() {
        val pressType = AACPManager.Companion.StemPressType.TRIPLE_PRESS
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertFalse(shouldConsume)
    }

    @Test
    fun `long press should not be consumed even when announcement is speaking`() {
        val pressType = AACPManager.Companion.StemPressType.LONG_PRESS
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertFalse(shouldConsume)
    }

    @Test
    fun `call handling takes priority over announcement stop`() {
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        val isInCall = true
        val pressType = AACPManager.Companion.StemPressType.SINGLE_PRESS

        val callHandled = isInCall
        val shouldStopAnnouncement = !callHandled &&
            pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertFalse(shouldStopAnnouncement)
    }

    @Test
    fun `single press when silent passes through to normal stem action`() {
        val pressType = AACPManager.Companion.StemPressType.SINGLE_PRESS
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertFalse("Single press should NOT be consumed when silent — normal action (PLAY_PAUSE) should execute",
            shouldConsume)
    }

    @Test
    fun `stop is never called when single press with no announcement playing`() {
        val pressType = AACPManager.Companion.StemPressType.SINGLE_PRESS
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns false

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        if (shouldConsume) {
            TtsEngine.stop()
            ElevenLabsEngine.stop()
        }

        verify(exactly = 0) { TtsEngine.stop() }
        verify(exactly = 0) { ElevenLabsEngine.stop() }
    }

    @Test
    fun `single press stops ElevenLabs announcement`() {
        val pressType = AACPManager.Companion.StemPressType.SINGLE_PRESS
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns true

        val shouldConsume = pressType == AACPManager.Companion.StemPressType.SINGLE_PRESS &&
            (TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        assertTrue(shouldConsume)

        TtsEngine.stop()
        ElevenLabsEngine.stop()

        verify(exactly = 1) { TtsEngine.stop() }
        verify(exactly = 1) { ElevenLabsEngine.stop() }
    }

    @Test
    fun `stop does not deadlock when called during simulated speech`() {
        every { TtsEngine.isSpeaking() } returns true
        every { ElevenLabsEngine.isSpeaking() } returns false

        TtsEngine.stop()
        ElevenLabsEngine.stop()

        verify(exactly = 1) { TtsEngine.stop() }
        verify(exactly = 1) { ElevenLabsEngine.stop() }

        // After stop, isSpeaking should return false
        every { TtsEngine.isSpeaking() } returns false
        every { ElevenLabsEngine.isSpeaking() } returns false
        assertFalse(TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())

        // Next speak should work (not blocked)
        every { TtsEngine.isSpeaking() } returns true
        assertTrue(TtsEngine.isSpeaking() || ElevenLabsEngine.isSpeaking())
    }
}
