package io.automated.ventures.everypods.utils.peer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.utils.peer.PeerGattClientPolicy as Policy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Peer link over Bluetooth LE, central side: dials the [PeerGattServer] of another phone
 * (docs/peer-ble-transport.md). Connects directly by bonded address (no scan), one GATT
 * operation in flight per link, and follows the Nordic BLE library / bitchat patterns:
 * link ids against stale callbacks, disconnect-then-close teardown, backoff redial.
 *
 * Threading: all state lives on one daemon HandlerThread; listener calls go out in order
 * on a single daemon executor.
 */
@SuppressLint("MissingPermission")
object PeerGattClient {
    interface Listener {
        fun localHello(): PeerGattRecords.Hello
        fun onSessionReady(address: String, hello: PeerGattRecords.Hello)
        fun onLegacyPacket(address: String, packet: ByteArray)
        fun onSessionEnded(address: String)
        /** Every 20 s while the link is ready. */
        fun onKeepAliveDue(address: String)
        /** A record this client does not handle itself (SETUP_ACK, V2), on a ready link. */
        fun onRecord(address: String, type: Int, payload: ByteArray) {}
    }

    private const val TAG = "PeerGattClient"
    private val SERVICE_UUID: UUID = PeerGattServer.SERVICE_UUID
    private val RX_UUID: UUID = PeerGattServer.RX_UUID
    private val TX_UUID: UUID = PeerGattServer.TX_UUID
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val TICK_MS = 1_000L
    private const val REQUEST_MTU = 517
    private const val WRITE_RETRY_MS = 50L
    private const val WRITE_MAX_REFUSALS = 40
    private const val WRITE_TIMEOUT_MS = 2_000L
    private const val REDISCOVER_DELAY_MS = 200L
    /** Room for one fragmented 160 KiB message (PeerLeChannel) on top of normal traffic. */
    private const val QUEUE_LIMIT_BYTES = 256 * 1024

    private enum class Stage { CONNECTING, SETTLING, MTU, DISCOVERING, SUBSCRIBING, HANDSHAKE, READY, CLOSING }

    private class Link(val address: String, val id: Long, val startedMs: Long) {
        var gatt: BluetoothGatt? = null
        var stage = Stage.CONNECTING
        var deadlineMs = Long.MAX_VALUE
        var rx: BluetoothGattCharacteristic? = null
        var tx: BluetoothGattCharacteristic? = null
        var cccd: BluetoothGattDescriptor? = null
        val outbox = PeerGattOutbox(QUEUE_LIMIT_BYTES)
        val decoder = PeerGattRecords.Decoder()
        var peerHello: PeerGattRecords.Hello? = null
        var refreshed = false
        var failure = false
        var finished = false
        var readyAnnounced = false
        var lastHeardMs = startedMs
        var lastPingMs = startedMs
        var lastKeepAliveMs = startedMs
        var refusals = 0
    }

