package io.automated.ventures.everypods.utils.peer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Peer link over Bluetooth LE, peripheral side (docs/peer-ble-transport.md).
 *
 * Hosts the EveryPods peer GATT service and advertises it, so computers (the Mac
 * today) can reach this phone without RFCOMM channel numbers. Characteristics require
 * encryption, so only a device bonded with this phone can use them, and the central's
 * address is its identity address: the address the peer is configured under.
 *
 * Threading: every GATT callback and all session state live on one HandlerThread;
 * listener calls go out in order on a single-thread executor, so the handover logic
 * never runs on (or blocks) the Bluetooth callback thread.
 */
@SuppressLint("MissingPermission")
object PeerGattServer {
    interface Listener {
        /** True if [address] is a configured, coordination-authorized peer. */
        fun isAllowedPeer(address: String): Boolean
        fun localHello(): PeerGattRecords.Hello
        fun onSessionReady(address: String, hello: PeerGattRecords.Hello)
        fun onLegacyPacket(address: String, packet: ByteArray)
        fun onSessionEnded(address: String)
        /** Every [KEEPALIVE_MS] for each ready session (legacy status keep-alive). */
        fun onKeepAliveDue(address: String)
    }

    private const val TAG = "PeerGatt"
    val SERVICE_UUID: UUID = UUID.fromString("78fc4c78-0bdc-435d-b4a8-7d8b5f0c0a04")
    val RX_UUID: UUID = UUID.fromString("78fc4c78-0bdc-435d-b4a8-7d8b5f0c0a05")
    val TX_UUID: UUID = UUID.fromString("78fc4c78-0bdc-435d-b4a8-7d8b5f0c0a06")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val TICK_MS = 1_000L
    private const val NOTIFY_TIMEOUT_MS = 2_000L
    private const val IDLE_UNREADY_MS = 30_000L
    private const val KEEPALIVE_MS = 20_000L
    private const val ADVERTISE_RETRY_BASE_MS = 2_000L
    private const val ADVERTISE_RETRY_MAX_MS = 30_000L
    private const val NOTIFY_RETRY_MS = 50L
    private const val NOTIFY_MAX_REFUSALS = 40

    private class Session(val address: String) {
        val outbox = PeerGattOutbox()
        val decoder = PeerGattRecords.Decoder()
        var subscribed = false
        var hello: PeerGattRecords.Hello? = null
        var ready = false
        var lastHeardMs = SystemClock.elapsedRealtime()
        var lastKeepAliveMs = SystemClock.elapsedRealtime()
        var refusedNotifies = 0
    }

