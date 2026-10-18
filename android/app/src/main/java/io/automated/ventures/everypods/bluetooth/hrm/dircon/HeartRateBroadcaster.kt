/* Copyright (C) 2026 EveryPods contributors. GPL-3.0.
 * DIRCON discovery/notification protocol follows QZ contributors; see DirconProtocol. */
package io.automated.ventures.everypods.bluetooth.hrm.dircon

import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingState
import io.automated.ventures.everypods.bluetooth.hrm.SharingError
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One manually started session. No cloud, auto restart, cached BPM or synthetic data. */
class HeartRateBroadcaster(
    context: Context,
    private val changed: (HeartRateSharingState) -> Unit,
    private val failed: () -> Unit
) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(HeartRateSharingState())
    private val clients = ConcurrentHashMap<Socket, Client>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    private var registration: NsdManager.RegistrationListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private fun update(change: (HeartRateSharingState) -> HeartRateSharingState) {
        state.update(change)
        changed(state.value)
    }
    private fun counts() = update { it.copy(clients = clients.size, subscriptions = clients.values.count { c -> c.subscribed }) }
    private fun fail(error: SharingError) {
        if (stopped) return
        Log.w(TAG, "Sharing failure=$error")
        stop(error)
        failed()
    }
    fun start() {
        update { HeartRateSharingState(active = true) }
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= 37 && app.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                    fail(SharingError.PERMISSION); return@launch
                }
                val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                val address = wifi?.let { cm.getLinkProperties(it)?.linkAddresses?.map { a -> a.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { a -> a.isSiteLocalAddress } }
                if (wifi == null || address == null) { fail(SharingError.WIFI_REQUIRED); return@launch }
                val listener = ServerSocket().apply {
                    reuseAddress = true // Allow Stop/Start even while closed clients are in TIME_WAIT.
                    try { bind(InetSocketAddress(address, 36866), 4) }
                    catch (e: Exception) { close(); throw e }
                }
                synchronized(this@HeartRateBroadcaster) {
                    if (stopped) { listener.close(); return@launch }
                    server = listener
                }
                update { it.copy(port = listener.localPort) }
                val prefs = app.getSharedPreferences("heart_rate_sharing", Context.MODE_PRIVATE)
                val identity = prefs.getString("identity", null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString("identity", it).apply()
                }
                val hex = identity.replace("-", "").take(10).chunked(2)
                val mac = (listOf("02") + hex).joinToString(":")
                val info = NsdServiceInfo().apply {
                    serviceName = "EveryPods HR"
                    serviceType = "_wahoo-fitness-tnp._tcp"
                    port = listener.localPort
                    network = wifi
                    setAttribute("ble-service-uuids", "0000180d-0000-1000-8000-00805f9b34fb,0000180a-0000-1000-8000-00805f9b34fb")
                    setAttribute("mac-address", mac)
                    setAttribute("serial-number", identity.take(8))
                }
                synchronized(this@HeartRateBroadcaster) {
                    if (stopped) return@launch
                    registration = object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(service: NsdServiceInfo) {
                            if (stopped) {
                                // Stop may race the asynchronous registration callback.
                                runCatching { nsd.unregisterService(this) }
                            } else {
                                update { it.copy(advertised = true, name = service.serviceName) }
                                Log.i(TAG, "Advertised HR service port=${service.port}")
                            }
                        }
                        override fun onRegistrationFailed(service: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD registration error=$code"); fail(SharingError.ADVERTISEMENT) }
                        override fun onServiceUnregistered(service: NsdServiceInfo) { Log.i(TAG, "Advertisement removed") }
                        override fun onUnregistrationFailed(service: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD unregister error=$code") }
                    }.also { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }
                    networkCallback = object : ConnectivityManager.NetworkCallback() {
                        override fun onLost(network: Network) { if (network == wifi) fail(SharingError.NETWORK_LOST) }
                        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
                            if (network == wifi && properties.linkAddresses.none { it.address == address }) fail(SharingError.NETWORK_LOST)
                        }
                    }.also { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it) }
                }
                while (!stopped) {
                    val socket = listener.accept()
                    synchronized(this@HeartRateBroadcaster) {
                        if (clients.size >= 4 || stopped) socket.close()
                        else {
                            val client = Client(socket)
                            clients[socket] = client
                            counts()
                            Log.i(TAG, "Client connected; clients=${clients.size}")
                            client.run()
                        }
                    }
                }
            } catch (e: SecurityException) { if (!stopped) fail(SharingError.PERMISSION) }
            catch (e: Exception) { if (!stopped) { Log.w(TAG, "Server failed: ${e.javaClass.simpleName}"); fail(SharingError.SERVER) } }
        }
    }
    // Called only for a newly accepted real sensor sample; subscriptions never replay old BPM.
    fun publish(bpm: Int) {
        if (stopped) return
        val packet = DirconProtocol.measurement(bpm)
        clients.values.filter { it.subscribed }.forEach { it.enqueue(packet, true) }
    }
    @Synchronized fun stop(error: SharingError? = null) {
        if (stopped) return
        stopped = true
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        runCatching { server?.close() }
        server = null
        clients.values.toList().forEach { it.close() }
        clients.clear()
        update { it.copy(active = false, advertised = false, clients = 0, subscriptions = 0, error = error) }
        scope.cancel()
        Log.i(TAG, "Sharing stopped; requests=${state.value.requests} sent=${state.value.sent} error=$error")
    }
    private inner class Client(private val socket: Socket) {
        @Volatile var subscribed = false
        private val queue = Channel<Triple<ByteArray, Boolean, Long>>(16)
        fun enqueue(bytes: ByteArray, measurement: Boolean = false) {
            if (!queue.trySend(Triple(bytes, measurement, System.nanoTime())).isSuccess) close()
        }
        fun close() {
            subscribed = false
            queue.close()
            runCatching { socket.close() }
            clients.remove(socket)
            counts()
        }
        fun run() {
            scope.launch {
                try {
                    val output = socket.getOutputStream()
                    for ((bytes, measurement, arrived) in queue) {
                        if (measurement && System.nanoTime() - arrived >= 5_000_000_000L) continue
                        output.write(bytes); output.flush()
                        if (measurement) update { it.copy(sent = it.sent + 1) }
                    }
                } catch (_: Exception) { } finally { close() }
            }
            scope.launch {
                try {
                    socket.soTimeout = 15_000 // Bound incomplete or abandoned requests.
                    val input = socket.getInputStream()
                    val decoder = DirconProtocol.Decoder()
                    val buffer = ByteArray(1024)
                    while (!stopped && !socket.isClosed) {
                        val n = try { input.read(buffer) } catch (_: SocketTimeoutException) {
                            if (subscribed) continue else break
                        }
                        if (n < 0) break
                        for (request in decoder.feed(buffer.copyOf(n))) {
                            val reply = DirconProtocol.respond(request)
                            update { it.copy(requests = it.requests + 1) }
                            // Queue response before enabling notifications, preserving wire order.
                            enqueue(reply.frame.encode())
                            reply.subscription?.let { subscribed = it; counts() }
                            Log.i(TAG, "Request id=${request.id} code=${reply.frame.code} subscribed=$subscribed")
                        }
                    }
                } catch (e: Exception) { if (!stopped) Log.w(TAG, "Client closed: ${e.javaClass.simpleName}") }
                finally { close() }
            }
        }
    }
    companion object { private const val TAG = "EveryPodsDIRCON" }
}
