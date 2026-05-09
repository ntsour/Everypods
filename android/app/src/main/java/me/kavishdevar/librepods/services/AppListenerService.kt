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
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
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

// Packages that appear on top of the camera (keyboard, system UI, permission dialogs…)
// and must NOT reset the cameraOpen flag.
private val cameraOverlayPackages = setOf(
    "com.android.systemui",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard",
    "com.swiftkey.swiftkeyapp",
    "com.touchtype.swiftkey",
    "android"
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

    /** Inject KEYCODE_VOLUME_DOWN directly into the focused window via AccessibilityService.
     *  This bypasses media-session routing and hits the actual foreground app input. */
    fun triggerShutter() {
        Log.d(TAG, "triggerShutter via AccessibilityService key injection")
        val down = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0)
        val up   = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0)
        // dispatchKeyEvent is available on AccessibilityService (hidden API, reflection)
        runCatching {
            val method = AccessibilityService::class.java.getMethod("dispatchKeyEvent", KeyEvent::class.java)
            method.invoke(this, down)
            method.invoke(this, up)
            Log.d(TAG, "triggerShutter: dispatchKeyEvent succeeded")
        }.onFailure { e ->
            Log.w(TAG, "triggerShutter: dispatchKeyEvent failed ($e), trying KEYCODE_CAMERA")
            runCatching {
                val method = AccessibilityService::class.java.getMethod("dispatchKeyEvent", KeyEvent::class.java)
                val downC = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAMERA, 0)
                val upC   = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAMERA, 0)
                method.invoke(this, downC)
                method.invoke(this, upC)
            }.onFailure { e2 ->
                Log.w(TAG, "triggerShutter: KEYCODE_CAMERA also failed: $e2")
            }
        }
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
