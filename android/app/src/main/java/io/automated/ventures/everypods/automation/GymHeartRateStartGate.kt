package io.automated.ventures.everypods.automation

/** Manual Gym Mode auto-sharing: no polling or repeated starts while readiness is unchanged. */
class GymHeartRateStartGate {
    private var attempted = false
    private var attempts = 0

    fun reset() { attempted = false; attempts = 0 }

    fun shouldStart(enabled: Boolean, ready: Boolean, active: Boolean, suppressed: Boolean, live: Boolean = false): Boolean {
        if (!enabled) { reset(); return false }
        if (!ready) { attempted = false; return false }
        if (active) { attempted = true; if (live) attempts = 0; return false }
        if (suppressed || attempted || attempts >= GymAutomationReducer.MAX_HR_START_ATTEMPTS) return false
        attempted = true
        attempts++
        return true
    }
}
