/*
    AndroPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 AndroPods contributors

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

package io.nikos.andropods.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.edit
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class ProximityScanner(private val context: Context) {

    enum class DeviceKind(val label: String) {
        AIRPODS("AirPods"),
        APPLE_FIND_MY("Find My"),
        APPLE_NEARBY("Apple BLE"),
        NAMED_AIRPODS("Named AirPods")
    }

    data class ProximityDevice(
        val id: String,
        val address: String,
        val name: String?,
        val kind: DeviceKind,
        val confidence: Int,
        val rssi: Int,
        val smoothedRssi: Float,
        val score: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val seenCount: Int,
        val model: String?,
        val hints: List<String>,
        val appleManufacturerHex: String?,
        val appleManufacturerPrefix: String?,
        val serviceUuids: List<String>,
        val txPower: Int?,
        val ownerScore: Int,
        val ownerLabel: String
    ) {
        val displayName: String
            get() = name ?: model ?: kind.label

        val proximityLabel: String
            get() = when {
                score >= 85 -> "Very close"
                score >= 68 -> "Near"
                score >= 48 -> "In this room"
                score >= 28 -> "Weak"
                else -> "Faint"
            }
    }

    enum class FeedbackMode { SOUND, VIBRATION }

    data class UiState(
        val isScanning: Boolean = false,
        val devices: List<ProximityDevice> = emptyList(),
        val focusedDevice: ProximityDevice? = null,
        val focusedId: String? = null,
        val hasOwnerFingerprint: Boolean = false,
        val isCalibrating: Boolean = false,
        val calibrationProgress: Float = 0f,
        val calibrationMessage: String? = null,
        val error: String? = null,
        val lastUpdated: Long = 0L,
        val feedbackMode: FeedbackMode = FeedbackMode.VIBRATION
    )

    private data class Classification(
        val kind: DeviceKind,
        val confidence: Int,
        val model: String?,
        val hints: List<String>
    )

    private data class MutableDevice(
        val id: String,
        val address: String,
        var name: String?,
        var kind: DeviceKind,
        var confidence: Int,
        var rssi: Int,
        var smoothedRssi: Float,
        val firstSeen: Long,
        var lastSeen: Long,
        var seenCount: Int,
        var model: String?,
        var hints: List<String>,
        var appleManufacturerHex: String?,
        var appleManufacturerPrefix: String?,
        var serviceUuids: List<String>,
        var txPower: Int?
    ) {
        fun snapshot(ownerFingerprint: OwnerFingerprint?): ProximityDevice {
            val ownerScore = ownerFingerprint?.matchScore(
                kind = kind,
                name = name,
                model = model,
                appleManufacturerPrefix = appleManufacturerPrefix,
                serviceUuids = serviceUuids
            ) ?: 0
            return ProximityDevice(
                id = id,
                address = address,
                name = name,
                kind = kind,
                confidence = confidence,
                rssi = rssi,
                smoothedRssi = smoothedRssi,
                score = rssiToScore(smoothedRssi),
                firstSeen = firstSeen,
                lastSeen = lastSeen,
                seenCount = seenCount,
                model = model,
                hints = hints,
                appleManufacturerHex = appleManufacturerHex,
                appleManufacturerPrefix = appleManufacturerPrefix,
                serviceUuids = serviceUuids,
                txPower = txPower,
                ownerScore = ownerScore,
                ownerLabel = ownerLabel(ownerScore)
            )
        }
    }

    private data class OwnerFingerprint(
        val kind: DeviceKind,
        val name: String?,
        val model: String?,
        val appleManufacturerPrefixes: Set<String>,
        val serviceUuids: Set<String>
    ) {
        fun matchScore(
            kind: DeviceKind,
            name: String?,
            model: String?,
            appleManufacturerPrefix: String?,
            serviceUuids: List<String>
        ): Int {
            var score = 0
            if (this.kind == kind) score += 28
            if (this.model != null && this.model == model) score += 24
            if (this.name != null && this.name == name) score += 12
            if (appleManufacturerPrefix != null && appleManufacturerPrefix in appleManufacturerPrefixes) {
                score += 34
            }
            if (serviceUuids.any { it in this.serviceUuids }) score += 14
            return score.coerceIn(0, 100)
        }
    }

    private data class CalibrationSample(
        val id: String,
        var kind: DeviceKind,
        var name: String?,
        var model: String?,
        var maxScore: Int,
        var maxConfidence: Int,
        var seenCount: Int,
        val appleManufacturerPrefixes: MutableSet<String>,
        val serviceUuids: MutableSet<String>
    ) {
        fun rankingScore(): Int {
            val airPodsBonus = if (kind == DeviceKind.AIRPODS || kind == DeviceKind.NAMED_AIRPODS) 45 else 0
            return maxScore + maxConfidence + airPodsBonus + (seenCount * 2).coerceAtMost(80)
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val devices = linkedMapOf<String, MutableDevice>()
    private val sharedPreferences = context.getSharedPreferences("proximity_finder", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var focusedId: String? = null
    private var ownerFingerprint: OwnerFingerprint? = loadOwnerFingerprint()
    private var calibrationEndsAt: Long = 0L
    private val calibrationSamples = linkedMapOf<String, CalibrationSample>()

    // Feedback mode persisted in prefs
    private var feedbackMode: FeedbackMode = loadFeedbackMode()

    // Pre-built 80ms 1kHz beep buffer for AudioTrack
    private val beepBuffer: ShortArray by lazy { buildBeepBuffer(durationMs = 80) }

    // Vibrator for haptic feedback
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private var lastPulseAt: Long = 0L

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            cleanup()
            finishCalibrationIfNeeded()
            publish()
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value.isScanning) return

        try {
            val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter

            if (adapter == null) {
                publish(error = "Bluetooth is not available on this device.")
                return
            }

            if (!adapter.isEnabled) {
                publish(error = "Bluetooth is turned off.")
                return
            }

            scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                publish(error = "Bluetooth LE scanning is not available.")
                return
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    process(result)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    results.forEach(::process)
                }

                override fun onScanFailed(errorCode: Int) {
                    publish(error = "BLE scan failed: $errorCode", isScanning = false)
                }
            }

            scanner?.startScan(null, scanSettings, callback)
            handler.removeCallbacks(cleanupRunnable)
            handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
            publish(isScanning = true, error = null)
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Missing Bluetooth scan permission", securityException)
            publish(error = "Bluetooth scan permission is missing.", isScanning = false)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unable to start proximity scanner", throwable)
            publish(error = "Unable to start BLE scan.", isScanning = false)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            callback?.let { scanner?.stopScan(it) }
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unable to stop proximity scanner", throwable)
        } finally {
            callback = null
            scanner = null
            handler.removeCallbacks(cleanupRunnable)
            publish(isScanning = false)
        }
    }

    fun setFeedbackMode(mode: FeedbackMode) {
        feedbackMode = mode
        sharedPreferences.edit { putString(PREF_FEEDBACK_MODE, mode.name) }
        publish()
    }

    fun clear() {
        devices.clear()
        focusedId = null
        publish()
    }

    fun focus(deviceId: String?) {
        focusedId = deviceId
        publish()
    }

    fun focusStrongest() {
        focusedId = devices.values
            .map { it.snapshot(ownerFingerprint) }
            .maxWithOrNull(compareBy<ProximityDevice> { it.ownerScore }.thenBy { it.score }.thenBy { it.confidence })
            ?.id
        publish()
    }

    fun startCalibration() {
        if (!_state.value.isScanning) start()
        calibrationSamples.clear()
        calibrationEndsAt = System.currentTimeMillis() + CALIBRATION_DURATION_MS
        publish(error = null)
    }

    fun cancelCalibration() {
        calibrationEndsAt = 0L
        calibrationSamples.clear()
        publish()
    }

    fun clearOwnerFingerprint() {
        ownerFingerprint = null
        sharedPreferences.edit { clear() }
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun process(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val appleData = scanRecord.getManufacturerSpecificData(APPLE_COMPANY_ID)
        val serviceUuids = serviceUuids(scanRecord.serviceUuids, scanRecord.serviceData?.keys)
        val name = scanRecord.deviceName ?: runCatching { result.device.name }.getOrNull()
        val classification = classify(name, appleData, serviceUuids) ?: return

        val now = System.currentTimeMillis()
        val address = result.device.address ?: "unknown"
        val manufacturerHex = appleData?.toHexString()
        val manufacturerPrefix = appleData?.manufacturerPrefix()
        val id = stableId(address, classification.kind, appleData)
        val txPower = result.txPower.takeUnless { it == Int.MIN_VALUE }

        val existing = devices[id]
        if (existing == null) {
            devices[id] = MutableDevice(
                id = id,
                address = address,
                name = name,
                kind = classification.kind,
                confidence = classification.confidence,
                rssi = result.rssi,
                smoothedRssi = result.rssi.toFloat(),
                firstSeen = now,
                lastSeen = now,
                seenCount = 1,
                model = classification.model,
                hints = classification.hints,
                appleManufacturerHex = manufacturerHex,
                appleManufacturerPrefix = manufacturerPrefix,
                serviceUuids = serviceUuids,
                txPower = txPower
            )
        } else {
            existing.name = name ?: existing.name
            existing.kind = classification.kind
            existing.confidence = classification.confidence
            existing.rssi = result.rssi
            existing.smoothedRssi = (existing.smoothedRssi * RSSI_SMOOTHING) +
                (result.rssi * (1f - RSSI_SMOOTHING))
            existing.lastSeen = now
            existing.seenCount += 1
            existing.model = classification.model ?: existing.model
            existing.hints = classification.hints
            existing.appleManufacturerHex = manufacturerHex ?: existing.appleManufacturerHex
            existing.appleManufacturerPrefix = manufacturerPrefix ?: existing.appleManufacturerPrefix
            existing.serviceUuids = serviceUuids.ifEmpty { existing.serviceUuids }
            existing.txPower = txPower ?: existing.txPower
        }

        devices[id]?.snapshot(ownerFingerprint)?.let(::recordCalibrationSample)
        publish(error = null)
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        val removedFocused = devices[focusedId]?.lastSeen?.let { it < cutoff } == true
        devices.entries.removeAll { it.value.lastSeen < cutoff }
        if (removedFocused) focusedId = null
    }

    private fun publish(
        error: String? = _state.value.error,
        isScanning: Boolean = _state.value.isScanning
    ) {
        finishCalibrationIfNeeded()
        val now = System.currentTimeMillis()
        val isCalibrating = calibrationEndsAt > now
        val calibrationProgress = if (isCalibrating) {
            1f - ((calibrationEndsAt - now).toFloat() / CALIBRATION_DURATION_MS.toFloat())
        } else {
            0f
        }

        val allSnapshots = devices.values
            .map { it.snapshot(ownerFingerprint) }
            .sortedWith(
                compareByDescending<ProximityDevice> { it.id == focusedId }
                    .thenByDescending { it.ownerScore }
                    .thenByDescending { it.score }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.lastSeen }
            )

        val bestOwnerMatch = allSnapshots
            .filter { it.ownerScore >= 45 }
            .maxWithOrNull(compareBy<ProximityDevice> { it.ownerScore }.thenBy { it.score }.thenBy { it.confidence })

        val focused = focusedId?.let { id -> allSnapshots.firstOrNull { it.id == id } }
            ?: bestOwnerMatch
            ?: allSnapshots.maxWithOrNull(compareBy<ProximityDevice> { it.score }.thenBy { it.confidence })

        // During calibration: show all. After calibration with saved fingerprint: show only matches.
        // Without a fingerprint: show all Apple devices so the user can calibrate.
        val visibleDevices = when {
            isCalibrating -> allSnapshots
            ownerFingerprint != null -> allSnapshots.filter { it.ownerScore >= 45 }
            else -> allSnapshots
        }

        // Fire proximity pulse based on focused device score
        val focusedScore = focused?.score ?: 0
        if (isScanning && focused != null && focusedScore >= 20) {
            val interval = pulseInterval(focusedScore)
            if (now - lastPulseAt >= interval) {
                lastPulseAt = now
                when (feedbackMode) {
                    FeedbackMode.SOUND -> playSpeakerBeep()
                    FeedbackMode.VIBRATION -> playVibration(focusedScore)
                }
            }
        }

        _state.value = UiState(
            isScanning = isScanning,
            devices = visibleDevices,
            focusedDevice = focused,
            focusedId = focusedId,
            hasOwnerFingerprint = ownerFingerprint != null,
            isCalibrating = isCalibrating,
            calibrationProgress = calibrationProgress,
            calibrationMessage = calibrationMessage(isCalibrating),
            error = error,
            lastUpdated = now,
            feedbackMode = feedbackMode
        )
    }

    private fun pulseInterval(score: Int): Long = when {
        score >= 85 -> 300L
        score >= 70 -> 500L
        score >= 55 -> 750L
        score >= 40 -> 1100L
        else -> 1500L
    }

    /** Play a short beep exclusively through the built-in earpiece/speaker. */
    private fun playSpeakerBeep() {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Find the built-in speaker device explicitly
            val speakerDevice = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            val sampleRate = 44_100
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, beepBuffer.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            // Pin to built-in speaker — bypasses Bluetooth routing entirely
            if (speakerDevice != null) track.setPreferredDevice(speakerDevice)
            track.write(beepBuffer, 0, beepBuffer.size)
            track.play()
            // Release after playback completes (~80ms)
            handler.postDelayed({
                runCatching { track.stop(); track.release() }
            }, 200)
        }
    }

    /** Vibrate with a pattern that encodes proximity (shorter gap = closer). */
    private fun playVibration(score: Int) {
        runCatching {
            val durationMs = when {
                score >= 85 -> 80L
                score >= 65 -> 60L
                else -> 40L
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        }
    }

    private fun loadFeedbackMode(): FeedbackMode {
        val name = sharedPreferences.getString(PREF_FEEDBACK_MODE, null) ?: return FeedbackMode.VIBRATION
        return runCatching { FeedbackMode.valueOf(name) }.getOrDefault(FeedbackMode.VIBRATION)
    }

    /** 80ms 1kHz sine wave at 90% amplitude. */
    private fun buildBeepBuffer(durationMs: Int): ShortArray {
        val sampleRate = 44_100
        val samples = (sampleRate * durationMs / 1000)
        val buf = ShortArray(samples)
        val freq = 1000.0
        for (i in buf.indices) {
            buf[i] = (sin(2.0 * PI * freq * i / sampleRate) * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        return buf
    }

    private fun recordCalibrationSample(device: ProximityDevice) {
        if (calibrationEndsAt <= System.currentTimeMillis()) return

        val sample = calibrationSamples.getOrPut(device.id) {
            CalibrationSample(
                id = device.id,
                kind = device.kind,
                name = device.name,
                model = device.model,
                maxScore = device.score,
                maxConfidence = device.confidence,
                seenCount = 0,
                appleManufacturerPrefixes = mutableSetOf(),
                serviceUuids = mutableSetOf()
            )
        }

        sample.kind = device.kind
        sample.name = device.name ?: sample.name
        sample.model = device.model ?: sample.model
        sample.maxScore = maxOf(sample.maxScore, device.score)
        sample.maxConfidence = maxOf(sample.maxConfidence, device.confidence)
        sample.seenCount += 1
        device.appleManufacturerPrefix?.let { sample.appleManufacturerPrefixes.add(it) }
        device.serviceUuids.forEach { sample.serviceUuids.add(it) }
    }

    private fun finishCalibrationIfNeeded() {
        if (calibrationEndsAt == 0L || calibrationEndsAt > System.currentTimeMillis()) return

        val bestSample = calibrationSamples.values.maxByOrNull { it.rankingScore() }
        if (bestSample != null) {
            ownerFingerprint = OwnerFingerprint(
                kind = bestSample.kind,
                name = bestSample.name,
                model = bestSample.model,
                appleManufacturerPrefixes = bestSample.appleManufacturerPrefixes,
                serviceUuids = bestSample.serviceUuids
            )
            saveOwnerFingerprint(ownerFingerprint!!)
            focusedId = bestSample.id
        }

        calibrationEndsAt = 0L
        calibrationSamples.clear()
    }

    private fun calibrationMessage(isCalibrating: Boolean): String? {
        if (isCalibrating) {
            val secondsLeft = ((calibrationEndsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
            return "Keep the open case beside the phone for ${secondsLeft}s."
        }

        return if (ownerFingerprint != null) {
            "Your AirPods fingerprint is saved. Matching devices are sorted first."
        } else {
            null
        }
    }

    private fun classify(
        name: String?,
        appleData: ByteArray?,
        serviceUuids: List<String>
    ): Classification? {
        val lowerName = name?.lowercase()
        val nameLooksLikeAirPods = lowerName?.contains("airpods") == true
        val hasFindMyService = serviceUuids.any { uuid ->
            uuid.contains("fd44", ignoreCase = true) || uuid.contains("feaa", ignoreCase = true)
        }

        if (appleData != null) {
            val first = appleData.getOrNull(0)?.toInt()?.and(0xFF)
            val second = appleData.getOrNull(1)?.toInt()?.and(0xFF)

            if (first == 0x07 && second == 0x19) {
                val model = parseAirPodsModel(appleData)
                val hints = buildList {
                    add("AirPods proximity")
                    if (model != null) add(model)
                    if (nameLooksLikeAirPods) add("Bluetooth name")
                }
                return Classification(
                    kind = DeviceKind.AIRPODS,
                    confidence = 100,
                    model = model,
                    hints = hints
                )
            }

            if (first == 0x12) {
                return Classification(
                    kind = DeviceKind.APPLE_FIND_MY,
                    confidence = if (second == 0x19) 92 else 78,
                    model = if (nameLooksLikeAirPods) "AirPods" else null,
                    hints = listOf("Apple Find My beacon")
                )
            }

            if (nameLooksLikeAirPods) {
                return Classification(
                    kind = DeviceKind.NAMED_AIRPODS,
                    confidence = 88,
                    model = "AirPods",
                    hints = listOf("Bluetooth name", "Apple advertisement")
                )
            }

            return Classification(
                kind = DeviceKind.APPLE_NEARBY,
                confidence = 42,
                model = null,
                hints = listOf("Apple advertisement")
            )
        }

        if (hasFindMyService) {
            return Classification(
                kind = DeviceKind.APPLE_FIND_MY,
                confidence = 72,
                model = if (nameLooksLikeAirPods) "AirPods" else null,
                hints = listOf("Find My service UUID")
            )
        }

        if (nameLooksLikeAirPods) {
            return Classification(
                kind = DeviceKind.NAMED_AIRPODS,
                confidence = 65,
                model = "AirPods",
                hints = listOf("Bluetooth name")
            )
        }

        return null
    }

    private fun parseAirPodsModel(data: ByteArray): String? {
        if (data.size < 5) return null
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        return modelNames[modelId] ?: "AirPods model 0x${modelId.toString(16).uppercase()}"
    }

    private fun stableId(address: String, kind: DeviceKind, appleData: ByteArray?): String {
        val fingerprint = appleData
            ?.take(6)
            ?.joinToString("") { "%02X".format(it) }
            ?: "no-apple-data"
        return "$address-${kind.name}-$fingerprint"
    }

    private fun serviceUuids(
        advertisedUuids: List<ParcelUuid>?,
        serviceDataUuids: Set<ParcelUuid>?
    ): List<String> {
        return ((advertisedUuids ?: emptyList()) + (serviceDataUuids ?: emptySet()))
            .map { it.uuid.toString() }
            .distinct()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { "%02X".format(it) }
    }

    private fun ByteArray.manufacturerPrefix(): String {
        return take(8).joinToString("") { "%02X".format(it) }
    }

    private fun loadOwnerFingerprint(): OwnerFingerprint? {
        val kind = sharedPreferences.getString(PREF_KIND, null)?.let {
            runCatching { DeviceKind.valueOf(it) }.getOrNull()
        } ?: return null

        return OwnerFingerprint(
            kind = kind,
            name = sharedPreferences.getString(PREF_NAME, null),
            model = sharedPreferences.getString(PREF_MODEL, null),
            appleManufacturerPrefixes = sharedPreferences.getStringSet(PREF_PREFIXES, emptySet()) ?: emptySet(),
            serviceUuids = sharedPreferences.getStringSet(PREF_SERVICE_UUIDS, emptySet()) ?: emptySet()
        )
    }

    private fun saveOwnerFingerprint(fingerprint: OwnerFingerprint) {
        sharedPreferences.edit {
            putString(PREF_KIND, fingerprint.kind.name)
            putString(PREF_NAME, fingerprint.name)
            putString(PREF_MODEL, fingerprint.model)
            putStringSet(PREF_PREFIXES, fingerprint.appleManufacturerPrefixes)
            putStringSet(PREF_SERVICE_UUIDS, fingerprint.serviceUuids)
        }
    }

    companion object {
        private const val TAG = "AirPodsProximity"
        private const val APPLE_COMPANY_ID = 0x004C
        private const val CLEANUP_INTERVAL_MS = 1000L
        private const val STALE_AFTER_MS = 12_000L
        private const val RSSI_SMOOTHING = 0.72f
        private const val CALIBRATION_DURATION_MS = 20_000L
        private const val PREF_KIND = "owner.kind"
        private const val PREF_NAME = "owner.name"
        private const val PREF_MODEL = "owner.model"
        private const val PREF_PREFIXES = "owner.prefixes"
        private const val PREF_SERVICE_UUIDS = "owner.service_uuids"
        private const val PREF_FEEDBACK_MODE = "feedback.mode"

        private val modelNames = mapOf(
            0x0E20 to "AirPods Pro",
            0x1420 to "AirPods Pro 2",
            0x2420 to "AirPods Pro 2 (USB-C)",
            0x3F20 to "AirPods Pro 3",
            0x0220 to "AirPods 1",
            0x0F20 to "AirPods 2",
            0x1320 to "AirPods 3",
            0x1920 to "AirPods 4",
            0x1B20 to "AirPods 4 (ANC)",
            0x0A20 to "AirPods Max",
            0x1F20 to "AirPods Max (USB-C)"
        )

        private fun rssiToScore(rssi: Float): Int {
            val clamped = rssi.coerceIn(-100f, -45f)
            return (((clamped + 100f) / 55f) * 100f).roundToInt().coerceIn(0, 100)
        }

        private fun ownerLabel(score: Int): String {
            return when {
                score >= 75 -> "Likely yours"
                score >= 45 -> "Maybe yours"
                else -> "Other nearby"
            }
        }
    }
}
