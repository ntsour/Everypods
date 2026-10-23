package io.automated.ventures.everypods.bluetooth.hrm.dircon

import org.junit.Assert.assertEquals
import org.junit.Test

class DirconNsdRegistrationLeaseTest {
    @Test fun `Stop before registration completes unregisters once on late success`() {
        var unregistered = 0
        var ready = 0
        val lease = DirconNsdRegistrationLease(
            unregister = { unregistered++ },
            onRegistered = { ready++ },
            onRegistrationFailed = { error("unexpected failure: $it") },
        )

        lease.stop()
        lease.registered()
        lease.registered()
        lease.stop()

        assertEquals(0, ready)
        assertEquals(1, unregistered)
    }

    @Test fun `registered service is unregistered exactly once on Stop`() {
        var unregistered = 0
        var ready = 0
        val lease = DirconNsdRegistrationLease(
            unregister = { unregistered++ },
            onRegistered = { ready++ },
            onRegistrationFailed = { error("unexpected failure: $it") },
        )

        lease.registered()
        lease.registered()
        lease.stop()
        lease.stop()

        assertEquals(1, ready)
        assertEquals(1, unregistered)
    }

    @Test fun `registration failure is delivered once and a late success is cleaned`() {
        var unregistered = 0
        val errors = mutableListOf<Int>()
        val lease = DirconNsdRegistrationLease(
            unregister = { unregistered++ },
            onRegistered = { error("unexpected success") },
            onRegistrationFailed = errors::add,
        )

        lease.registrationFailed(7)
        lease.registrationFailed(8)
        lease.registered()

        assertEquals(listOf(7), errors)
        assertEquals(1, unregistered)
    }
}
