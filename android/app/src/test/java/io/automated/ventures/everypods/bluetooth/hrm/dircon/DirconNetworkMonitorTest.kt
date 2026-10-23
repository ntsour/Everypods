package io.automated.ventures.everypods.bluetooth.hrm.dircon

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DirconNetworkMonitorTest {
    @Test fun `distinguishes Wi-Fi without a supported IPv4 address`() {
        val context = mockk<Context>(relaxed = true)
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        val network = mockk<Network>(relaxed = true)
        val capabilities = mockk<NetworkCapabilities>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.allNetworks } returns arrayOf(network)
        every { connectivity.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { connectivity.getLinkProperties(network) } returns LinkProperties()
        every { connectivity.activeNetwork } returns network

        val monitor = DirconNetworkMonitor(context)
        assertNull(monitor.currentEndpoint())
        assertTrue(monitor.hasWifiNetwork())
        monitor.close()
    }

    @Test fun `no Wi-Fi is distinguished from unsupported address configuration`() {
        val context = mockk<Context>(relaxed = true)
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.allNetworks } returns emptyArray()

        val monitor = DirconNetworkMonitor(context)
        assertFalse(monitor.hasWifiNetwork())
        assertNull(monitor.currentEndpoint())
    }
}
