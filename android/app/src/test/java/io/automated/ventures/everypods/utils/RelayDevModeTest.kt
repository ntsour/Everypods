package io.automated.ventures.everypods.utils

import io.automated.ventures.everypods.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDevModeTest {
    private val productionUuid = "1abbb9a4-10e4-4000-a75c-8953c5471342"

    @Test fun productionAlwaysAllowsAacp() {
        assertTrue(RelayDevMode.aacpAllowed(relayDev = false, devOptIn = false))
        assertTrue(RelayDevMode.aacpAllowed(relayDev = false, devOptIn = true))
    }

    @Test fun devIsRelayOnlyUntilOptIn() {
        assertFalse(RelayDevMode.aacpAllowed(relayDev = true, devOptIn = false))
        assertTrue(RelayDevMode.aacpAllowed(relayDev = true, devOptIn = true))
    }

    @Test fun warnOnlyForDevNextToProduction() {
        assertTrue(RelayDevMode.shouldWarn(true, "io.automated.ventures.everypods.relaydev", true))
        assertFalse(RelayDevMode.shouldWarn(true, "io.automated.ventures.everypods.relaydev", false))
        assertFalse(RelayDevMode.shouldWarn(false, "io.automated.ventures.everypods", true))
        assertFalse(RelayDevMode.shouldWarn(true, RelayDevMode.PRODUCTION_PACKAGE, true))
    }

    /** Unit tests run on the debug variant: it must use its own cross-device namespace. */
    @Test fun debugVariantIsolatedFromProductionPeers() {
        assertTrue(BuildConfig.RELAY_DEV)
        assertTrue(BuildConfig.APPLICATION_ID.endsWith(".relaydev"))
        assertNotEquals(productionUuid, RelayDevMode.crossDeviceUuid.toString())
        assertNotEquals("EveryPodsCrossDevice", RelayDevMode.crossDeviceServiceName)
        assertEquals(36, BuildConfig.CROSS_DEVICE_UUID.length)
    }
}
