/*
    EveryPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.automated.ventures.everypods.utils.BluetoothCryptography
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.iterator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager for Bluetooth Low Energy scanning operations specifically for AirPods
 */
@OptIn(ExperimentalEncodingApi::class)
class BLEManager(private val context: Context) {

    data class AirPodsStatus(
        val address: String,
        val lastSeen: Long = System.currentTimeMillis(),
        val observedAtElapsedMs: Long = 0L,
        val paired: Boolean = false,
        val model: String = "Unknown",
        val leftBattery: Int? = null,
        val rightBattery: Int? = null,
        val caseBattery: Int? = null,
        val isLeftInEar: Boolean = false,
        val isRightInEar: Boolean = false,
        val isLeftCharging: Boolean = false,
        val isRightCharging: Boolean = false,
        val isCaseCharging: Boolean = false,
        val lidOpen: Boolean = false,
        val color: String = "Unknown",
        val connectionState: String = "Unknown"
    )

    @Synchronized fun getMostRecentStatus(): AirPodsStatus? {
        return deviceStatusMap.values.maxByOrNull { it.observedAtElapsedMs }
    }

    /** Recheck against the current saved key; a previously cached RPA is not enough. */
    @Synchronized fun isIdentityVerified(address: String): Boolean =
        getIrkFromPreferences()?.let { runCatching { BluetoothCryptography.verifyRPA(address, it) }.getOrDefault(false) } == true

    interface AirPodsStatusListener {
        fun onDeviceStatusChanged(device: AirPodsStatus, previousStatus: AirPodsStatus?)
        fun onBroadcastFromNewAddress(device: AirPodsStatus)
        fun onLidStateChanged(lidOpen: Boolean)
        fun onLidStateUnknown() {}
        fun onEarStateChanged(device: AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean)
        fun onBatteryChanged(device: AirPodsStatus)
        fun onDeviceDisappeared()
    }

    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    private var mScanCallback: ScanCallback? = null
    private var probeCallback: ScanCallback? = null
    private var probeTimeout: Runnable? = null
    private var lastProbeAtMs = 0L
    private var airPodsStatusListener: AirPodsStatusListener? = null
    private val deviceStatusMap = mutableMapOf<String, AirPodsStatus>()
    private val verifiedAddresses = mutableSetOf<String>()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var currentGlobalLidState: Boolean? = null
    @Volatile private var lastBroadcastTime: Long = 0
    @Volatile private var lastBroadcastElapsedMs: Long = 0
    private val processedAddresses = mutableSetOf<String>()

    /** True while the service wants BLE proximity scanning (Option 1 / battery / lid). */
    @Volatile private var scanningDesired: Boolean = false
    /** A callback was registered/requested; vendor stacks can silently reject or suspend it. */
    @Volatile private var scanCallbackActive: Boolean = false
    @Volatile private var lastScanStartAtMs: Long = 0L
    @Volatile private var consecutiveScanFailures: Int = 0
    private val scanRetryHandler = Handler(Looper.getMainLooper())
    private var pendingScanRetry: Runnable? = null

    // Global (not per-address) cache so MAC rotation doesn't lose the last known value
    private var lastValidCaseLevel: Int? = null
    private var lastValidCaseCharging: Boolean = false
    private val modelNames = mapOf(
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 (USB-C)",
        0x0220 to "AirPods 1",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 (ANC)",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max (USB-C)"
    )

