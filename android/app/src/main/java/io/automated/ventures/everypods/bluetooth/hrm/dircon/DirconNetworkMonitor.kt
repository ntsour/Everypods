package io.automated.ventures.everypods.bluetooth.hrm.dircon

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import java.io.Closeable
import java.net.Inet4Address

/** Selects one suitable IPv4 Wi-Fi network and watches that exact Android Network identity. */
open class DirconNetworkMonitor(context: Context) {
    data class Endpoint(val network: Network, val address: Inet4Address)

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    open fun currentEndpoint(): Endpoint? {
        val cm = connectivity ?: return null
        val candidates = runCatching {
            cm.allNetworks.mapNotNull { network ->
                if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@mapNotNull null
                val address = cm.getLinkProperties(network)?.linkAddresses?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.filter { it.isSiteLocalAddress }
                    ?.sortedBy { it.hostAddress }
                    ?.firstOrNull() ?: return@mapNotNull null
                Endpoint(network, address)
            }
        }.getOrDefault(emptyList())
        val active = runCatching { cm.activeNetwork }.getOrNull()
        return candidates.firstOrNull { it.network == active }
            ?: candidates.sortedBy { it.network.toString() }.firstOrNull()
    }

    open fun hasWifiNetwork(): Boolean {
        val cm = connectivity ?: return false
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(false)
    }

    @Synchronized open fun watchWifiAvailability(changed: () -> Unit): Closeable? {
        val cm = connectivity ?: return null
        close()
        val local = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { changed() }
            override fun onLost(network: Network) { changed() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { changed() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { changed() }
        }
        return try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), local,
            )
            callback = local
            Closeable { synchronized(this) { if (callback === local) close() } }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Registers before the broadcaster binds, then rejects a stale selection race. */
    @Synchronized open fun watch(endpoint: Endpoint, invalidated: () -> Unit): Closeable? {
        val cm = connectivity ?: return null
        close()
        val local = object : ConnectivityManager.NetworkCallback() {
            private fun check(network: Network, properties: LinkProperties? = null) {
                if (network != endpoint.network) return
                val stillSuitable = runCatching {
                    cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        (properties ?: cm.getLinkProperties(network))?.linkAddresses?.any { it.address == endpoint.address } == true
                }.getOrDefault(false)
                if (!stillSuitable) invalidated()
            }
            override fun onLost(network: Network) { if (network == endpoint.network) invalidated() }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = check(network, linkProperties)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (network == endpoint.network && !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) invalidated()
            }
        }
        return try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), local,
            )
            callback = local
            val current = currentEndpoint()
            if (current?.network != endpoint.network || current.address != endpoint.address) invalidated()
            Closeable { synchronized(this) { if (callback === local) close() } }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    @Synchronized open fun close() {
        val old = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(old) }
    }
}
