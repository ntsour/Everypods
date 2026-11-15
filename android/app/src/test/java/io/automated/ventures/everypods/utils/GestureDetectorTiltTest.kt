package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.data.GestureTuning
import io.automated.ventures.everypods.data.HeadGesture
import org.junit.Assert.*
import org.junit.Test

/** Shake and tilt both swing the horizontal signal; only a shake also swings the lateral one. */
class GestureDetectorTiltTest {
    private var now = 0L
    private val detector = GestureDetector { now }
    // App defaults: nod and shake are fast, tilt allows a slower rock.
    private val base = GestureTuning(600, 2, 2000, 800, 2000, 300)
    private val tiltTuning = base.copy(maxPhaseGapMs = 1200, maxDurationMs = 3000)

    private fun sample(horizontal: Int = 0, lateral: Int = 0, vertical: Int = 0, dt: Long = 100,
                       shake: GestureTuning = base, tilt: GestureTuning? = tiltTuning): HeadGesture? {
        now += dt
        return detector.process(horizontal, vertical, base, shake, lateral, tilt)
    }

    /** One back-and-forth swing. [turnPercent] is the lateral signal relative to the horizontal one:
     *  about -150 for a recorded shake, within +/-36 for a recorded tilt. */
    private fun cycle(turnPercent: Int, amplitude: Int = 1000, vertical: Int = 0, dt: Long = 100,
                      shake: GestureTuning = base, tilt: GestureTuning? = tiltTuning): HeadGesture? {
        sample(amplitude, amplitude * turnPercent / 100, vertical, dt, shake, tilt)
        sample(-amplitude, -amplitude * turnPercent / 100, -vertical, dt, shake, tilt)
        return sample(dt = dt, shake = shake, tilt = tilt)
    }

    @Test fun `horizontal swings without head turning are a tilt`() {
        assertNull(cycle(turnPercent = 20))
        assertEquals(HeadGesture.TILT, cycle(turnPercent = 20))
        assertEquals(2000L, detector.status.cooldownMs)
    }

    @Test fun `horizontal swings with head turning are a shake`() {
        assertNull(cycle(turnPercent = -150))
        assertEquals(HeadGesture.SHAKE, cycle(turnPercent = -150))
    }

    /** Pixel 10 safe test, 15 November 2026, 12:25:24: horizontal 2115, vertical 1240,
     *  turning 3595 (scaled). The horizontal signal alone is only 1.7 times the vertical one. */
    @Test fun `shake whose turning is strong passes straightness even when the horizontal share is weak`() {
        val strict = base.copy(axisDominancePercent = 400)
        fun swing() = run {
            sample(1057, -2605, 620, shake = strict); sample(-1057, 2605, -620, shake = strict); sample(shake = strict)
        }
        assertNull(swing())
        assertEquals(HeadGesture.SHAKE, swing())
    }

    @Test fun `turning sign does not matter`() {
        assertNull(cycle(turnPercent = 150))
        assertEquals(HeadGesture.SHAKE, cycle(turnPercent = 150))
    }

    @Test fun `movement between a tilt and a shake is neither`() {
        // Turning at 60 percent scales to 0.41 of the horizontal range: too little for a
        // shake (needs 0.5) and too much for a tilt at 3.0x strictness (allows 0.33).
        assertNull(cycle(turnPercent = 60)); assertNull(cycle(turnPercent = 60))
        val outcome = detector.outcome!!
        assertFalse(outcome.accepted)
        assertEquals(2000, outcome.horizontalRange); assertEquals(828, outcome.turnRange)
    }

    @Test fun `tilt without a tilt tuning is ignored instead of firing shake`() {
        assertNull(cycle(turnPercent = 20, tilt = null)); assertNull(cycle(turnPercent = 20, tilt = null))
        assertFalse(detector.awaitingNeutral)
    }

    @Test fun `slow tilt passes its own timing while shake timing would reject it`() {
        // 500 ms steps: one second between direction changes, beyond the 800 ms shake limit.
        assertNull(cycle(turnPercent = 20, dt = 500))
        assertEquals(HeadGesture.TILT, cycle(turnPercent = 20, dt = 500))
        detector.reset()
        assertNull(cycle(turnPercent = -150, dt = 500)); assertNull(cycle(turnPercent = -150, dt = 500))
    }

