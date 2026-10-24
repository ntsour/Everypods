/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.bluetooth.hrm

/** Orders Wahoo commands so pairing can never precede the first real measurement. */
class WahooHeartRateRelay(private val setValue: (Int) -> Unit, private val pair: () -> Unit) {
    private var paired = false
    private var stopped = false
    private var zeroed = false
    var sent = 0L
        private set

    @Synchronized fun publish(bpm: Int) {
        if (stopped || bpm !in 1..255) return
        setValue(bpm)
        zeroed = false
        if (!paired) { pair(); paired = true }
        sent++
    }
    @Synchronized fun clearReading() {
        if (zeroed) return
        setValue(0)
        zeroed = true
    }
    /** Receiver was restarted; pair again only after the next genuine sample. */
    @Synchronized fun resetPairing() {
        if (!stopped) paired = false
    }
    @Synchronized fun stop() {
        if (stopped) return
        // Always send zero, even if a stale-reading reset already did so.
        setValue(0)
        zeroed = true
        stopped = true
    }
}
