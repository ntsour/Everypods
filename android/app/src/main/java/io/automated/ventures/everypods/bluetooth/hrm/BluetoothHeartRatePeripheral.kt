/* Copyright (C) 2026 EveryPods contributors. GPL-3.0.
 * Implements the Bluetooth SIG Heart Rate Service (180D) over Android GATT server APIs. */
package io.automated.ventures.everypods.bluetooth.hrm

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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BluetoothHrPeripheralError { UNSUPPORTED, PERMISSION, GATT_SERVER, SERVICE, ADVERTISING }
data class BluetoothHrPeripheralState(
    val active: Boolean = false,
    val advertising: Boolean = false,
    val connected: Int = 0,
    val subscribed: Int = 0,
    val sent: Long = 0,
    val error: BluetoothHrPeripheralError? = null
)

/** A standard 180D HR sensor peripheral, explicitly started by the user. */
class BluetoothHeartRatePeripheral(
    context: Context,
    private val changed: (BluetoothHrPeripheralState) -> Unit,
    private val failed: (BluetoothHrPeripheralError) -> Unit
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var stopped = false
    private val subscriptions = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val pendingNotifications = ConcurrentHashMap<BluetoothDevice, Long>()
    private val connected = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    @Volatile private var state = BluetoothHrPeripheralState()
    private lateinit var measurement: BluetoothGattCharacteristic
    private lateinit var bodyLocation: BluetoothGattCharacteristic
    private lateinit var deviceInformationService: BluetoothGattService

    @Synchronized private fun update(change: (BluetoothHrPeripheralState) -> BluetoothHrPeripheralState) {
        if (stopped) return
        state = change(state)
        changed(state)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val hasConnect = app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val hasAdvertise = app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        if (!hasConnect || !hasAdvertise) return fail(BluetoothHrPeripheralError.PERMISSION)
        if (!adapter.isEnabled || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || adapter.bluetoothLeAdvertiser == null)
            return fail(BluetoothHrPeripheralError.UNSUPPORTED)

        measurement = BluetoothGattCharacteristic(
            HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        measurement.addDescriptor(BluetoothGattDescriptor(
            CLIENT_CONFIGURATION,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        bodyLocation = BluetoothGattCharacteristic(
            BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ) // Read callback reports Other: the sensor is in the AirPods.
        val heartRateService = BluetoothGattService(HEART_RATE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(measurement); addCharacteristic(bodyLocation) }
        deviceInformationService = BluetoothGattService(DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply {
                addCharacteristic(readOnlyString(MANUFACTURER_NAME))
                addCharacteristic(readOnlyString(MODEL_NUMBER))
            }

        server = manager.openGattServer(app, gattCallback)
        if (server == null) return fail(BluetoothHrPeripheralError.GATT_SERVER)
        advertiser = adapter.bluetoothLeAdvertiser
        update { it.copy(active = true) }
        if (!server!!.addService(heartRateService))
            fail(BluetoothHrPeripheralError.SERVICE)
    }

    private fun readOnlyString(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    )

    @SuppressLint("MissingPermission")
    private fun beginAdvertising() {
        if (stopped) return
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        try {
            advertiser?.startAdvertising(settings, data, AdvertiseData.Builder().setIncludeDeviceName(true).build(), advertiseCallback)
                ?: fail(BluetoothHrPeripheralError.UNSUPPORTED)
        } catch (e: SecurityException) {
            fail(BluetoothHrPeripheralError.PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "BLE advertising failed: ${e.javaClass.simpleName}")
            fail(BluetoothHrPeripheralError.ADVERTISING)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising standard HR service 180D")
            update { it.copy(active = true, advertising = true, error = null) }
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertiser rejected start code=$errorCode")
            fail(BluetoothHrPeripheralError.ADVERTISING)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (stopped) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail(BluetoothHrPeripheralError.SERVICE)
            when (service.uuid) {
                HEART_RATE_SERVICE -> if (server?.addService(deviceInformationService) != true)
                    fail(BluetoothHrPeripheralError.SERVICE)
                DEVICE_INFO_SERVICE -> beginAdvertising()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (stopped) return
            // Android reports shared adapter links too. Count a receiver only after
            // it accesses one of our characteristics or subscribes to our service.
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                connected.remove(device)
                subscriptions.remove(device)
                pendingNotifications.remove(device)
            }
            update { it.copy(connected = connected.size, subscribed = subscriptions.size) }
            Log.i(TAG, "BLE client state=$newState status=$status clients=${connected.size}")
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (descriptor.uuid == CLIENT_CONFIGURATION && offset == 0)
                if (subscriptions.contains(device)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            else null
            server?.sendResponse(device, requestId,
                if (value == null) BluetoothGatt.GATT_INVALID_OFFSET else BluetoothGatt.GATT_SUCCESS,
                offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (stopped) return
            if (preparedWrite || descriptor.uuid != CLIENT_CONFIGURATION || descriptor.characteristic.uuid != HEART_RATE_MEASUREMENT || offset != 0 || value.size != 2) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            connected.add(device)
            when {
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> subscriptions.add(device)
                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> subscriptions.remove(device)
                else -> {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    return
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            update { it.copy(connected = connected.size, subscribed = subscriptions.size) }
            Log.i(TAG, "BLE HR notifications subscribed=${subscriptions.contains(device)}")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            pendingNotifications.remove(device)
            if (status == BluetoothGatt.GATT_SUCCESS) update { it.copy(sent = it.sent + 1) }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = when (characteristic.uuid) {
                BODY_SENSOR_LOCATION -> byteArrayOf(0)
                MANUFACTURER_NAME -> "EveryPods".toByteArray(Charsets.UTF_8)
                MODEL_NUMBER -> "AirPods heart-rate bridge".toByteArray(Charsets.UTF_8)
                else -> null
            }
            if (stopped) return
            if (value != null) {
                connected.add(device)
                update { it.copy(connected = connected.size) }
            }
            val status = when {
                value == null -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset < 0 || offset > value.size -> BluetoothGatt.GATT_INVALID_OFFSET
                else -> BluetoothGatt.GATT_SUCCESS
            }
            val response = if (status == BluetoothGatt.GATT_SUCCESS) value!!.copyOfRange(offset, value.size) else null
            server?.sendResponse(device, requestId, status, offset, response)
        }
    }

    @SuppressLint("MissingPermission")
    fun publish(bpm: Int) {
        if (stopped || bpm !in 1..255) return
        val payload = byteArrayOf(0, bpm.toByte()) // uint8 BPM, no optional fields.
        subscriptions.toList().forEach { device ->
            val now = android.os.SystemClock.elapsedRealtime()
            // One in-flight notification per receiver; never build a stale sample queue.
            val previous = pendingNotifications.putIfAbsent(device, now)
            if (previous != null) {
                if (now - previous < 5_000) return@forEach
                pendingNotifications[device] = now
            }
            val status = runCatching {
                server?.notifyCharacteristicChanged(device, measurement, false, payload)
                    ?: android.bluetooth.BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND
            }.getOrElse { android.bluetooth.BluetoothStatusCodes.ERROR_UNKNOWN }
            if (status != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                pendingNotifications.remove(device)
                Log.w(TAG, "HR notification rejected status=$status")
            }
        }
    }

    private fun fail(error: BluetoothHrPeripheralError) {
        if (stopped) return
        state = state.copy(active = false, advertising = false, error = error)
        changed(state)
        stop()
        failed(error)
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { server?.clearServices(); server?.close() }
        server = null
        pendingNotifications.clear()
        subscriptions.clear()
        connected.clear()
        state = state.copy(active = false, advertising = false, connected = 0, subscribed = 0)
        changed(state)
        Log.i(TAG, "BLE heart-rate peripheral stopped; sent=${state.sent}")
    }

    companion object {
        private const val TAG = "EveryPodsBLEHR"
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
