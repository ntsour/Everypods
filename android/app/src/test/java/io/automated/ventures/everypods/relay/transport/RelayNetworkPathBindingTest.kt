/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RelayNetworkPathBindingTest {
    private val path = RelayNetworkPath("wlan0", "192.168.1.38", 24, 1171636801549L)

    @Test
    fun bindableNetworkKeepsItsHandle() {
        assertSame(path, path.withNetworkBinding(bindable = true))
    }

    @Test
    fun refusedBindingPinsByInterfaceAddressOnly() {
        val fallback = path.withNetworkBinding(bindable = false)
        assertEquals(0L, fallback.networkHandle)
        assertEquals(path.interfaceName, fallback.interfaceName)
        assertEquals(path.address, fallback.address)
        assertEquals(path.prefix, fallback.prefix)
    }
}