    // Daemon threads: they must never keep a process alive on their own.
    @Volatile private var threadStarted = false
    private val thread by lazy { threadStarted = true; HandlerThread("PeerGatt").apply { isDaemon = true; start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val delivery by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "PeerGattDelivery").apply { isDaemon = true } }
    }

    // Owned by [handler]'s thread.
    private var context: Context? = null
    private var listener: Listener? = null
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var running = false
    private var advertising = false
    private var advertisingLowPower = false
    private var advertiseRetries = 0
    private var advertiseGivenUp = false
    private val sessions = HashMap<String, Session>()

    // Read from any thread.
    private val readyAddresses = ConcurrentHashMap.newKeySet<String>()

    fun isReady(address: String): Boolean = address.uppercase() in readyAddresses
    fun hasReadySession(): Boolean = readyAddresses.isNotEmpty()

    /** Starts (or keeps) the server. Safe to call repeatedly, e.g. from every CrossDevice.init. */
    fun start(context: Context, listener: Listener) {
        val app = context.applicationContext
        handler.post {
            this.context = app
            this.listener = listener
            if (running) return@post
            openServer()
        }
    }

    /** Closes the server and drops every session (Bluetooth off, coordination off, service stop). */
    fun stop(reason: String) {
        if (!threadStarted) return  // never started: nothing to close (and no thread to create)
        handler.post { close(reason) }
    }

    /** Drops the session for a peer that was removed from the configuration. */
    fun forget(address: String) {
        if (!threadStarted) return
        handler.post { sessions.remove(address.uppercase())?.let { endSession(it, "peer removed") } }
    }

    /** Queues one legacy packet to [address]; false if no ready session reaches it. */
    fun send(address: String, packet: ByteArray): Boolean {
        val key = address.uppercase()
        if (key !in readyAddresses || packet.isEmpty() || packet.size > PeerGattRecords.MAX_PAYLOAD) return false
        val record = PeerGattRecords.encode(PeerGattRecords.LEGACY, packet)
        handler.post { sessions[key]?.takeIf { it.ready }?.let { enqueue(it, record) } }
        return true
    }

    // --- server lifecycle (handler thread) ---

    private fun hasPermissions(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
            ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openServer() {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            Log.w(TAG, "not starting: Bluetooth connect/advertise permission missing")
            return
        }
        val manager = ctx.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        if (!adapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "not starting: this device cannot advertise (no LE peripheral role)")
            return
        }
        val s = try { manager.openGattServer(ctx, callback) } catch (e: SecurityException) {
            Log.w(TAG, "openGattServer denied: ${e.message}")
            null
        } ?: run {
            Log.w(TAG, "openGattServer failed")
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        val txChar = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        txChar.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
        ))
        service.addCharacteristic(rx)
        service.addCharacteristic(txChar)
        server = s
        tx = txChar
        running = true
        advertiseRetries = 0
        advertiseGivenUp = false
        if (!s.addService(service)) {
            Log.w(TAG, "addService refused")
            close("addService refused")
            return
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        // Advertising starts from onServiceAdded.
    }

    private fun close(reason: String) {
        if (!running && server == null) return
        Log.d(TAG, "closing ($reason)")
        running = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(restartAdvertising)
        stopAdvertising()
        sessions.values.toList().forEach { endSession(it, reason) }
        sessions.clear()
        server?.runCatching { close() }
        server = null
        tx = null
    }

    // --- advertising ---

    private fun startAdvertising() {
        if (!running || advertiseGivenUp) return
        val ctx = context ?: return
        val advertiser = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: run {
            scheduleAdvertiseRestart("no advertiser")
            return
        }
        val lowPower = sessions.values.any { it.ready }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (lowPower) AdvertiseSettings.ADVERTISE_MODE_LOW_POWER else AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            advertisingLowPower = lowPower
        } catch (e: SecurityException) {
            Log.w(TAG, "startAdvertising denied: ${e.message}")
        } catch (e: IllegalStateException) {
            scheduleAdvertiseRestart("adapter not ready")
        }
    }

    private fun stopAdvertising() {
        val advertiser = context?.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    /** Low latency while no computer is linked (fast relink), low power once one is. */
    private fun updateAdvertisingRate() {
        if (!running || !advertising) return
        val wantLowPower = sessions.values.any { it.ready }
        if (wantLowPower == advertisingLowPower) return
        stopAdvertising()
        startAdvertising()
    }

    private val restartAdvertising = Runnable {
        stopAdvertising()
        startAdvertising()
    }

    private fun scheduleAdvertiseRestart(reason: String) {
        advertiseRetries++
        val delay = (ADVERTISE_RETRY_BASE_MS * advertiseRetries).coerceAtMost(ADVERTISE_RETRY_MAX_MS)
        Log.w(TAG, "advertising restart in ${delay} ms ($reason, attempt $advertiseRetries)")
        handler.removeCallbacks(restartAdvertising)
        handler.postDelayed(restartAdvertising, delay)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            handler.post {
                advertising = true
                advertiseRetries = 0
                Log.d(TAG, "advertising (${if (advertisingLowPower) "low power" else "low latency"})")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                advertising = false
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> advertising = true
                    ADVERTISE_FAILED_DATA_TOO_LARGE, ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                        advertiseGivenUp = true
                        Log.w(TAG, "advertising unavailable (error $errorCode); computers will use RFCOMM")
                    }
                    else -> scheduleAdvertiseRestart("error $errorCode")
                }
            }
        }
    }

    // --- sessions ---

    private fun allowed(address: String): Boolean = listener?.isAllowedPeer(address) == true

    private fun sessionFor(device: BluetoothDevice): Session =
        sessions.getOrPut(device.address.uppercase()) { Session(device.address.uppercase()) }

    private fun deliver(block: Listener.() -> Unit) {
        val l = listener ?: return
        delivery.execute { runCatching { l.block() }.onFailure { Log.w(TAG, "listener failed", it) } }
    }

    private fun endSession(session: Session, reason: String) {
        session.outbox.clear()
        session.decoder.reset()
        session.subscribed = false
        session.hello = null
        unready(session, reason)
    }

    private fun unready(session: Session, reason: String) {
        if (!session.ready) return
        session.ready = false
        readyAddresses.remove(session.address)
        Log.d(TAG, "[${session.address}] link down ($reason)")
        deliver { onSessionEnded(session.address) }
        updateAdvertisingRate()
    }

    private fun maybeReady(session: Session) {
        val hello = session.hello ?: return
        if (session.ready || !session.subscribed) return
        session.ready = true
        session.lastKeepAliveMs = SystemClock.elapsedRealtime()
        readyAddresses.add(session.address)
        Log.d(TAG, "[${session.address}] link ready with '${hello.name}' (${hello.installationId.take(8)})")
        deliver { onSessionReady(session.address, hello) }
        updateAdvertisingRate()
    }

    private fun enqueue(session: Session, record: ByteArray) {
        if (!session.outbox.enqueue(record)) {
            Log.w(TAG, "[${session.address}] send queue full; dropping link state")
            endSession(session, "send queue full")
            return
        }
        pump(session)
    }

    private fun pump(session: Session) {
        val s = server ?: return
        val c = tx ?: return
        if (!session.subscribed) return
        val chunk = session.outbox.next(SystemClock.elapsedRealtime()) ?: return
        val device = context?.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(session.address) ?: return
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                s.notifyCharacteristicChanged(device, c, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = chunk
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, c, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[${session.address}] notify failed: ${e.message}")
            false
        }
        if (accepted) {
            session.refusedNotifies = 0
            return
        }
        session.outbox.onRefused()
        if (++session.refusedNotifies >= NOTIFY_MAX_REFUSALS) {
            Log.w(TAG, "[${session.address}] notifications refused for 2 s; resetting the session")
            session.refusedNotifies = 0
            endSession(session, "notify refused")
            return
        }
        handler.postDelayed({ if (sessions[session.address] === session) pump(session) }, NOTIFY_RETRY_MS)
    }

    private fun onRecords(session: Session, bytes: ByteArray) {
        val records = try { session.decoder.feed(bytes) } catch (e: PeerGattRecords.ProtocolException) {
            Log.w(TAG, "[${session.address}] bad stream (${e.message}); resetting the session")
            endSession(session, "bad stream")
            return
        }
        session.lastHeardMs = SystemClock.elapsedRealtime()
        for (record in records) when (record.type) {
            PeerGattRecords.HELLO -> {
                val hello = try { PeerGattRecords.parseHello(record.payload) } catch (e: PeerGattRecords.ProtocolException) {
                    Log.w(TAG, "[${session.address}] bad HELLO (${e.message})")
                    endSession(session, "bad hello")
                    return
                }
                session.hello = hello
                maybeReady(session)
            }
            PeerGattRecords.LEGACY -> if (session.ready) {
                val packet = record.payload
                deliver { onLegacyPacket(session.address, packet) }
            }
            PeerGattRecords.PING -> enqueue(session, PeerGattRecords.encode(PeerGattRecords.PONG, record.payload))
            else -> Unit  // PONG and V2 are not expected here yet; unknown types are skipped
        }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            for (session in sessions.values.toList()) {
                if (session.outbox.expireInFlight(now, NOTIFY_TIMEOUT_MS)) pump(session)
                if (session.ready && now - session.lastHeardMs > IDLE_UNREADY_MS) {
                    unready(session, "silent for ${IDLE_UNREADY_MS / 1000} s")
                }
                if (session.ready && now - session.lastKeepAliveMs >= KEEPALIVE_MS) {
                    session.lastKeepAliveMs = now
                    deliver { onKeepAliveDue(session.address) }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (!running) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
                else {
                    Log.w(TAG, "service not added (status $status)")
                    close("service not added")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                val key = device.address.uppercase()
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (allowed(key)) sessionFor(device)
                } else {
                    sessions.remove(key)?.let { endSession(it, "disconnected") }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { sessions[device.address.uppercase()]?.outbox?.mtu = mtu }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val key = device.address.uppercase()
            val ok = descriptor.uuid == CCCD_UUID && allowed(key)
            if (responseNeeded) {
                server?.sendResponse(device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION, 0, null)
            }
            if (!ok) return
            val enable = value?.getOrNull(0)?.toInt()?.and(0x01) == 1
            handler.post {
                val session = sessionFor(device)
                if (enable) {
                    // A (re)subscribe starts a fresh stream: the central rediscovered or reconnected.
                    session.outbox.clear()
                    session.decoder.reset()
                    session.subscribed = true
                    session.lastHeardMs = SystemClock.elapsedRealtime()
                    listener?.let { enqueue(session, PeerGattRecords.encodeHello(it.localHello())) }
                    maybeReady(session)
                } else {
                    endSession(session, "unsubscribed")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val key = device.address.uppercase()
            val ok = characteristic.uuid == RX_UUID && !preparedWrite && offset == 0 && allowed(key)
            if (responseNeeded) {
                server?.sendResponse(device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION, 0, null)
            }
            if (!ok || value == null) return
            val bytes = value.copyOf()
            handler.post { sessions[key]?.let { onRecords(it, bytes) } }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                val session = sessions[device.address.uppercase()] ?: return@post
                session.outbox.onSent()
                pump(session)
            }
        }
    }
}
