package io.automated.ventures.everypods.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossDeviceReconnectPolicyTest {
    @Test
    fun `passive peer recovery is blocked while local audio is connected`() {
        assertFalse(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = true,
                manualAttemptRequested = false,
            ),
        )
    }

    @Test
    fun `manual retry is allowed while local audio is connected`() {
        assertTrue(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = true,
                manualAttemptRequested = true,
            ),
        )
    }

    @Test
    fun `passive recovery resumes after local audio disconnects`() {
        assertTrue(
            CrossDeviceReconnectPolicy.mayAttempt(
                localAirPodsConnected = false,
                manualAttemptRequested = false,
            ),
        )
    }
}
