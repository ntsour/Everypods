/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services


import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG="AppListenerService"

val cameraPackages = mutableSetOf(
    "com.google.android.GoogleCamera",
    "com.sec.android.app.camera",
    "com.android.camera",
    "com.oppo.camera",
    "com.motorola.camera2",
    "org.codeaurora.snapcam"
)

// Packages that must NOT reset the cameraOpen flag — system overlays, launchers,
// keyboards, and our own app (stem press can briefly surface our service window).
private val cameraOverlayPackages = setOf(
    "com.android.systemui",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard",
    "com.swiftkey.swiftkeyapp",
    "com.touchtype.swiftkey",
    "android",
    // Launchers — appear briefly during camera interactions
    "com.google.android.apps.nexuslauncher",
    "com.sec.android.app.launcher",
    "com.huawei.android.launcher",
    "com.miui.home",
    "com.oneplus.launcher",
    "com.oppo.launcher",
    // Our own package — stem press surfaces the service briefly
    "me.kavishdevar.librepods"
)

var cameraOpen = false
private var currentCustomPackage: String? = null

class AppListenerService: AccessibilityService() {
    private lateinit var prefs: android.content.SharedPreferences
    private val preferenceChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "custom_camera_package") {
            val newPackage = sharedPreferences.getString(key, null)
            currentCustomPackage?.let { cameraPackages.remove(it) }
            if (!newPackage.isNullOrBlank()) {
                cameraPackages.add(newPackage)
            }
            currentCustomPackage = newPackage
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val customPackage = prefs.getString("custom_camera_package", null)
        if (!customPackage.isNullOrBlank()) {
            cameraPackages.add(customPackage)
            currentCustomPackage = customPackage
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected — AppListenerService is live")
        instance = this

    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    /** Fire the camera shutter via the IAccessibilityServiceConnection hidden API.
     *  This is the same channel Android uses internally to handle key events in
     *  accessibility services — it routes directly to the focused window. */
    fun triggerShutter() {
        Log.d(TAG, "triggerShutter")
        val now = SystemClock.uptimeMillis()

        // Strategy 1: IAccessibilityServiceConnection.sendKeyEvent (hidden, pre-Q)
        // Strategy 2: AccessibilityService mConnectionImpl field
        // Strategy 3: fall back to adb-style via Runtime (xposed/root only)
        // Strategy 4: volume-key broadcast to camera's MediaButtonReceiver

        // Use dispatchGesture to tap the shutter button position.
        // Google Camera's shutter is always horizontally centred, ~82% down the screen.
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels * 0.74f  // shutter button at ~74% down the screen
        Log.d(TAG, "triggerShutter: tapping shutter at ($cx, $cy) on ${metrics.widthPixels}x${metrics.heightPixels}")

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "triggerShutter: gesture tap completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "triggerShutter: gesture tap cancelled")
            }
        }, null)
        Log.d(TAG, "triggerShutter: dispatchGesture returned $dispatched")
    }

    companion object {
        var instance: AppListenerService? = null
    }

    override fun onAccessibilityEvent(ev: AccessibilityEvent?) {
        try {
            Log.d(TAG, "onAccessibilityEvent: type=${ev?.eventType} pkg=${ev?.packageName}")
            if (ev?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = ev.packageName?.toString() ?: return
                // Ignore system overlay packages — they appear on top of the camera
                // but the user hasn't left the camera app
                if (pkg in cameraOverlayPackages) return
                Log.d(TAG, "Window changed → pkg=$pkg  cameraOpen=$cameraOpen")
                if (pkg in cameraPackages) {
                    Log.d(TAG, "✓ Camera opened: $pkg  serviceRef=${ServiceManager.getService()}")
                    if (!cameraOpen) cameraOpen = true
                    ServiceManager.getService()?.cameraOpened()
                } else {
                    if (cameraOpen) {
                        Log.d(TAG, "Camera closed by $pkg")
                        cameraOpen = false
                        ServiceManager.getService()?.cameraClosed()
                    }
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }
}
