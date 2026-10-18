/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

import android.content.Context
import android.content.Intent
import android.util.Log

/** Experimental integration with Wahoo's undocumented Android diagnostic input. */
class WahooDiagnosticBridge(context: Context) {
    private val app = context.applicationContext
    private var stopped = false
    private val prefs = app.getSharedPreferences("heart_rate_sharing", Context.MODE_PRIVATE)
    private val relay = WahooHeartRateRelay(
        setValue = { bpm -> send(ACTION_SET_VALUE) { putExtra("cp", "HR_CRUX"); putExtra("value", bpm) } },
        pair = { send(ACTION_PAIR) { putExtra("cp", "HR_CRUX") } }
    )
    val sent get() = relay.sent
    fun publish(bpm: Int): Boolean {
        if (stopped || bpm !in 1..255) return false
        return attempt {
        if (bpm in 1..255) prefs.edit().putBoolean("wahoo_reset_pending", true).apply()
        relay.publish(bpm)
        }
    }
    fun clearReading() = attempt { relay.clearReading() }
    fun stop() = attempt {
        relay.stop()
        stopped = true
        prefs.edit().remove("wahoo_reset_pending").apply()
    }

    private inline fun attempt(block: () -> Unit): Boolean = runCatching(block)
        .onFailure { Log.w("EveryPodsWahooDiag", "Wahoo command failed", it) }.isSuccess

    private inline fun send(action: String, extras: Intent.() -> Unit) {
        app.sendBroadcast(Intent(action).setPackage("com.wahoofitness.fitness").apply(extras))
    }
    companion object {
        /** Clean up a prior interrupted session without restarting monitoring or pairing. */
        fun resetInterruptedSession(context: Context) {
            if (context.getSharedPreferences("heart_rate_sharing", Context.MODE_PRIVATE)
                    .getBoolean("wahoo_reset_pending", false)) WahooDiagnosticBridge(context).stop()
        }

        private const val ACTION_PAIR = "com.wahoofitness.support.stdsensors.StdSensorManager.PAIR_SIM"
        private const val ACTION_SET_VALUE = "com.wahoofitness.support.stdsensors.StdSensorManager.SET_SIM_VALUE"
    }
}
