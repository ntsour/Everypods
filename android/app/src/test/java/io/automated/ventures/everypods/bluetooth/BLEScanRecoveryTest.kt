package io.automated.ventures.everypods.bluetooth

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BLEScanRecoveryTest {
    private fun BLEManager.setField(name: String, value: Any?) {
        BLEManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }
    private fun BLEManager.field(name: String): Any? =
        BLEManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    @Test fun `failed probe preserves the existing scan`() {
        val manager = BLEManager(ApplicationProvider.getApplicationContext())
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        val existing = mockk<ScanCallback>(relaxed = true)
        val probe = slot<ScanCallback>()
        every { scanner.startScan(null, any<ScanSettings>(), capture(probe)) } just runs
        manager.setField("mBluetoothLeScanner", scanner)
        manager.setField("mScanCallback", existing)
        manager.setField("scanCallbackActive", true)

        manager.ensureScanning("watchdog", force = true)
        verify(exactly = 0) { scanner.stopScan(existing) }
        probe.captured.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        verify(exactly = 0) { scanner.stopScan(existing) }
        assertSame(existing, manager.field("mScanCallback"))
        manager.stopScanning()
    }

    @Test fun `only a fresh probe result replaces the existing scan`() {
        val manager = BLEManager(ApplicationProvider.getApplicationContext())
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        val existing = mockk<ScanCallback>(relaxed = true)
        val probe = slot<ScanCallback>()
        every { scanner.startScan(null, any<ScanSettings>(), capture(probe)) } just runs
        manager.setField("mBluetoothLeScanner", scanner)
        manager.setField("mScanCallback", existing)
        manager.setField("scanCallbackActive", true)
        manager.ensureScanning("watchdog", force = true)

        val result = mockk<ScanResult>(relaxed = true)
        every { result.timestampNanos } returns 0L
        probe.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
        verify(exactly = 0) { scanner.stopScan(existing) }
        every { result.timestampNanos } returns (SystemClock.elapsedRealtime().coerceAtLeast(1L) * 1_000_000L)
        probe.captured.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
        verify(exactly = 1) { scanner.stopScan(existing) }
        assertSame(probe.captured, manager.field("mScanCallback"))
        manager.stopScanning()
    }

    @Test fun `silent probe expires without stopping the existing registration`() {
        val manager = BLEManager(ApplicationProvider.getApplicationContext())
        val scanner = mockk<BluetoothLeScanner>(relaxed = true)
        val existing = mockk<ScanCallback>(relaxed = true)
        val probe = slot<ScanCallback>()
        every { scanner.startScan(null, any<ScanSettings>(), capture(probe)) } just runs
        manager.setField("mBluetoothLeScanner", scanner)
        manager.setField("mScanCallback", existing)
        manager.setField("scanCallbackActive", true)
        manager.ensureScanning("watchdog", force = true)
        shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.SECONDS)
        verify(exactly = 1) { scanner.stopScan(probe.captured) }
        verify(exactly = 0) { scanner.stopScan(existing) }
        assertSame(existing, manager.field("mScanCallback"))
        manager.stopScanning()
    }
}
