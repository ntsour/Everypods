package io.automated.ventures.everypods

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast

/** Gives the public showAssist API a focused activity to request Android's current assistant. */
class AssistantLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val finishRequest = Runnable { finish() }
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Assistant launch: keyguardLocked=${getSystemService(KeyguardManager::class.java).isKeyguardLocked}")
        // A no-display activity cannot call showAssist: it must be visible and focused first.
        setContentView(View(this))
        // Do not replay the user's stem press after an activity recreation.
        if (savedInstanceState != null) {
            finish()
            return
        }
        handler.postDelayed({
            if (!requested && !isFinishing) {
                Log.w(TAG, "System assistant request did not receive window focus")
                showUnavailable()
                finish()
            }
        }, 3_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (isFinishing) return
        if (!hasFocus) {
            // The assistant now owns focus. Remove our transparent task to reveal the prior app.
            if (requested) handler.post(finishRequest)
            return
        }
        if (requested) return
        requested = true
        // Let the first frame become visible before the framework checks our activity token.
        window.decorView.post { requestAssistant(0) }
    }

    private fun requestAssistant(attempt: Int) {
        if (isFinishing || !hasWindowFocus()) return
        val accepted = runCatching { showAssist(Bundle()) }
            .onFailure { Log.w(TAG, "System assistant request failed", it) }
            .getOrDefault(false)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        Log.i(TAG, "System assistant session requested: accepted=$accepted keyguardLocked=$locked attempt=${attempt + 1}")
        if (!accepted && attempt < 2) {
            // A first-frame visibility race can reject an otherwise focused foreground activity.
            // Retry only rejected requests; never request a second session after acceptance.
            handler.postDelayed({ requestAssistant(attempt + 1) }, 100L)
            return
        }
        if (!accepted) {
            showUnavailable()
            finish()
        } else {
            // Also clean up if an assistant accepts the request but never takes focus.
            handler.postDelayed(finishRequest, 2_000L)
        }
    }

    private fun showUnavailable() {
        Toast.makeText(this, R.string.assistant_unavailable, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EveryPodsAssistant"
    }
}
