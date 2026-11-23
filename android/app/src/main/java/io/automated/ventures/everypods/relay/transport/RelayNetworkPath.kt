/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.relay.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import android.system.OsConstants
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkMonitor
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** One explicit local interface. No process-wide network binding or Internet route changes. */
data class RelayNetworkPath(
    val interfaceName: String,
    val address: String,
    val prefix: Int,
    val networkHandle: Long,
) {
    fun contains(other: String): Boolean {
        val local = ipv4(address)
        val remote = ipv4(other)
        val mask = -1L shl (32 - prefix)
        return (local and mask) == (remote and mask)
    }

    fun available(context: Context): Boolean = runCatching {
        val iface = NetworkInterface.getByName(interfaceName) ?: return false
        if (!iface.isUp || iface.interfaceAddresses.none { it.address.hostAddress == address }) return false
        networkHandle == 0L || context.getSystemService(ConnectivityManager::class.java).allNetworks.any {
            it.networkHandle == networkHandle
        }
    }.getOrDefault(false)

    /**
     * A VPN that does not allow bypass makes Netd reject binding this app's sockets to
     * any other Network, even when the VPN excludes the LAN from its routes. WebRTC
     * then gathers no candidate on the pinned network. In that case pin by interface
     * address only (handle zero), as for unregistered hotspot interfaces.
     */
    fun forSocketBinding(context: Context): RelayNetworkPath {
        if (networkHandle == 0L) return this
        val bindable = runCatching {
            val network = context.getSystemService(ConnectivityManager::class.java).allNetworks
                .firstOrNull { it.networkHandle == networkHandle } ?: return@runCatching false
            val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)
            try { network.bindSocket(fd); true } finally { Os.close(fd) }
        }.getOrDefault(false)
        return withNetworkBinding(bindable)
    }

    fun withNetworkBinding(bindable: Boolean): RelayNetworkPath = if (bindable) this else copy(networkHandle = 0L)

    companion object {
        fun ipv4(text: String): Long {
            val parts = text.split('.')
            require(parts.size == 4)
            return parts.fold(0L) { value, part ->
                require(part.isNotEmpty() && part.all(Char::isDigit))
                val octet = part.toInt()
                require(octet in 0..255)
                (value shl 8) or octet.toLong()
            }
        }

        private fun wifiPaths(context: Context, downstream: Boolean = false): List<RelayNetworkPath> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.flatMap { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
                // Android 15+ can expose the hotspot host as a registered local
                // Network, distinct from the upstream Wi-Fi client connection.
                val isDownstream = android.os.Build.VERSION.SDK_INT >= 35 &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                if (isDownstream != downstream) return@flatMap emptyList()
                val links = cm.getLinkProperties(network) ?: return@flatMap emptyList()
                val name = links.interfaceName ?: return@flatMap emptyList()
                links.linkAddresses.filter { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                    .map { RelayNetworkPath(name, it.address.hostAddress!!, it.prefixLength, network.networkHandle) }
            }
        }

        private fun unregisteredPrivatePaths(context: Context): List<RelayNetworkPath> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val registered = cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
            return runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
                    it.isUp && !it.isLoopback && !it.isPointToPoint && it.name !in registered
                }.flatMap { iface ->
                    iface.interfaceAddresses.filter { it.address is Inet4Address && it.address.isSiteLocalAddress && it.networkPrefixLength in 1..30 }
                        .map { RelayNetworkPath(iface.name, it.address.hostAddress!!, it.networkPrefixLength.toInt(), 0L) }
                }
            }.getOrDefault(emptyList())
        }

        /** Receiver selects either its registered Wi-Fi or its downstream AP interface. */
        fun receiver(context: Context, mode: String): RelayNetworkPath {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val registeredHotspots = wifiPaths(context, downstream = true)
            val shared = wifiPaths(context)
            val paths = when (mode) {
                "wifi" -> shared
                "hotspot" -> registeredHotspots + unregisteredPrivatePaths(context)
                else -> {
                    val activeHandle = cm.activeNetwork?.networkHandle
                    val active = shared.filter { it.networkHandle == activeHandle }
                    if (active.isNotEmpty()) active else if (registeredHotspots.isNotEmpty()) registeredHotspots else shared + unregisteredPrivatePaths(context)
                }
            }
            val candidates = paths.distinctBy { it.interfaceName to it.address }
            android.util.Log.i("RelayNetworkPath", "Receiver mode=$mode choices=${candidates.map { "${it.interfaceName}:${it.address}/${it.prefix} handle=${it.networkHandle}" }}")
            val activeHandle = cm.activeNetwork?.networkHandle
            return candidates.singleOrNull() ?: candidates.singleOrNull { mode != "hotspot" && it.networkHandle == activeHandle }
                ?: error("Selected relay network unavailable or ambiguous")
        }

        /** Sender must have a Wi-Fi address on the receiver's selected subnet. */
        fun sender(context: Context, receiverAddress: String, prefix: Int): RelayNetworkPath {
            require(prefix in 1..30)
            val remote = RelayNetworkPath("", receiverAddress, prefix, 0L)
            val registered = wifiPaths(context)
            val registeredNames = registered.map { it.interfaceName }.toSet()
            val localInterfaces = unregisteredPrivatePaths(context)
            val all = registered + localInterfaces.filter { it.interfaceName !in registeredNames }
            val candidates = all.filter { it.prefix == prefix && it.address != receiverAddress && remote.contains(it.address) }
                .distinctBy { it.interfaceName to it.address }
            val activeHandle = context.getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle
            android.util.Log.i("RelayNetworkPath", "Sender choices=${candidates.map { "${it.interfaceName}:${it.address}/${it.prefix} handle=${it.networkHandle}" }} active=$activeHandle")
            return pickSender(candidates, activeHandle) ?: error("No Wi-Fi interface on receiver subnet")
        }

        /**
         * Picks the sender's interface among those on the receiver's subnet. Phones with dual Wi-Fi (e.g. Xiaomi
         * "Wi-Fi acceleration") join the same LAN on two interfaces: prefer the system's default network, then a
         * registered Wi-Fi network over an unregistered interface, then the first in a stable order.
         */
        internal fun pickSender(candidates: List<RelayNetworkPath>, activeHandle: Long?): RelayNetworkPath? =
            candidates.singleOrNull()
                ?: candidates.firstOrNull { activeHandle != null && it.networkHandle == activeHandle }
                ?: candidates.filter { it.networkHandle != 0L }.minByOrNull { it.interfaceName }
                ?: candidates.minByOrNull { it.interfaceName }
    }
}

/** WebRTC sees only the negotiated interface, rather than every Android adapter. */
object RelayNetworkMonitor {
    fun install(path: RelayNetworkPath) {
        val monitor = NetworkMonitor.getInstance()
        check(monitor.numObservers == 0) { "Previous relay network monitor is still active" }
        monitor.setNetworkChangeDetectorFactory { _, _ ->
            object : NetworkChangeDetector {
                override fun getCurrentConnectionType() = NetworkChangeDetector.ConnectionType.CONNECTION_WIFI
                override fun supportNetworkCallback() = true
                override fun getActiveNetworkList() = listOf(NetworkChangeDetector.NetworkInformation(
                    path.interfaceName,
                    NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
                    NetworkChangeDetector.ConnectionType.CONNECTION_NONE,
                    // AP interfaces have no ConnectivityManager Network. Handle zero
                    // makes native WebRTC fall back to binding this interface's IP.
                    path.networkHandle,
                    arrayOf(NetworkChangeDetector.IPAddress(InetAddress.getByName(path.address).address)),
                    NetworkChangeDetector.NetworkSlice.NO_SLICE,
                ))
                override fun destroy() = Unit
            }
        }
    }
}
