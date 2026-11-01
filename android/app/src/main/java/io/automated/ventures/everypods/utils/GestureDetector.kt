package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.data.GestureTuning
import io.automated.ventures.everypods.data.HeadGesture
import kotlin.math.abs

/** Deterministic motion classifier. All calls belong to one controller thread. */
class GestureDetector(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Progress(val amplitude: Int = 0, val repetitions: Int = 0)
    data class Status(
        val nod: Progress = Progress(), val shake: Progress = Progress(),
        val detected: HeadGesture? = null, val cooldownMs: Long = 0,
    )
    private class Axis {
        var firstSign = 0
        var opposite = false
        var count = 0
        var started: Long? = null
        var lastExcursion = 0L
        var min = 0
        var max = 0
        fun clear() { firstSign = 0; opposite = false; count = 0; started = null; lastExcursion = 0; min = 0; max = 0 }
        fun sample(value: Int, tuning: GestureTuning, now: Long) {
            if (started != null && (now - lastExcursion > tuning.maxPhaseGapMs || now - started!! > tuning.maxDurationMs)) clear()
            val hadOpposite = opposite
            val cycleSign = firstSign
            if (abs(value) >= tuning.amplitude) {
                if (started == null) started = now
                val sign = if (value > 0) 1 else -1
                if (firstSign == 0) { firstSign = sign; lastExcursion = now }
                else if (sign != firstSign && !opposite) { opposite = true; lastExcursion = now }
            }
            if (started != null) {
                min = minOf(min, value); max = maxOf(max, value)
                // A neutral return can fall between two 40 ms samples. Crossing back to
                // the first side still completes the cycle; don't require an exact zero sample.
                val returned = abs(value) <= tuning.amplitude / 4 ||
                    (cycleSign > 0 && value > 0) || (cycleSign < 0 && value < 0)
                if (hadOpposite && returned) {
                    count++; firstSign = 0; opposite = false; lastExcursion = now
                    // A full excursion on this sample can begin the next repetition too.
                    if (abs(value) >= tuning.amplitude) firstSign = if (value > 0) 1 else -1
                }
            }
        }
    }
    private val nod = Axis()
    private val shake = Axis()
    private var lastSample: Long? = null
    private var blocked = false
    private var cooldownUntil = 0L
    private var neutralSince: Long? = null
    private var detected: HeadGesture? = null
    val awaitingNeutral: Boolean get() = blocked
    var status = Status()
        private set

    fun reset() {
        nod.clear(); shake.clear(); lastSample = null
        blocked = false; cooldownUntil = 0; neutralSince = null; detected = null; status = Status()
    }

    /** Invalid input discards partial motion but cannot bypass an existing cooldown. */
    fun invalidate() {
        nod.clear(); shake.clear(); neutralSince = null; lastSample = null
        status = status.copy(nod = Progress(), shake = Progress())
    }

    /** Lifecycle boundaries must discard partial movements without clearing a completed cooldown. */
    fun awaitNeutral() {
        invalidate()
        blocked = true
        cooldownUntil = maxOf(cooldownUntil, clock())
        status = status.copy(cooldownMs = (cooldownUntil - clock()).coerceAtLeast(0))
    }

    fun process(horizontal: Int, vertical: Int, nodTuning: GestureTuning, shakeTuning: GestureTuning): HeadGesture? {
        val now = clock()
        if (abs(horizontal.toLong()) > 6000 || abs(vertical.toLong()) > 6000) { invalidate(); return null }
        if (lastSample?.let { now - it > 1000 || now < it } == true) invalidate()
        lastSample = now
        if (blocked) {
            if (abs(horizontal) <= shakeTuning.amplitude / 4 && abs(vertical) <= nodTuning.amplitude / 4) {
                if (neutralSince == null) neutralSince = now
                if (now >= cooldownUntil && now - neutralSince!! >= 300) { blocked = false; neutralSince = null }
            } else neutralSince = null
            status = status.copy(cooldownMs = (cooldownUntil - now).coerceAtLeast(0))
            return null
        }
        nod.sample(vertical, nodTuning, now)
        shake.sample(horizontal, shakeTuning, now)
        // Measure both axes even when cross-axis motion is below its own threshold.
        if (nod.started != null || shake.started != null) {
            nod.min = minOf(nod.min, vertical); nod.max = maxOf(nod.max, vertical)
            shake.min = minOf(shake.min, horizontal); shake.max = maxOf(shake.max, horizontal)
        } else { nod.clear(); shake.clear() }
        val winner = when {
            nod.count >= nodTuning.repetitions && (nod.max - nod.min) * 100 >= (shake.max - shake.min) * nodTuning.axisDominancePercent -> HeadGesture.NOD
            shake.count >= shakeTuning.repetitions && (shake.max - shake.min) * 100 >= (nod.max - nod.min) * shakeTuning.axisDominancePercent -> HeadGesture.SHAKE
            else -> null
        }
        status = Status(Progress(maxOf(abs(nod.min), abs(nod.max)), nod.count), Progress(maxOf(abs(shake.min), abs(shake.max)), shake.count), detected)
        if (winner != null) {
            detected = winner
            val tuning = if (winner == HeadGesture.NOD) nodTuning else shakeTuning
            cooldownUntil = now + tuning.cooldownMs
            blocked = true; neutralSince = null
            status = status.copy(detected = winner, cooldownMs = tuning.cooldownMs.toLong())
            nod.clear(); shake.clear()
        } else if (nod.count >= nodTuning.repetitions || shake.count >= shakeTuning.repetitions) {
            // A completed ambiguous sequence is rejected, not banked for later motion.
            nod.clear(); shake.clear()
        }
        return winner
    }
}
