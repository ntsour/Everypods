package me.kavishdevar.librepods.utils

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatteryAlertWatcherTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AnnouncementPrefs.prefs(context)

        BatteryAlertWatcher.resetState()

        mockkObject(TtsEngine)
        mockkObject(ElevenLabsEngine)
        mockkObject(AnnouncementAudioRoute)

        every { AnnouncementAudioRoute.canAnnounceToAirPods(any()) } returns true
        every { TtsEngine.speak(any(), any(), any()) } just Runs
        every { ElevenLabsEngine.speak(any(), any(), any(), any(), any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setSystemTtsEngine() {
        prefs.edit()
            .putString(AnnouncementPrefs.KEY_TTS_ENGINE, AnnouncementPrefs.TTS_ENGINE_SYSTEM)
            .remove(AnnouncementPrefs.KEY_ELEVENLABS_API_KEY)
            .apply()
    }

    private fun setElevenLabsTtsEngine(apiKey: String = "test-api-key") {
        prefs.edit()
            .putString(AnnouncementPrefs.KEY_TTS_ENGINE, AnnouncementPrefs.TTS_ENGINE_ELEVENLABS)
            .putString(AnnouncementPrefs.KEY_ELEVENLABS_API_KEY, apiKey)
            .putString(AnnouncementPrefs.KEY_ELEVENLABS_VOICE_ID, "test-voice-id")
            .apply()
    }

    private fun setBatteryAlertsEnabled(enabled: Boolean = true, threshold: Int = 40) {
        SmartFeaturesPrefs.prefs(context).edit()
            .putBoolean(SmartFeaturesPrefs.KEY_BATTERY_ALERTS_ENABLED, enabled)
            .putInt(SmartFeaturesPrefs.KEY_BATTERY_ALERT_THRESHOLD, threshold)
            .apply()
    }

    @Test
    fun `speak uses system TTS when system engine is selected`() {
        setSystemTtsEngine()
        setBatteryAlertsEnabled(threshold = 50)

        val battery = Battery(BatteryComponent.LEFT, 30, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify { TtsEngine.speak(any(), any(), any()) }
        verify(exactly = 0) { ElevenLabsEngine.speak(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `speak uses ElevenLabs when elevenlabs engine is selected and API key is present`() {
        setElevenLabsTtsEngine()
        setBatteryAlertsEnabled(threshold = 50)

        val battery = Battery(BatteryComponent.LEFT, 30, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify {
            ElevenLabsEngine.speak(
                any(), any(), apiKey = "test-api-key", voiceId = "test-voice-id",
                any(), any(), any()
            )
        }
        verify(exactly = 0) { TtsEngine.speak(any(), any(), any()) }
    }

    @Test
    fun `speak falls back to system TTS when elevenlabs is selected but no API key`() {
        setElevenLabsTtsEngine(apiKey = "")
        setBatteryAlertsEnabled(threshold = 50)

        val battery = Battery(BatteryComponent.LEFT, 30, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify { TtsEngine.speak(any(), any(), any()) }
        verify(exactly = 0) { ElevenLabsEngine.speak(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `speak falls back to system TTS when ElevenLabs onFallback is called`() {
        setElevenLabsTtsEngine()
        setBatteryAlertsEnabled(threshold = 50)

        every {
            ElevenLabsEngine.speak(any(), any(), any(), any(), any(), any(), captureLambda())
        } answers {
            val onFallback = lambda<(String) -> Unit>().captured
            onFallback("No internet connection")
        }

        val battery = Battery(BatteryComponent.LEFT, 30, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify { TtsEngine.speak(any(), any(), any()) }
    }

    @Test
    fun `speak does not announce when battery alerts are disabled`() {
        setSystemTtsEngine()
        setBatteryAlertsEnabled(enabled = false)

        val battery = Battery(BatteryComponent.LEFT, 30, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify(exactly = 0) { TtsEngine.speak(any(), any(), any()) }
        verify(exactly = 0) { ElevenLabsEngine.speak(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `speak does not announce when battery is above threshold`() {
        setSystemTtsEngine()
        setBatteryAlertsEnabled(threshold = 20)

        val battery = Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify(exactly = 0) { TtsEngine.speak(any(), any(), any()) }
    }

    @Test
    fun `speak uses system TTS with correct language tag`() {
        setSystemTtsEngine()
        setBatteryAlertsEnabled(threshold = 50)
        prefs.edit().putString(AnnouncementPrefs.KEY_LANGUAGE, "es").apply()

        val battery = Battery(BatteryComponent.LEFT, 30, BatteryStatus.NOT_CHARGING)

        BatteryAlertWatcher.checkAndMaybeAlert(context, listOf(battery), anyBudInEar = true)

        verify { TtsEngine.speak(any(), any(), eq("es")) }
    }
}
