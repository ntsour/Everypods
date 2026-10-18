package io.automated.ventures.everypods.bluetooth.hrm

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BluetoothHeartRatePeripheralTest {
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<BluetoothManager>(relaxed = true)
    private val server = mockk<BluetoothGattServer>(relaxed = true)
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private val states = mutableListOf<BluetoothHrPeripheralState>()
    private val failures = mutableListOf<BluetoothHrPeripheralError>()

    private fun peripheral(): Pair<BluetoothHeartRatePeripheral, BluetoothGattServerCallback> {
        every { context.applicationContext } returns context
        every { context.getSystemService(BluetoothManager::class.java) } returns manager
        val peripheral = BluetoothHeartRatePeripheral(context, { states += it }, { failures += it })
        BluetoothHeartRatePeripheral::class.java.getDeclaredField("server").apply {
            isAccessible = true
            set(peripheral, server)
        }
        val callback = BluetoothHeartRatePeripheral::class.java.getDeclaredField("gattCallback").run {
            isAccessible = true
            get(peripheral) as BluetoothGattServerCallback
        }
        return peripheral to callback
    }

    private fun location() = BluetoothGattCharacteristic(
        BluetoothHeartRatePeripheral.BODY_SENSOR_LOCATION,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    )

    @Test fun `read responds with body sensor location`() {
        val (_, callback) = peripheral()
        callback.onCharacteristicReadRequest(device, 7, 0, location())
        verify { server.sendResponse(device, 7, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0)) }
        assertEquals(emptyList<BluetoothHrPeripheralError>(), failures)
    }

    @Test fun `revoked permission stops sharing without escaping callback and ignores late reads`() {
        val (_, callback) = peripheral()
        every { server.sendResponse(any(), any(), any(), any(), any()) } throws SecurityException("revoked")
        callback.onCharacteristicReadRequest(device, 7, 0, location())
        callback.onCharacteristicReadRequest(device, 8, 0, location())
        assertEquals(listOf(BluetoothHrPeripheralError.PERMISSION), failures)
        assertFalse(states.last().active)
        assertEquals(BluetoothHrPeripheralError.PERMISSION, states.last().error)
        verify(exactly = 1) { server.sendResponse(any(), any(), any(), any(), any()) }
        verify { server.close() }
    }

    @Test fun `descriptor read arriving after stop does not access GATT server`() {
        val (peripheral, callback) = peripheral()
        peripheral.stop()
        val descriptor = android.bluetooth.BluetoothGattDescriptor(
            BluetoothHeartRatePeripheral.CLIENT_CONFIGURATION,
            android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ
        )
        callback.onDescriptorReadRequest(device, 7, 0, descriptor)
        verify(exactly = 0) { server.sendResponse(any(), any(), any(), any(), any()) }
    }
}