    // Daemon threads: they must never keep a process alive on their own.
    @Volatile private var threadStarted = false
    private val thread by lazy { threadStarted = true; HandlerThread("PeerGattClient").apply { isDaemon = true; start() } }
    private val handler by lazy { Handler(thread.looper) }
    private val delivery by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "PeerGattClientDelivery").apply { isDaemon = true } }
    }

    // Owned by [handler]'s thread.
    private var context: Context? = null
    private var listener: Listener? = null
    private var running = false
    private var permissionWarned = false
    private var targets: Set<String> = emptySet()
    private val links = HashMap<String, Link>()
    private val schedule = Policy.DialSchedule()
    private val linkIds = Policy.LinkIds()

    // Read from any thread.
    private val readyAddresses = ConcurrentHashMap.newKeySet<String>()

    fun isReady(address: String): Boolean = address.uppercase() in readyAddresses

    @Volatile private var targetSnapshot: Set<String> = emptySet()

    /** True if this phone dials [address] as an LE central (another phone): its connection is not a central's. */
    fun isTarget(address: String): Boolean = address.uppercase() in targetSnapshot
    fun hasReadySession(): Boolean = readyAddresses.isNotEmpty()

    private fun now() = SystemClock.elapsedRealtime()

    /** Starts (or keeps) the client. Safe to call repeatedly; a fresh start forgets all backoff. */
    fun start(context: Context, listener: Listener) {
        val app = context.applicationContext
        handler.post {
            this.context = app
            this.listener = listener
            if (running) return@post
            running = true
            permissionWarned = false
            schedule.reset()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_MS)
            reconcile()
        }
    }

    /** Tears every link down (Bluetooth off, coordination off, service stop). */
    fun stop(reason: String) {
        if (!threadStarted) return  // never started: nothing to close (and no thread to create)
        handler.post {
            if (!running && links.isEmpty()) return@post
            Log.d(TAG, "stopping ($reason)")
            running = false
            targets = emptySet()
            links.values.toList().forEach { teardown(it, reason, failure = false) }
        }
    }

    /** Keep (or start) links to exactly these bonded classic addresses (uppercase); close the rest. */
    fun setTargets(addresses: Set<String>) {
        val wanted = addresses.map { it.uppercase() }.toSet()
        targetSnapshot = wanted
        handler.post {
            targets = wanted
            if (running) reconcile()
        }
    }

    /** Queues one legacy packet to [address]; false if that link is not ready. */
    fun send(address: String, packet: ByteArray): Boolean {
        val key = address.uppercase()
        if (key !in readyAddresses || packet.isEmpty() || packet.size > PeerGattRecords.MAX_PAYLOAD) return false
        val record = PeerGattRecords.encode(PeerGattRecords.LEGACY, packet)
        handler.post { links[key]?.takeIf { it.stage == Stage.READY }?.let { enqueue(it, record) } }
        return true
    }

    /** Queues one record of any type (e.g. SETUP_REQ) to [address]; false if that link is not ready. */
    fun sendRecord(address: String, type: Int, payload: ByteArray): Boolean {
        val key = address.uppercase()
        if (key !in readyAddresses || payload.size > PeerGattRecords.MAX_PAYLOAD) return false
        val record = PeerGattRecords.encode(type, payload)
        handler.post { links[key]?.takeIf { it.stage == Stage.READY }?.let { enqueue(it, record) } }
        return true
    }

    /** Dials [address] now if it is a target, clearing any hold-off and backoff (a user-started setup). */
    fun dialNow(address: String) {
        if (!threadStarted) return
        val key = address.uppercase()
        handler.post {
            schedule.forget(key)
            if (running) reconcile()
        }
    }

    /** Closes the link to [address] and does not redial it for [holdOffMs]. */
    fun drop(address: String, reason: String, holdOffMs: Long) {
        if (!threadStarted) return
        val key = address.uppercase()
        handler.post {
            schedule.holdOff(key, now(), holdOffMs)
            links[key]?.let { teardown(it, reason, failure = false) }
        }
    }

    // --- dialing (handler thread) ---

    private fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun reconcile() {
        val t = now()
        links.values.toList().forEach { if (it.address !in targets && it.stage != Stage.CLOSING) teardown(it, "no longer a target", false) }
        for (address in targets) {
            if (links.containsKey(address) || !schedule.canDial(address, t)) continue
            dial(address, t)
        }
    }

    private fun dial(address: String, t: Long) {
        val ctx = context ?: return
        if (!hasPermission(ctx)) {
            if (!permissionWarned) Log.w(TAG, "not dialing: BLUETOOTH_CONNECT permission missing")
            permissionWarned = true
            return
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val id = linkIds.next(address)
        val link = Link(address, id, t)
        val gatt = try {
            adapter.getRemoteDevice(address).connectGatt(ctx, false, GattCallback(address, id), BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "[$address] connectGatt failed: ${e.message}")
            null
        }
        if (gatt == null) {
            linkIds.release(address, id)
            val delay = schedule.recordFailure(address, t)
            Log.w(TAG, "[$address] connectGatt returned nothing; retry in $delay ms")
            return
        }
        link.gatt = gatt
        link.deadlineMs = t + Policy.CONNECT_TIMEOUT_MS
        links[address] = link
        Log.d(TAG, "[$address] link #$id connecting")
    }

    // --- teardown ---

    /** disconnect(), wait for the callback (max 2 s), then close(). Never leaks the BluetoothGatt. */
    private fun teardown(link: Link, reason: String, failure: Boolean) {
        if (link.finished) return
        if (failure) link.failure = true
        if (link.stage == Stage.CLOSING) return
        Log.d(TAG, "[${link.address}] link #${link.id} closing ($reason)")
        unready(link, reason)
        link.outbox.clear()
        link.decoder.reset()
        link.stage = Stage.CLOSING
        link.deadlineMs = Long.MAX_VALUE
        val gatt = link.gatt
        if (gatt == null) { finish(link); return }
        try { gatt.disconnect() } catch (e: Exception) { finish(link); return }
        later(link, Policy.DISCONNECT_WAIT_MS) { finish(link) }
    }

    private fun finish(link: Link) {
        if (link.finished) return
        link.finished = true
        unready(link, "closed")
        runCatching { link.gatt?.close() }
        link.gatt = null
        if (links[link.address] === link) links.remove(link.address)
        linkIds.release(link.address, link.id)
        val t = now()
        schedule.noteClosed(link.address, t)
        if (link.failure) {
            val delay = schedule.recordFailure(link.address, t)
            Log.d(TAG, "[${link.address}] link #${link.id} failed; redial in $delay ms")
        }
    }

    // --- sessions ---

    private fun deliver(block: Listener.() -> Unit) {
        val l = listener ?: return
        delivery.execute { runCatching { l.block() }.onFailure { Log.w(TAG, "listener failed", it) } }
    }

    private fun unready(link: Link, reason: String) {
        if (!link.readyAnnounced) return
        link.readyAnnounced = false
        readyAddresses.remove(link.address)
        Log.d(TAG, "[${link.address}] link down ($reason)")
        deliver { onSessionEnded(link.address) }
    }

    private fun maybeReady(link: Link) {
        val hello = link.peerHello ?: return
        if (link.stage != Stage.HANDSHAKE) return
        link.stage = Stage.READY
        link.deadlineMs = Long.MAX_VALUE
        val t = now()
        link.lastPingMs = t
        link.lastKeepAliveMs = t
        link.readyAnnounced = true
        schedule.recordReady(link.address)
        readyAddresses.add(link.address)
        Log.d(TAG, "[${link.address}] link ready with '${hello.name}' (${hello.installationId.take(8)}), mtu ${link.outbox.mtu}")
        deliver { onSessionReady(link.address, hello) }
    }

    private fun enter(link: Link, stage: Stage, timeoutMs: Long = Policy.STAGE_TIMEOUT_MS) {
        link.stage = stage
        link.deadlineMs = now() + timeoutMs
    }

    /** Runs [block] on the handler thread after [delayMs] unless [link] has been closed meanwhile. */
    private fun later(link: Link, delayMs: Long, block: () -> Unit) {
        handler.postDelayed({
            if (!link.finished) block()
        }, delayMs)
    }

    // --- connection steps ---

    private fun requestMtu(link: Link) {
        val gatt = link.gatt ?: return
        enter(link, Stage.MTU)
        val ok = try { gatt.requestMtu(REQUEST_MTU) } catch (e: Exception) { false }
        if (!ok) discover(link)
    }

    private fun discover(link: Link) {
        val gatt = link.gatt ?: return
        enter(link, Stage.DISCOVERING)
        val ok = try { gatt.discoverServices() } catch (e: Exception) { false }
        if (!ok) teardown(link, "discoverServices refused", failure = true)
    }

    /** Hidden BluetoothGatt.refresh() (Nordic clear-cache pattern). */
    private fun refreshCache(gatt: BluetoothGatt): Boolean = try {
        gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean ?: false
    } catch (e: Throwable) {
        false
    }

    private fun onServicesFound(link: Link) {
        val gatt = link.gatt ?: return
        val service = gatt.getService(SERVICE_UUID)
        val rx = service?.getCharacteristic(RX_UUID)
        val tx = service?.getCharacteristic(TX_UUID)
        val cccd = tx?.getDescriptor(CCCD_UUID)
        if (rx == null || tx == null || cccd == null) {
            if (!link.refreshed) {
                link.refreshed = true
                Log.w(TAG, "[${link.address}] peer service missing; clearing the GATT cache and rediscovering")
                refreshCache(gatt)
                enter(link, Stage.DISCOVERING)
                later(link, REDISCOVER_DELAY_MS) { discover(link) }
            } else {
                teardown(link, "peer service not found", failure = true)
            }
            return
        }
        link.rx = rx; link.tx = tx; link.cccd = cccd
        enter(link, Stage.SUBSCRIBING)
        val ok = try {
            gatt.setCharacteristicNotification(tx, true) &&
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } catch (e: Exception) {
            false
        }
        if (!ok) teardown(link, "subscribe refused", failure = true)
    }

    private fun onSubscribed(link: Link) {
        enter(link, Stage.HANDSHAKE)  // waits for the peer's HELLO (it may already have arrived)
        val l = listener ?: return
        enqueue(link, PeerGattRecords.encodeHello(l.localHello()))
        maybeReady(link)
    }

    // --- writes (one at a time) ---

    private fun enqueue(link: Link, record: ByteArray) {
        if (!link.outbox.enqueue(record)) {
            Log.w(TAG, "[${link.address}] send queue full")
            teardown(link, "send queue full", failure = true)
            return
        }
        pump(link)
    }

    private fun pump(link: Link) {
        if (link.stage != Stage.HANDSHAKE && link.stage != Stage.READY) return
        val gatt = link.gatt ?: return
        val rx = link.rx ?: return
        val chunk = link.outbox.next(now()) ?: return
        val result = try {
            gatt.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } catch (e: Exception) {
            Log.w(TAG, "[${link.address}] write failed: ${e.message}")
            teardown(link, "write failed", failure = true)
            return
        }
        if (result == BluetoothStatusCodes.SUCCESS) {
            link.refusals = 0
            return
        }
        link.outbox.onRefused()
        if (++link.refusals >= WRITE_MAX_REFUSALS) {
            teardown(link, "write refused", failure = true)
            return
        }
        later(link, WRITE_RETRY_MS) { pump(link) }
    }

    // --- inbound ---

    private fun onBytes(link: Link, bytes: ByteArray) {
        if (link.stage != Stage.SUBSCRIBING && link.stage != Stage.HANDSHAKE && link.stage != Stage.READY) return
        val records = try { link.decoder.feed(bytes) } catch (e: PeerGattRecords.ProtocolException) {
            Log.w(TAG, "[${link.address}] bad stream (${e.message})")
            teardown(link, "bad stream", failure = true)
            return
        }
        link.lastHeardMs = now()
        for (record in records) when (record.type) {
            PeerGattRecords.HELLO -> {
                val hello = try { PeerGattRecords.parseHello(record.payload) } catch (e: PeerGattRecords.ProtocolException) {
                    Log.w(TAG, "[${link.address}] bad HELLO (${e.message})")
                    teardown(link, "bad hello", failure = true)
                    return
                }
                link.peerHello = hello
                maybeReady(link)
            }
            PeerGattRecords.LEGACY -> if (link.stage == Stage.READY) {
                val packet = record.payload
                deliver { onLegacyPacket(link.address, packet) }
            }
            PeerGattRecords.PING -> if (link.stage != Stage.SUBSCRIBING) enqueue(link, PeerGattRecords.encode(PeerGattRecords.PONG, record.payload))
            PeerGattRecords.PONG -> Unit
            else -> if (link.stage == Stage.READY) {
                val type = record.type
                val payload = record.payload
                deliver { onRecord(link.address, type, payload) }
            }
        }
    }

    // --- periodic work ---

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val t = now()
            for (link in links.values.toList()) checkLink(link, t)
            if (running) reconcile()
            if (running || links.isNotEmpty()) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun checkLink(link: Link, t: Long) {
        if (link.stage == Stage.CLOSING || link.finished) return
        if (t > link.deadlineMs) {
            if (link.stage == Stage.MTU) { discover(link); return }  // no MTU answer: carry on with the default
            teardown(link, "${link.stage} timed out", failure = true)
            return
        }
        if (link.stage != Stage.READY) return
        if (link.outbox.expireInFlight(t, WRITE_TIMEOUT_MS)) pump(link)
        if (Policy.isSilent(link.lastHeardMs, t)) {
            teardown(link, "silent for ${Policy.SILENCE_LIMIT_MS / 1000} s", failure = true)
            return
        }
        if (Policy.pingDue(link.lastPingMs, t)) {
            link.lastPingMs = t
            enqueue(link, PeerGattRecords.encode(PeerGattRecords.PING, ByteArray(0)))
        }
        if (Policy.keepAliveDue(link.lastKeepAliveMs, t)) {
            link.lastKeepAliveMs = t
            deliver { onKeepAliveDue(link.address) }
        }
    }

    // --- GATT callbacks: every one is tied to the link id of the attempt that created it ---

    private class GattCallback(private val address: String, private val id: Long) : BluetoothGattCallback() {
        private fun onLink(block: (Link) -> Unit) {
            handler.post {
                val link = links[address]
                if (link == null || link.id != id || !linkIds.isCurrent(address, id) || link.finished) return@post  // stale
                block(link)
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = onLink { link ->
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (link.stage != Stage.CONNECTING) return@onLink
                Log.d(TAG, "[$address] link #$id connected after ${now() - link.startedMs} ms")
                enter(link, Stage.SETTLING)
                later(link, Policy.SETTLE_AFTER_CONNECT_MS) { if (link.stage == Stage.SETTLING) requestMtu(link) }
            } else if (link.stage == Stage.CLOSING) {
                finish(link)  // the disconnect we asked for
            } else {
                val kind = Policy.classify(status, now() - link.startedMs)
                Log.w(TAG, "[$address] link #$id lost (status $status, $kind, state $newState)")
                link.failure = true
                link.stage = Stage.CLOSING
                finish(link)  // already disconnected: close() right away, redial after backoff
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = onLink { link ->
            if (link.stage != Stage.MTU) return@onLink
            if (status == BluetoothGatt.GATT_SUCCESS) link.outbox.mtu = mtu
            discover(link)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = onLink { link ->
            if (link.stage != Stage.DISCOVERING) return@onLink
            if (status != BluetoothGatt.GATT_SUCCESS) teardown(link, "discovery failed ($status)", failure = true)
            else onServicesFound(link)
        }

        override fun onServiceChanged(gatt: BluetoothGatt) = onLink { link ->
            if (link.stage == Stage.CLOSING || link.stage == Stage.CONNECTING || link.stage == Stage.SETTLING) return@onLink
            Log.w(TAG, "[$address] service changed; refreshing and rediscovering")
            unready(link, "service changed")
            link.outbox.clear()
            link.decoder.reset()
            link.peerHello = null
            link.refreshed = true  // one rediscovery only
            refreshCache(gatt)
            enter(link, Stage.DISCOVERING)
            later(link, REDISCOVER_DELAY_MS) { discover(link) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onLink { link ->
            if (link.stage != Stage.SUBSCRIBING || descriptor.uuid != CCCD_UUID) return@onLink
            if (status != BluetoothGatt.GATT_SUCCESS) teardown(link, "subscribe failed ($status)", failure = true)
            else onSubscribed(link)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = onLink { link ->
            if (characteristic.uuid != RX_UUID) return@onLink
            link.outbox.onSent()
            if (status != BluetoothGatt.GATT_SUCCESS) teardown(link, "write failed ($status)", failure = true)
            else pump(link)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != TX_UUID) return
            val bytes = value.copyOf()
            onLink { link -> onBytes(link, bytes) }
        }
    }
}
