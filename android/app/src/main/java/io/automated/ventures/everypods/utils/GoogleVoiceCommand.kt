package io.automated.ventures.everypods.utils

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Starts Google's hands-free voice search the way a headset button long press does.
 *
 * Intent.ACTION_VOICE_COMMAND is not used: since Google app 17.76 it rejects third-party callers.
 * Instead a KEYCODE_HEADSETHOOK long press is dispatched, so the system's MediaSessionService
 * launches the voice search itself. Google still requires a connected headset and owns microphone
 * routing; EveryPods does not record.
 */
object GoogleVoiceCommand {
    private const val LONG_PRESS_MS = 500L

    fun launch(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        longPressHeadsetHook(SystemClock.uptimeMillis()).forEach(audioManager::dispatchMediaKeyEvent)
    }

    internal fun longPressHeadsetHook(downTime: Long): List<KeyEvent> {
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)
        val longPressTime = downTime + LONG_PRESS_MS
        val longPress = KeyEvent.changeTimeRepeat(down, longPressTime, 1, KeyEvent.FLAG_LONG_PRESS)
        val up = KeyEvent(downTime, longPressTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0)
        return listOf(down, longPress, up)
    }
}
