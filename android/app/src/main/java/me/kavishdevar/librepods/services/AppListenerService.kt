/*
    LibrePods - AirPods liberated from Apple's ecosystem
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

package me.kavishdevar.librepods.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

private const val TAG = "AppListenerService"

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
    "com.google.android.apps.nexuslauncher",
    "com.sec.android.app.launcher",
    "com.huawei.android.launcher",
    "com.miui.home",
    "com.oneplus.launcher",
    "com.oppo.launcher",
    "me.kavishdevar.librepods"
)

var cameraOpen = false
private var currentCustomPackage: String? = null

class AppListenerService : AccessibilityService() {
    private lateinit var prefs: android.content.SharedPreferences
    private val preferenceChangeListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
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
        Log.d(TAG, "Camera listener service connected")
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    /**
     * Trigger the camera shutter by tapping the shutter button position on screen
     * via [dispatchGesture]. The shutter button in most camera apps sits at the
     * horizontal centre, ~74% down the screen. Requires `canPerformGestures="true"`
     * in the accessibility service config XML.
     */
    fun triggerShutter() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels * 0.74f

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Shutter tap completed at ($cx, $cy)")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Shutter tap cancelled")
            }
        }, null)
    }

    override fun onAccessibilityEvent(ev: AccessibilityEvent?) {
        try {
            if (ev?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = ev.packageName?.toString() ?: return
                if (pkg in cameraOverlayPackages) return

                if (pkg in cameraPackages) {
                    if (!cameraOpen) cameraOpen = true
                    ServiceManager.getService()?.cameraOpened()
                } else {
                    if (cameraOpen) {
                        cameraOpen = false
                        ServiceManager.getService()?.cameraClosed()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: AppListenerService? = null
    }
}