    val colorNames = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow"
    )

    val connStates = mapOf(
        0x00 to "Disconnected", 0x04 to "Idle", 0x05 to "Music",
        0x06 to "Call", 0x07 to "Ringing", 0x09 to "Hanging Up", 0xFF to "Unknown"
    )

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            cleanupStaleDevices()
            checkLidStateTimeout()
            cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    fun setAirPodsStatusListener(listener: AirPodsStatusListener) {
        airPodsStatusListener = listener
    }

    fun isScanCallbackActive(): Boolean = scanCallbackActive

    fun isScanningDesired(): Boolean = scanningDesired

    /** Milliseconds since last processed AirPods advertisement, or -1 if never. */
    fun msSinceLastAdvertisement(): Long {
        if (lastBroadcastTime <= 0L) return -1L
        return (System.currentTimeMillis() - lastBroadcastTime).coerceAtLeast(0L)
    }

    /**
     * No recent advertisements means delivery is unknown, not that scanning is dead.
     * After the grace period the watchdog may start a bounded recovery probe.
     */
    fun isScanStale(): Boolean {
        if (!scanCallbackActive) return true
        return io.automated.ventures.everypods.utils.LidAutoconnectPolicy.isScanSilent(
            SystemClock.elapsedRealtime(), lastScanStartAtMs, lastBroadcastElapsedMs, STALE_SCAN_RESTART_MS
        )
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun ensureScanning(reason: String = "ensure", force: Boolean = false) {
        scanningDesired = true
        val age = msSinceLastAdvertisement()
        val stale = isScanStale()
        val needsRestart = force || stale
        if (!needsRestart) {
            Log.d(TAG, "ensureScanning skip reason=$reason already_healthy ageMs=$age active=$scanCallbackActive")
            return
        }
        Log.d(
            TAG,
            "ensureScanning restart reason=$reason force=$force active=$scanCallbackActive ageMs=$age stale=$stale"
        )
        if (scanCallbackActive && reason != "bt_state_on") {
            probeScanning(reason)
        } else {
            startScanningInternal(reason)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        scanningDesired = true
        startScanningInternal("start")
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun startScanningInternal(reason: String) {
        cancelPendingScanRetry()
        try {
            io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_start", mapOf("reason" to reason))
            Log.d(TAG, "Starting BLE scanner reason=$reason")

            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (btAdapter == null) {
                Log.d(TAG, "No Bluetooth adapter available")
                scanCallbackActive = false
                return
            }

            // Always clear any prior callback before (re)starting so we never leave
            // a half-dead scanner that reports "started" but delivers nothing.
            stopScanCallbackOnly()

            if (!btAdapter.isEnabled) {
                Log.d(TAG, "Bluetooth is disabled — will retry when adapter is on")
                scanCallbackActive = false
                return
            }

            mBluetoothLeScanner = btAdapter.bluetoothLeScanner
            if (mBluetoothLeScanner == null) {
                Log.w(TAG, "bluetoothLeScanner null (adapter on) — scheduling retry")
                scanCallbackActive = false
                scheduleScanRetry("scanner_null")
                return
            }

            mScanCallback = newScanCallback()
            // Keep the Apple manufacturer/type check in processScanResult rather
            // than the controller filter. Some OEM stacks suppress filtered results
            // for AirPods while their native helper still receives the advertisements.
            mBluetoothLeScanner?.startScan(null, scanSettings(), mScanCallback)
            scanCallbackActive = true
            lastScanStartAtMs = SystemClock.elapsedRealtime()
            consecutiveScanFailures = 0
            Log.d(TAG, "BLE scanner start requested reason=$reason (delivery not yet confirmed)")

            cleanupHandler.removeCallbacks(cleanupRunnable)
            cleanupHandler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
        } catch (t: Throwable) {
            io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_exception", mapOf("reason" to reason, "error" to t.javaClass.simpleName))
            Log.e(TAG, "Error starting BLE scanner reason=$reason", t)
            scanCallbackActive = false
            if (scanningDesired) {
                scheduleScanRetry("start_exception")
            }
        }
    }

    private fun scanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        .setReportDelay(0L)
        .build()

    private fun newScanCallback(): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResults(this, listOf(result))
        override fun onBatchScanResults(results: List<ScanResult>) = handleScanResults(this, results)
        override fun onScanFailed(errorCode: Int) = synchronized(this@BLEManager) {
            if (this === probeCallback) {
                io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_probe_failed", mapOf("errorCode" to errorCode))
                stopProbe()
                if (mScanCallback == null && scanningDesired) scheduleScanRetry("probe_failed_$errorCode")
            } else if (this === mScanCallback) {
                io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_failed", mapOf("errorCode" to errorCode))
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                scanCallbackActive = false
                mScanCallback = null
                consecutiveScanFailures++
                if (scanningDesired && probeCallback == null) scheduleScanRetry("onScanFailed_$errorCode")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun handleScanResults(callback: ScanCallback, results: List<ScanResult>) {
        if (results.isEmpty()) return
        if (callback === probeCallback) {
            val now = SystemClock.elapsedRealtime()
            if (results.none { io.automated.ventures.everypods.utils.LidAutoconnectPolicy.isFreshBleObservation(it.timestampNanos / 1_000_000L, now) }) return
            // A real callback proves the replacement delivered something; until then
            // never throw away the original, which the OS may only have suspended.
            val previous = mScanCallback
            probeTimeout?.let { scanRetryHandler.removeCallbacks(it) }
            probeTimeout = null
            probeCallback = null
            mScanCallback = callback
            scanCallbackActive = true
            if (previous != null) runCatching { mBluetoothLeScanner?.stopScan(previous) }
            io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_probe_promoted")
        } else if (callback !== mScanCallback) return
        processedAddresses.clear()
        results.forEach(::processScanResult)
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun probeScanning(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (probeCallback != null || (lastProbeAtMs > 0 && now - lastProbeAtMs < SCAN_PROBE_INTERVAL_MS)) return
        val scanner = mBluetoothLeScanner ?: return
        val callback = newScanCallback()
        probeCallback = callback
        lastProbeAtMs = now
        io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_probe_start", mapOf("reason" to reason))
        val timeout = Runnable { synchronized(this@BLEManager) {
            if (probeCallback === callback) {
                io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_probe_timeout")
                stopProbe()
                if (mScanCallback == null && scanningDesired) scheduleScanRetry("probe_timeout")
            }
        } }
        probeTimeout = timeout
        try {
            scanner.startScan(null, scanSettings(), callback)
            scanRetryHandler.postDelayed(timeout, SCAN_PROBE_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "BLE scan probe could not start", t)
            stopProbe()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun stopProbe() {
        probeTimeout?.let { scanRetryHandler.removeCallbacks(it) }
        probeTimeout = null
        probeCallback?.let { runCatching { mBluetoothLeScanner?.stopScan(it) } }
        probeCallback = null
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun stopScanCallbackOnly() {
        stopProbe()
        try {
            if (mBluetoothLeScanner != null && mScanCallback != null) {
                mBluetoothLeScanner?.stopScan(mScanCallback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scan callback", t)
        } finally {
            mScanCallback = null
            scanCallbackActive = false
        }
    }

    private fun scheduleScanRetry(reason: String) {
        if (!scanningDesired) return
        cancelPendingScanRetry()
        val attempt = consecutiveScanFailures.coerceAtLeast(1)
        val delayMs = (SCAN_RETRY_BASE_MS * attempt.toLong()).coerceAtMost(SCAN_RETRY_MAX_MS)
        Log.d(TAG, "Scheduling BLE scan retry in ${delayMs}ms reason=$reason failures=$consecutiveScanFailures")
        val runnable = Runnable {
            pendingScanRetry = null
            if (scanningDesired) {
                startScanningInternal("retry_$reason")
            }
        }
        pendingScanRetry = runnable
        scanRetryHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingScanRetry() {
        pendingScanRetry?.let { scanRetryHandler.removeCallbacks(it) }
        pendingScanRetry = null
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun stopScanning() {
        io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("scan_stop")
        scanningDesired = false
        cancelPendingScanRetry()
        try {
            if (mBluetoothLeScanner != null && mScanCallback != null) {
                Log.d(TAG, "Stopping BLE scanner")
            }
            stopScanCallbackOnly()
            cleanupHandler.removeCallbacks(cleanupRunnable)
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping BLE scanner", t)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun getEncryptionKeyFromPreferences(): ByteArray? {
        val keyBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.ENC_KEY.name, null)
        return if (keyBase64 != null) {
            try {
                Base64.decode(keyBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode encryption key", e)
                null
            }
        } else {
            null
        }
    }

    @SuppressLint("GetInstance")
    private fun decryptLastBytes(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            if (data.size < 16) {
                return null
            }

            val block = data.copyOfRange(data.size - 16, data.size)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            cipher.doFinal(block)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            null
        }
    }

    private fun formatBattery(byteVal: Int): Pair<Boolean, Int> {
        val charging = (byteVal and 0x80) != 0
        val level = byteVal and 0x7F
        return Pair(charging, level)
    }

    private fun processScanResult(result: ScanResult) {
        try {
            val observedAt = result.timestampNanos / 1_000_000L
            val receivedAt = SystemClock.elapsedRealtime()
            if (!io.automated.ventures.everypods.utils.LidAutoconnectPolicy.mayAcceptBleScan(
                    observedAt, receivedAt, deviceStatusMap.values.maxOfOrNull { it.observedAtElapsedMs })) {
                io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("ble_scan_stale", mapOf(
                    "ageMs" to (receivedAt - observedAt).coerceAtLeast(0L)))
                return
            }
            val scanRecord = result.scanRecord ?: return
            val address = result.device.address

            if (processedAddresses.contains(address)) {
                return
            }

            val manufacturerData = scanRecord.getManufacturerSpecificData(76) ?: return
            if (manufacturerData.size <= 20) return
            // This payload layout is specific to the AirPods proximity subtype;
            // other Apple company advertisements are scanned but never parsed here.
            if (manufacturerData[0].toInt() != 7 || manufacturerData[1].toInt() != 25) return

            if (!verifiedAddresses.contains(address)) {
                val irk = getIrkFromPreferences()
                if (irk != null) {
                    // IRK available — verify the RPA belongs to our AirPods
                    if (!BluetoothCryptography.verifyRPA(address, irk)) {
                        return
                    }
                    verifiedAddresses.add(address)
                    Log.d(TAG, "RPA verified and added to trusted list: $address")
                } else {
                    // No IRK (limited-mode device) — skip RPA verification and fall
                    // through to the keyless parseProximityMessage() path so battery
                    // data from the unencrypted proximity advertisement is still surfaced.
                    // Use RSSI as a proximity guard: our AirPods are on the user's body
                    // (≈ −45 to −65 dBm); a neighbour's device across the room or through
                    // a wall is typically −75 dBm or weaker.
                    if (result.rssi < RSSI_THRESHOLD_LIMITED_MODE) {
                        Log.d(TAG, "No IRK — skipping weak advertisement (${ result.rssi } dBm < $RSSI_THRESHOLD_LIMITED_MODE): $address")
                        return
                    }
                    Log.d(TAG, "No IRK available, processing advertisement (RSSI ${ result.rssi } dBm): $address")
                }
            }

            processedAddresses.add(address)
            lastBroadcastTime = System.currentTimeMillis()
            lastBroadcastElapsedMs = receivedAt

            val encryptionKey = getEncryptionKeyFromPreferences()
            val decryptedData = if (encryptionKey != null) decryptLastBytes(manufacturerData, encryptionKey) else null
            val parsedStatus = (if (decryptedData != null && decryptedData.size == 16) {
                parseProximityMessageWithDecryption(address, manufacturerData, decryptedData)
            } else {
                parseProximityMessage(address, manufacturerData)
            }).copy(observedAtElapsedMs = observedAt)

            val previousStatus = deviceStatusMap[address]
            if (previousStatus != null && observedAt < previousStatus.observedAtElapsedMs) return
            deviceStatusMap[address] = parsedStatus

            airPodsStatusListener?.let { listener ->
                if (previousStatus == null) {
                    listener.onBroadcastFromNewAddress(parsedStatus)
                    Log.d(TAG, "New AirPods device detected: $address")

                    if (currentGlobalLidState == null || currentGlobalLidState != parsedStatus.lidOpen) {
                        currentGlobalLidState = parsedStatus.lidOpen
                        listener.onLidStateChanged(parsedStatus.lidOpen)
                        Log.d(TAG, "Lid state ${if (parsedStatus.lidOpen) "opened" else "closed"} (detected from new device)")
                    }
                } else {
                    if (parsedStatus.copy(lastSeen = 0L, observedAtElapsedMs = 0L) != previousStatus.copy(lastSeen = 0L, observedAtElapsedMs = 0L)) {
                        listener.onDeviceStatusChanged(parsedStatus, previousStatus)
                    }

                    if (parsedStatus.lidOpen != previousStatus.lidOpen) {
                        val previousGlobalState = currentGlobalLidState
                        currentGlobalLidState = parsedStatus.lidOpen

                        if (previousGlobalState != parsedStatus.lidOpen) {
                            listener.onLidStateChanged(parsedStatus.lidOpen)
                            Log.d(TAG, "Lid state changed from $previousGlobalState to ${parsedStatus.lidOpen}")
                        }
                    }

                    if (parsedStatus.isLeftInEar != previousStatus.isLeftInEar ||
                        parsedStatus.isRightInEar != previousStatus.isRightInEar) {
                        listener.onEarStateChanged(
                            parsedStatus,
                            parsedStatus.isLeftInEar,
                            parsedStatus.isRightInEar
                        )
                        Log.d(TAG, "Ear state changed - Left: ${parsedStatus.isLeftInEar}, Right: ${parsedStatus.isRightInEar}")
                    }

                    if (parsedStatus.leftBattery != previousStatus.leftBattery ||
                        parsedStatus.rightBattery != previousStatus.rightBattery ||
                        parsedStatus.caseBattery != previousStatus.caseBattery) {
                        listener.onBatteryChanged(parsedStatus)
                        Log.d(TAG, "Battery changed - Left: ${parsedStatus.leftBattery}, Right: ${parsedStatus.rightBattery}, Case: ${parsedStatus.caseBattery}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error processing scan result", t)
        }
    }

    private fun parseProximityMessageWithDecryption(address: String, data: ByteArray, decrypted: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
//        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftByteIndex = if (isFlipped) 2 else 1
        val rightByteIndex = if (isFlipped) 1 else 2

        val (isLeftCharging, leftBattery) = formatBattery(decrypted[leftByteIndex].toInt() and 0xFF)
        val (isRightCharging, rightBattery) = formatBattery(decrypted[rightByteIndex].toInt() and 0xFF)

        val rawCaseBatteryByte = decrypted[3].toInt() and 0xFF
        val (isCaseCharging, rawCaseBattery) = formatBattery(rawCaseBatteryByte)

        // 0xFF means case battery is unavailable (buds not in case, or lid just opened).
        // Use the global last-known value so MAC rotation doesn't clear the cache.
        val caseBattery = if (rawCaseBatteryByte == 0xFF || (isCaseCharging && rawCaseBattery == 127)) {
            lastValidCaseLevel   // may still be null on first connection
        } else {
            lastValidCaseLevel = rawCaseBattery
            lastValidCaseCharging = isCaseCharging
            rawCaseBattery
        }

        val lidOpen = ((lid shr 3) and 0x01) == 0

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    @Synchronized private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleCutoff = now - STALE_DEVICE_TIMEOUT_MS
        val hadDevices = deviceStatusMap.isNotEmpty()

        val staleDevices = deviceStatusMap.filter { it.value.lastSeen < staleCutoff }

        for (device in staleDevices) {
            deviceStatusMap.remove(device.key)
            Log.d(TAG, "Removed stale device from tracking: ${device.key}")
        }

        if (hadDevices && deviceStatusMap.isEmpty()) {
            airPodsStatusListener?.onDeviceDisappeared()
        }
    }

    private fun checkLidStateTimeout() {
        val currentTime = SystemClock.elapsedRealtime()
        if (lastBroadcastElapsedMs > 0 && currentTime - lastBroadcastElapsedMs > LID_CLOSE_TIMEOUT_MS && currentGlobalLidState == true) {
            Log.d(TAG, "No broadcasts for ${LID_CLOSE_TIMEOUT_MS}ms, lid state unknown")
            currentGlobalLidState = null
            airPodsStatusListener?.onLidStateUnknown()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun getIrkFromPreferences(): ByteArray? {
        val irkBase64 = sharedPreferences.getString(AACPManager.Companion.ProximityKeyType.IRK.name, null)
        return if (irkBase64 != null) {
            try {
                Base64.decode(irkBase64)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode IRK", e)
                null
            }
        } else {
            null
        }
    }

    private fun parseProximityMessage(address: String, data: ByteArray): AirPodsStatus {
        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = modelNames[modelId] ?: "Unknown ($modelId)"

        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = colorNames[data[9].toInt()] ?: "Unknown"
        val conn = connStates[data[10].toInt()] ?: "Unknown (${data[10].toInt()})"

        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase

        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        val isFlipped = !primaryLeft

        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F

        val caseBattery = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        val lidOpen = ((lid shr 3) and 0x01) == 0

        fun decodeBattery(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }

        return AirPodsStatus(
            address = address,
            lastSeen = System.currentTimeMillis(),
            paired = paired,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBattery),
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            lidOpen = lidOpen,
            color = color,
            connectionState = conn
        )
    }

    companion object {
        private const val TAG = "AirPodsBLE"
        private const val CLEANUP_INTERVAL_MS = 10000L
        private const val STALE_DEVICE_TIMEOUT_MS = 15000L
        private const val LID_CLOSE_TIMEOUT_MS = 2500L
        /** Silence is uncertain; probe without discarding the existing registration. */
        const val STALE_SCAN_RESTART_MS = 45_000L
        private const val SCAN_PROBE_INTERVAL_MS = 45_000L
        private const val SCAN_PROBE_TIMEOUT_MS = 20_000L
        private const val SCAN_RETRY_BASE_MS = 2_000L
        private const val SCAN_RETRY_MAX_MS = 30_000L
        // Minimum RSSI (dBm) accepted when no IRK is available (limited-mode devices).
        // Rejects distant/neighbour AirPods while accepting the user's own device which
        // is worn on the body and typically reads −45 to −65 dBm.
        private const val RSSI_THRESHOLD_LIMITED_MODE = -75
    }
}