    @Test fun `tilt amplitude and repetition count are its own`() {
        val strong = tiltTuning.copy(amplitude = 1100)
        assertNull(cycle(turnPercent = 20, tilt = strong)); assertNull(cycle(turnPercent = 20, tilt = strong))
        detector.reset()
        val three = tiltTuning.copy(repetitions = 3)
        assertNull(cycle(turnPercent = 20, tilt = three)); assertNull(cycle(turnPercent = 20, tilt = three))
        assertEquals(HeadGesture.TILT, cycle(turnPercent = 20, tilt = three))
    }

    @Test fun `shake needing more repetitions is not erased by the tilt count finishing first`() {
        val three = base.copy(repetitions = 3)
        assertNull(cycle(turnPercent = -150, shake = three)); assertNull(cycle(turnPercent = -150, shake = three))
        assertEquals(HeadGesture.SHAKE, cycle(turnPercent = -150, shake = three))
    }

    @Test fun `tilt direction strictness rejects nodding mixed in and loosens with its setting`() {
        assertNull(cycle(turnPercent = 20, vertical = 400)); assertNull(cycle(turnPercent = 20, vertical = 400))
        detector.reset()
        val loose = tiltTuning.copy(axisDominancePercent = 150)
        assertNull(cycle(turnPercent = 20, vertical = 400, tilt = loose))
        assertEquals(HeadGesture.TILT, cycle(turnPercent = 20, vertical = 400, tilt = loose))
    }

    @Test fun `nod is unaffected by the turning signal`() {
        sample(vertical = 900, lateral = 80); sample(vertical = -900, lateral = -80); assertNull(sample())
        sample(vertical = 900, lateral = 80); sample(vertical = -900, lateral = -80)
        assertEquals(HeadGesture.NOD, sample())
    }

    @Test fun `every completed sequence reports its outcome, accepted or rejected`() {
        assertNull(detector.outcome)
        cycle(turnPercent = 20, vertical = 400); cycle(turnPercent = 20, vertical = 400)
        val rejected = detector.outcome!!
        assertFalse(rejected.accepted); assertEquals(800, rejected.verticalRange)
        cycle(turnPercent = 20); cycle(turnPercent = 20)
        val accepted = detector.outcome!!
        assertEquals(HeadGesture.TILT, accepted.gesture); assertTrue(accepted.accepted)
        assertEquals(rejected.sequence + 1, accepted.sequence)
        assertEquals(2000, accepted.horizontalRange); assertEquals(276, accepted.turnRange)
    }

    private fun replay(resource: String): List<Pair<Long, HeadGesture>> {
        val lines = javaClass.getResourceAsStream("/head-motion/$resource")!!.bufferedReader().readLines().drop(1)
        val found = mutableListOf<Pair<Long, HeadGesture>>()
        detector.reset()
        for (line in lines) {
            val (time, lateral, horizontal, vertical) = line.split(',').map { it.toInt() }
            now = time.toLong()
            detector.process(horizontal, vertical, base, base, lateral, tiltTuning)?.let { found += now / 1000 to it }
        }
        return found
    }

    /** Pixel 10 capture, 15 November 2026: tilts until 135 s, then nods, then shakes, then
     *  unscripted movement. The nod and shake times are what the released two-signal detector
     *  reported on the phone; it also reported eight of the tilts as shakes. */
    @Test fun `recorded session keeps every nod and shake and reads the tilts as tilts`() {
        val found = replay("session-tilt-nod-shake.csv")
        val nods = listOf(138L, 147, 153, 156, 160, 167, 175).map { it to HeadGesture.NOD }
        val shakes = listOf(179L, 185, 190, 197, 204, 208, 213, 218, 224, 230).map { it to HeadGesture.SHAKE }
        assertEquals(nods + shakes, found.filter { it.second != HeadGesture.TILT })
        val tilts = found.filter { it.second == HeadGesture.TILT }
        assertEquals(22, tilts.size)
        assertTrue(tilts.all { it.first < 136 })
    }

    @Test fun `recorded tilt session reports only tilts`() {
        assertEquals(listOf(9L, 18, 31).map { it to HeadGesture.TILT }, replay("session-tilts.csv"))
    }
}
