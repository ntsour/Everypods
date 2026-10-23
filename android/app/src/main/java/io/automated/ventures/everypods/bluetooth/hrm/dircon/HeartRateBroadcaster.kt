/* Copyright (C) 2026 EveryPods contributors. GPL-3.0.
 * DIRCON discovery/notification protocol follows QZ contributors; see DirconProtocol. */
package io.automated.ventures.everypods.bluetooth.hrm.dircon

import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingState
import io.automated.ventures.everypods.bluetooth.hrm.SharingError
import io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetSocketAddress
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

private data class DirconPendingWrite(
    val bytes: ByteArray,
    val measurement: Boolean,
    val queuedAtNs: Long,
    val epoch: Long,
)

interface DirconTransport {
    fun start()
    fun stop(error: SharingError? = null)
    fun snapshot(): HeartRateSharingState
    fun clearReading()
    fun publish(bpm: Int)
}

/** One manually started session. No cloud, auto restart, cached BPM or synthetic data. */
class HeartRateBroadcaster(
    context: Context,
    private val changed: (HeartRateSharingState) -> Unit,
    private val failed: () -> Unit,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : DirconTransport {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val networkMonitor = DirconNetworkMonitor(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(HeartRateSharingState())
    private val clients = ConcurrentHashMap<Socket, Client>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    @Volatile private var started = false
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var registrationLease: DirconNsdRegistrationLease? = null
    @Volatile private var registrationWatchdog: Job? = null
    private var networkWatch: Closeable? = null
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
    override fun snapshot(): HeartRateSharingState = state.value

    /** Disconnect receivers on sensor gaps so no client can mistake old BPM for live data. */
    override fun clearReading() { clients.values.toList().forEach { it.close("sensor_stale") } }

    @Synchronized override fun start() {
        if (started || stopped) return
        started = true
        update { HeartRateSharingState(active = true) }
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= 37 && app.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                    fail(SharingError.PERMISSION); return@launch
                }
                val endpoint = networkMonitor.currentEndpoint()
                if (endpoint == null) {
                    fail(if (networkMonitor.hasWifiNetwork()) SharingError.UNSUPPORTED_NETWORK else SharingError.WIFI_REQUIRED)
                    return@launch
                }
                val wifi = endpoint.network
                val address = endpoint.address
                networkWatch = networkMonitor.watch(endpoint) { if (!stopped) fail(SharingError.NETWORK_LOST) }
                if (networkWatch == null) { fail(SharingError.PERMISSION); return@launch }
                if (stopped) return@launch
                val listener = ServerSocket().apply {
                    reuseAddress = true // Allow Stop/Start even while closed clients are in TIME_WAIT.
                    try { bind(InetSocketAddress(address, 36866), 4) }
                    catch (e: BindException) { close(); fail(SharingError.PORT_IN_USE); return@launch }
                    catch (e: Exception) { close(); throw e }
                }
                synchronized(this@HeartRateBroadcaster) {
                    if (stopped) { listener.close(); return@launch }
                    server = listener
                }
                update { it.copy(port = listener.localPort, address = address.hostAddress.orEmpty()) }
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
                    lateinit var lease: DirconNsdRegistrationLease
                    val listener = object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(service: NsdServiceInfo) {
                            lease.registered()
                        }
                        override fun onRegistrationFailed(service: NsdServiceInfo, code: Int) {
                            lease.registrationFailed(code)
                        }
                        override fun onServiceUnregistered(service: NsdServiceInfo) {
                            Log.i(TAG, "Advertisement removed")
                        }
                        override fun onUnregistrationFailed(service: NsdServiceInfo, code: Int) {
                            Log.w(TAG, "NSD unregister error=$code")
                        }
                    }
                    lease = DirconNsdRegistrationLease(
                        unregister = { runCatching { nsd.unregisterService(listener) } },
                        onRegistered = {
                            synchronized(this@HeartRateBroadcaster) {
                                registrationWatchdog?.cancel()
                                registrationWatchdog = null
                                if (!stopped && registration === listener) {
                                    update { it.copy(advertised = true, name = info.serviceName) }
                                    Log.i(TAG, "Advertised HR service port=${info.port}")
                                }
                            }
                        },
                        onRegistrationFailed = { code ->
                            registrationWatchdog?.cancel()
                            registrationWatchdog = null
                            Log.w(TAG, "NSD registration error=$code")
                            fail(SharingError.ADVERTISEMENT)
                        },
                    )
                    registration = listener
                    registrationLease = lease
                    nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                }
                val pendingRegistration = registration
                registrationWatchdog = scope.launch {
                    delay(NSD_REGISTRATION_TIMEOUT_MS)
                    if (!stopped && registration === pendingRegistration && !state.value.advertised) {
                        Log.w(TAG, "NSD registration deadline exceeded")
                        fail(SharingError.ADVERTISEMENT)
                    }
                }
                while (!stopped) {
                    val socket = listener.accept()
                    acceptClient(socket)
                }
            } catch (e: SecurityException) { if (!stopped) fail(SharingError.PERMISSION) }
            catch (e: Exception) { if (!stopped) { Log.w(TAG, "Server failed: ${e.javaClass.simpleName}"); fail(SharingError.SERVER) } }
        }
    }
    /** Also exercised over real loopback TCP by the transport tests. */
    @Synchronized internal fun acceptClient(socket: Socket) {
        if (clients.size >= 4 || stopped) {
            LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "client_rejected", mapOf(
                "reason" to if (stopped) "session_stopped" else "client_limit",
                "clients" to clients.size,
            ))
            socket.close()
            return
        }
        val client = Client(socket)
        clients[socket] = client
        counts()
        LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "client_accepted", mapOf("clients" to clients.size))
        Log.i(TAG, "Client connected; clients=${clients.size}")
        client.run()
    }

    // Called only for a newly accepted real sensor sample; subscriptions never replay old BPM.
    override fun publish(bpm: Int) {
        if (stopped || bpm !in 1..255) return
        val packet = DirconProtocol.measurement(bpm)
        clients.values.filter { it.subscribed }.forEach { it.enqueue(packet, true) }
    }
    @Synchronized override fun stop(error: SharingError?) {
        if (stopped) return
        stopped = true
        registrationWatchdog?.cancel()
        registrationWatchdog = null
        registrationLease?.stop()
        registrationLease = null
        registration = null
        networkWatch?.let { runCatching { it.close() } }
        networkWatch = null
        networkMonitor.close()
        runCatching { server?.close() }
        server = null
        clients.values.toList().forEach { it.close("session_stop") }
        clients.clear()
        update { it.copy(active = false, advertised = false, clients = 0, subscriptions = 0, error = error) }
        scope.cancel()
        Log.i(TAG, "Sharing stopped; requests=${state.value.requests} sent=${state.value.sent} error=$error")
    }
    private inner class Client(socket: Socket) {
        private val socket = socket
        @Volatile var subscribed = false
        private val responses = Channel<DirconPendingWrite>(16)
        private val latestMeasurement = Channel<DirconPendingWrite>(Channel.CONFLATED)
        private val closed = AtomicBoolean(false)
        private val acceptedAtElapsedMs = elapsedRealtime()
        @Volatile private var subscriptionEpoch = 0L

        /** Control replies and subscription changes share one ordering lock with publishers. */
        @Synchronized fun enqueue(bytes: ByteArray, measurement: Boolean = false): Boolean {
            if (closed.get()) return false
            if (measurement && !subscribed) return false
            val pending = DirconPendingWrite(bytes, measurement, System.nanoTime(), subscriptionEpoch)
            if (measurement) return latestMeasurement.trySend(pending).isSuccess
            if (responses.trySend(pending).isSuccess) return true
            close("response_queue_overflow")
            return false
        }

        @Synchronized fun enqueueReply(reply: DirconProtocol.Reply) {
            // Unsubscribe takes effect before the acknowledgement; any older queued sample
            // carries the previous epoch and will be discarded by the writer.
            if (reply.subscription == false) {
                subscribed = false
                subscriptionEpoch++
            }
            if (!enqueue(reply.frame.encode())) return
            // Subscribe only after its acknowledgement is ahead of all measurements.
            if (reply.subscription == true) {
                subscriptionEpoch++
                subscribed = true
            }
        }
        fun close(reason: String = "closed") {
            if (!closed.compareAndSet(false, true)) return
            subscribed = false
            responses.close()
            latestMeasurement.close()
            runCatching { socket.close() }
            clients.remove(socket)
            counts()
            LidAutoconnectDiagnostics.logConnectionEvent("DIRCON", "client_closed", mapOf(
                "reason" to reason,
                "clients" to clients.size,
                "ageMs" to (elapsedRealtime() - acceptedAtElapsedMs).coerceAtLeast(0L),
            ))
        }
        fun run() {
            scope.launch {
                var closeReason = "writer_stopped"
                try {
                    val output = socket.getOutputStream()
                    while (!socket.isClosed) {
                        val pending = nextWrite() ?: break
                        val (bytes, measurement, arrived, epoch) = pending
                        if (measurement && (!subscribed || epoch != subscriptionEpoch ||
                                System.nanoTime() - arrived >= 5_000_000_000L)) continue
                        val watchdog = scope.launch {
                            delay(5_000L)
                            close("write_timeout")
                        }
                        try { output.write(bytes); output.flush() } finally { watchdog.cancel() }
                        if (measurement) update { it.copy(sent = it.sent + 1) }
                    }
                } catch (_: Exception) { closeReason = "write_failure" }
                finally { close(closeReason) }
            }
            scope.launch {
                var closeReason = "peer_disconnected"
                try {
                    socket.soTimeout = 1_000
                    val input = socket.getInputStream()
                    val decoder = DirconProtocol.Decoder()
                    val buffer = ByteArray(1024)
                    val acceptedAt = acceptedAtElapsedMs
                    var partialStartedAt: Long? = null
                    val requestRateLimiter = DirconRequestRateLimiter(nowElapsedMs = acceptedAt)
                    while (!stopped && !socket.isClosed) {
                        val n = try { input.read(buffer) } catch (_: SocketTimeoutException) {
                            val now = elapsedRealtime()
                            if (!subscribed && now - acceptedAt >= INITIAL_REQUEST_TIMEOUT_MS) {
                                closeReason = "initial_request_timeout"
                                break
                            }
                            if (partialStartedAt?.let { now - it >= PARTIAL_FRAME_TIMEOUT_MS } == true) {
                                closeReason = "partial_frame_timeout"
                                break
                            }
                            continue
                        }
                        if (n < 0) break
                        val hadPartial = decoder.hasPendingBytes
                        val pendingSince = partialStartedAt
                        val requests = decoder.feed(buffer.copyOf(n))
                        for (request in requests) {
                            partialStartedAt = null
                            val now = elapsedRealtime()
                            if (!requestRateLimiter.tryAcquire(now)) {
                                Log.w(TAG, "Client request rate limit exceeded")
                                closeReason = "request_rate_limit"
                                break
                            }
                            val reply = DirconProtocol.respond(request)
                            update { it.copy(requests = it.requests + 1) }
                            // Queue response and change subscription atomically with publishers.
                            enqueueReply(reply)
                            if (reply.subscription != null) counts()
                        }
                        if (closeReason == "request_rate_limit") break
                        partialStartedAt = when {
                            !decoder.hasPendingBytes -> null
                            hadPartial && requests.isEmpty() -> pendingSince
                            else -> elapsedRealtime()
                        }
                        if (partialStartedAt?.let { elapsedRealtime() - it >= PARTIAL_FRAME_TIMEOUT_MS } == true) {
                            closeReason = "partial_frame_timeout"
                            break
                        }
                    }
                } catch (e: Exception) {
                    closeReason = "protocol_or_read_failure"
                    if (!stopped) Log.w(TAG, "Client closed: ${e.javaClass.simpleName}")
                } finally { close(closeReason) }
            }
        }

        /** Drain reliable control replies first; only the newest pending sample is retained. */
        private suspend fun nextWrite(): DirconPendingWrite? {
            while (true) {
                responses.tryReceive().getOrNull()?.let { return it }
                latestMeasurement.tryReceive().getOrNull()?.let { return it }
                val next = select<DirconPendingWrite?> {
                    responses.onReceiveCatching { it.getOrNull() }
                    latestMeasurement.onReceiveCatching { it.getOrNull() }
                }
                return next
            }
        }
    }
    companion object {
        private const val TAG = "EveryPodsDIRCON"
        private const val NSD_REGISTRATION_TIMEOUT_MS = 10_000L
        private const val INITIAL_REQUEST_TIMEOUT_MS = 15_000L
        private const val PARTIAL_FRAME_TIMEOUT_MS = 5_000L
    }
}
