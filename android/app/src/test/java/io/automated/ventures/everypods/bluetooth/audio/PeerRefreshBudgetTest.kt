package io.automated.ventures.everypods.bluetooth.audio

import org.junit.Assert.*
import org.junit.Test

class PeerRefreshBudgetTest {
    @Test fun `refreshes are spaced and limited per intent without extending its budget`() {
        val budget = PeerRefreshBudget(intervalMs = 2000, maximum = 3)
        assertTrue(budget.take(1, 100))
        assertFalse(budget.take(1, 850))
        assertFalse(budget.take(1, 99))
        assertTrue(budget.take(1, 2100))
        assertTrue(budget.take(1, 4100))
        assertFalse(budget.take(1, 9000))
        assertTrue(budget.take(2, 9000))
    }
}
