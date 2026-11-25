package io.automated.ventures.everypods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearReportBaselineTest {
    private val baseline = WearReportBaseline()

    @Test
    fun `the first report on a link is the baseline`() {
        assertTrue(baseline.isFirstOnLink(1))
    }

    @Test
    fun `later reports on the same link are changes`() {
        baseline.isFirstOnLink(1)
        assertFalse(baseline.isFirstOnLink(1))
        assertFalse(baseline.isFirstOnLink(1))
    }

    @Test
    fun `a new link starts a new baseline`() {
        baseline.isFirstOnLink(1)
        baseline.isFirstOnLink(1)
        assertTrue(baseline.isFirstOnLink(2))
        assertFalse(baseline.isFirstOnLink(2))
    }

    @Test
    fun `generation zero still gets a baseline`() {
        assertTrue(baseline.isFirstOnLink(0))
        assertFalse(baseline.isFirstOnLink(0))
    }
}
