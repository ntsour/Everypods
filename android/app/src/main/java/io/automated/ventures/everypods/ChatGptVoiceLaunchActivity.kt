package io.automated.ventures.everypods

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast

private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
private const val CHATGPT_VOICE_URL = "https://chat.com/c?mode=voice"

/** Intent that opens ChatGPT's own voice mode through its voice-shortcut deep link. */
internal fun chatGptVoiceIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(CHATGPT_VOICE_URL))
        .setPackage(CHATGPT_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Opens ChatGPT voice mode from a stem press.
 *
 * ChatGPT cannot show over the lock screen (verified on device), so this transparent
 * show-when-locked activity first asks the system to dismiss the keyguard. That requires
 * biometric or PIN unlock unless the device is trusted. Once the device is unlocked, ChatGPT's
 * own voice-shortcut deep link is opened and this activity finishes.
 */
class ChatGptVoiceLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        // Do not replay the user's stem press after an activity recreation.
        if (savedInstanceState != null) {
            finish()
            return
        }
        handler.postDelayed({
            if (!isFinishing) {
                Log.w(TAG, "Timed out waiting for keyguard dismissal")
                finish()
            }
        }, TIMEOUT_MS)
    }

    override fun onResume() {
        super.onResume()
        if (started || isFinishing) return
        started = true
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (!keyguardManager.isKeyguardLocked) {
            launchChatGpt()
            return
        }
        keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.i(TAG, "Keyguard dismissed")
                launchChatGpt()
            }

            override fun onDismissCancelled() {
                Log.i(TAG, "Keyguard dismiss cancelled")
                finish()
            }

            override fun onDismissError() {
                Log.w(TAG, "Keyguard dismiss error")
                finish()
            }
        })
    }

    private fun launchChatGpt() {
        try {
            startActivity(chatGptVoiceIntent())
            Log.i(TAG, "Launched ChatGPT voice")
        } catch (error: ActivityNotFoundException) {
            reportUnavailable(error)
        } catch (error: RuntimeException) {
            reportUnavailable(error)
        }
        finish()
    }

    private fun reportUnavailable(error: Exception) {
        Log.w(TAG, "Could not launch ChatGPT voice", error)
        Toast.makeText(this, getString(R.string.chatgpt_voice_unavailable), Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EveryPodsChatGptVoice"
        private const val TIMEOUT_MS = 15_000L

        fun intent(context: Context): Intent =
            Intent(context, ChatGptVoiceLaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
