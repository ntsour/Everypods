package io.automated.ventures.everypods.automation

import org.junit.Assert.*
import org.junit.Test

class GymHeartRateStartGateTest {
    @Test fun waitsForWearAndRetriesOnlyOnNewReadinessWithinBudget() {
        val gate = GymHeartRateStartGate()
        repeat(GymAutomationReducer.MAX_HR_START_ATTEMPTS) {
            assertFalse(gate.shouldStart(true, false, false, false))
            assertTrue(gate.shouldStart(true, true, false, false))
            assertFalse(gate.shouldStart(true, true, false, false))
        }
        assertFalse(gate.shouldStart(true, false, false, false))
        assertFalse(gate.shouldStart(true, true, false, false))
        gate.reset()
        assertTrue(gate.shouldStart(true, true, false, false))
    }

    @Test fun manualStopAndExistingSessionsAreRespected() {
        val gate = GymHeartRateStartGate()
        assertFalse(gate.shouldStart(true, true, true, false))
        assertFalse(gate.shouldStart(true, true, false, false))
        assertFalse(gate.shouldStart(true, false, false, true))
        assertFalse(gate.shouldStart(true, true, false, true))
        assertFalse(gate.shouldStart(false, true, false, false))
        assertTrue(gate.shouldStart(true, true, false, false))
    }

    @Test fun validReadingRestoresBudgetForTheNextWorkoutWearPeriod() {
        val gate = GymHeartRateStartGate()
        repeat(5) {
            gate.shouldStart(true, false, false, false)
            assertTrue(gate.shouldStart(true, true, false, false))
            assertFalse(gate.shouldStart(true, true, true, false, live = true))
        }
    }
}
