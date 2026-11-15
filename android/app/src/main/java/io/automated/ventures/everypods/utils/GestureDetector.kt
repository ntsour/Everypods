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
        val tilt: Progress = Progress(),
    ) {
        fun progress(gesture: HeadGesture) = when (gesture) {
            HeadGesture.NOD -> nod; HeadGesture.SHAKE -> shake; HeadGesture.TILT -> tilt
        }
    }
    /** The last completed sequence, accepted or rejected, with the movement ranges that decided it.
     *  [turnRange] is the head-turning signal, scaled to what a shake puts on the horizontal one. */
    data class Outcome(
        val sequence: Int, val gesture: HeadGesture, val accepted: Boolean,
        val verticalRange: Int, val horizontalRange: Int, val turnRange: Int,
    )
    var outcome: Outcome? = null
        private set
    private var outcomes = 0
    private fun record(gesture: HeadGesture, accepted: Boolean) {
        val axis = if (gesture == HeadGesture.TILT) tilt else shake
        outcome = Outcome(++outcomes, gesture, accepted, nod.range, axis.range, axis.rivalRange)
    }
    private class Axis {
        var firstSign = 0
        var opposite = false
        var count = 0
        var started: Long? = null
        var lastExcursion = 0L
        var min = 0
        var max = 0
        // Range of the turning signal over this axis' own lifetime. It separates shake
        // (head turns) from tilt (head does not turn) on the shared horizontal signal.
        var rivalMin = 0
        var rivalMax = 0
        val range get() = max - min
        val rivalRange get() = rivalMax - rivalMin
        fun measure(value: Int, rival: Int) {
            min = minOf(min, value); max = maxOf(max, value)
            rivalMin = minOf(rivalMin, rival); rivalMax = maxOf(rivalMax, rival)
        }
        fun clear() {
            firstSign = 0; opposite = false; count = 0; started = null; lastExcursion = 0
            min = 0; max = 0; rivalMin = 0; rivalMax = 0
        }
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
    private val tilt = Axis()
    private fun clearAxes() { nod.clear(); shake.clear(); tilt.clear() }
    private var lastSample: Long? = null
    private var blocked = false
    private var cooldownUntil = 0L
    private var neutralSince: Long? = null
    private var detected: HeadGesture? = null
    val awaitingNeutral: Boolean get() = blocked
    var status = Status()
        private set

    fun reset() {
        clearAxes(); lastSample = null
        blocked = false; cooldownUntil = 0; neutralSince = null; detected = null; status = Status()
    }

    /** Invalid input discards partial motion but cannot bypass an existing cooldown. */
    fun invalidate() {
        clearAxes(); neutralSince = null; lastSample = null
        status = status.copy(nod = Progress(), shake = Progress(), tilt = Progress())
    }

    /** Lifecycle boundaries must discard partial movements without clearing a completed cooldown. */
    fun awaitNeutral() {
        invalidate()
        blocked = true
        cooldownUntil = maxOf(cooldownUntil, clock())
        status = status.copy(cooldownMs = (cooldownUntil - clock()).coerceAtLeast(0))
    }

    /** Shake and tilt both swing the horizontal signal; only a shake also swings the turning
     *  signal ([lateral]). In the 2026-11-15 captures a shake put 0.61 to 0.69 of its lateral
     *  range on the horizontal signal, while a tilt's lateral range stayed under 0.36 of its
     *  horizontal range. Without [lateral] the detector runs in its original two-signal form,
     *  and without [tiltTuning] a tilt is never reported. */
    fun process(
        horizontal: Int, vertical: Int, nodTuning: GestureTuning, shakeTuning: GestureTuning,
        lateral: Int? = null, tiltTuning: GestureTuning? = null,
    ): HeadGesture? {
        val now = clock()
        if (abs(horizontal.toLong()) > 6000 || abs(vertical.toLong()) > 6000 || abs((lateral ?: 0).toLong()) > 6000) { invalidate(); return null }
        if (lastSample?.let { now - it > 1000 || now < it } == true) invalidate()
        lastSample = now
        if (blocked) {
            val horizontalNeutral = minOf(shakeTuning.amplitude, tiltTuning?.amplitude ?: shakeTuning.amplitude) / 4
            if (abs(horizontal) <= horizontalNeutral && abs(vertical) <= nodTuning.amplitude / 4) {
                if (neutralSince == null) neutralSince = now
                if (now >= cooldownUntil && now - neutralSince!! >= 300) { blocked = false; neutralSince = null }
            } else neutralSince = null
            status = status.copy(cooldownMs = (cooldownUntil - now).coerceAtLeast(0))
            return null
        }
        nod.sample(vertical, nodTuning, now)
        shake.sample(horizontal, shakeTuning, now)
        if (tiltTuning != null) tilt.sample(horizontal, tiltTuning, now)
        // Measure every axis even when cross-axis motion is below its own threshold.
        if (nod.started != null || shake.started != null || tilt.started != null) {
            val turn = (lateral ?: 0) * TURN_TO_HORIZONTAL_PERCENT / 100
            nod.measure(vertical, turn); shake.measure(horizontal, turn); tilt.measure(horizontal, turn)
        } else clearAxes()
        val nodDone = nod.count >= nodTuning.repetitions
        val shakeDone = shake.count >= shakeTuning.repetitions
        val tiltDone = tiltTuning != null && tilt.count >= tiltTuning.repetitions
        // A shake is mostly head turning; the horizontal signal carries only part of it, and how
        // much depends on how the earbud sits. Judge its straightness on the larger of the two.
        val shakeStrength = maxOf(shake.range, shake.rivalRange * 100 / TURN_TO_HORIZONTAL_PERCENT)
        val shakeStraight = shakeStrength * 100 >= nod.range * shakeTuning.axisDominancePercent
        val tiltStraight = tiltTuning != null && tilt.range * 100 >= nod.range * tiltTuning.axisDominancePercent
        val winner = when {
            nodDone && nod.range * 100 >= shake.range * nodTuning.axisDominancePercent -> HeadGesture.NOD
            // A shake must show at least half the turning a real shake produces.
            shakeDone && shakeStraight && (lateral == null || shake.rivalRange * 2 >= shake.range) -> HeadGesture.SHAKE
            tiltDone && tiltStraight && tilt.range * 100 >= tilt.rivalRange * tiltTuning!!.axisDominancePercent -> HeadGesture.TILT
            else -> null
        }
        status = Status(Progress(maxOf(abs(nod.min), abs(nod.max)), nod.count), Progress(maxOf(abs(shake.min), abs(shake.max)), shake.count),
            detected, tilt = Progress(maxOf(abs(tilt.min), abs(tilt.max)), tilt.count))
        if (winner != null) {
            record(winner, true)
            detected = winner
            val tuning = when (winner) { HeadGesture.NOD -> nodTuning; HeadGesture.SHAKE -> shakeTuning; HeadGesture.TILT -> tiltTuning!! }
            cooldownUntil = now + tuning.cooldownMs
            blocked = true; neutralSince = null
            status = status.copy(detected = winner, cooldownMs = tuning.cooldownMs.toLong())
            clearAxes()
        } else if (nodDone || shakeDone || tiltDone) {
            // A completed ambiguous sequence is rejected, not banked for later motion.
            // Shake and tilt count the same signal with different tunings, so one of them
            // finishing first while the other is still counting only discards itself.
            val shakeWaitsForTilt = shakeDone && !nodDone && shakeStraight && tiltTuning != null && !tiltDone && tilt.started != null
            val tiltWaitsForShake = tiltDone && !nodDone && !shakeDone && tiltStraight && shake.started != null
            when {
                shakeWaitsForTilt -> shake.clear()
                tiltWaitsForShake -> tilt.clear()
                else -> {
                    record(if (tiltDone && !shakeDone && !nodDone) HeadGesture.TILT else if (shakeDone && !nodDone) HeadGesture.SHAKE else HeadGesture.NOD, false)
                    clearAxes()
                }
            }
        }
        return winner
    }

    companion object { const val TURN_TO_HORIZONTAL_PERCENT = 69 }
}
