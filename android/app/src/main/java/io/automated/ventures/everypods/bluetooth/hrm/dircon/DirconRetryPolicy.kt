package io.automated.ventures.everypods.bluetooth.hrm.dircon

/** Monotonic retry schedule for a requested DIRCON session. */
object DirconRetryPolicy {
    private val delaysMs = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 30_000L)
    const val STEADY_DELAY_MS = 60_000L

    /** attempt is one-based; jitter is injected so tests can prove bounded timing. */
    fun delayMs(attempt: Int, jitterFactor: Double): Long {
        require(attempt >= 1) { "attempt_must_be_positive" }
        require(jitterFactor in 0.8..1.2) { "jitter_out_of_bounds" }
        val base = delaysMs.getOrNull(attempt - 1) ?: STEADY_DELAY_MS
        return (base * jitterFactor).toLong()
    }
}
