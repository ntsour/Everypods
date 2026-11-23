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


package io.automated.ventures.everypods.presentation.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.media.AudioManager
import android.widget.VideoView
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.data.AirPodsNotifications
import io.automated.ventures.everypods.data.Battery
import io.automated.ventures.everypods.data.BatteryComponent
import io.automated.ventures.everypods.data.BatteryStatus
import io.automated.ventures.everypods.utils.HoldKind
import io.automated.ventures.everypods.utils.HoldPhase
import io.automated.ventures.everypods.utils.HoldStatus
import io.automated.ventures.everypods.utils.PlaybackHold

@SuppressLint("InflateParams", "ClickableViewAccessibility")
class PopupWindow(
    private val context: Context,
    private val onCloseCallback: () -> Unit = {}
) {
    private val mView: View
    private var isClosing = false
    private var autoCloseHandler = Handler(Looper.getMainLooper())
    private var autoCloseRunnable: Runnable? = null
    private var batteryUpdateReceiver: BroadcastReceiver? = null
    private var holdMode = false
    private var holdMorphed = false

    /** True while this popup shows the connecting/failed hold look. */
    val isHolding: Boolean get() = holdMode

    @Suppress("DEPRECATION")
    private val mParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        height = WindowManager.LayoutParams.WRAP_CONTENT
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        width = if (screenWidthDp >= 600) {
            (400 * displayMetrics.density).toInt()
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dimAmount = 0.3f
        flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }

    private val mWindowManager: WindowManager

    init {
        val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mView = layoutInflater.inflate(R.layout.popup_window, null)
        mParams.x = 0
        mParams.y = 0

        mView.setOnClickListener {
            close()
        }

        mView.findViewById<ImageButton>(R.id.close_button).setOnClickListener {
            close()
        }

        val ll = mView.findViewById<LinearLayout>(R.id.linear_layout)
        ll.setOnClickListener {
            close()
        }

        @Suppress("DEPRECATION")
        mView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        mView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val touchY = event.rawY
                val popupTop = mView.top
                if (touchY < popupTop) {
                    close()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        mWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        mView.findViewById<View>(R.id.hold_retry).setOnClickListener { PlaybackHold.retry() }
        mView.findViewById<View>(R.id.hold_play_on_phone).setOnClickListener { PlaybackHold.playOnPhone() }
    }

    @SuppressLint("InlinedApi", "SetTextI18s")
    fun open(
        name: String = "AirPods Pro",
        batteryNotification: AirPodsNotifications.BatteryNotification,
        holdMode: Boolean = false
    ) {
        try {
            this.holdMode = holdMode
            this.holdMorphed = false
            applyHoldVisibility(holdMode)
            if (mView.windowToken == null && mView.parent == null && !isClosing) {
                mView.findViewById<TextView>(R.id.name).text = name

                updateBatteryStatus(batteryNotification)

                val vid = mView.findViewById<VideoView>(R.id.video)
                UiVideoPlayback.configure(vid)
                vid.setOnPreparedListener { player ->
                    player.setVolume(0f, 0f)
                    io.automated.ventures.everypods.utils.LidAutoconnectDiagnostics.logEvent("ui_video_start", mapOf("component" to "popup", "usage" to "sonification"))
                }
                vid.setVideoPath("android.resource://${mView.context.packageName}/" + R.raw.connected)
                vid.resolveAdjustedSize(vid.width, vid.height)
                vid.start()
                vid.setOnCompletionListener {
                    vid.start()
                }

                try {
                    mWindowManager.addView(mView, mParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val displayMetrics = mView.context.resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels

                mView.translationY = screenHeight.toFloat()
                mView.alpha = 1f

                val translationY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, screenHeight.toFloat(), 0f)

                ObjectAnimator.ofPropertyValuesHolder(mView, translationY).apply {
                    duration = 500
                    interpolator = DecelerateInterpolator()
                    start()
                }

                registerBatteryUpdateReceiver()

                if (!holdMode) startAutoCloseTimer()
            }
        } catch (e: Exception) {
            Log.e("PopupWindow", "Error opening popup: ${e.message}")
            onCloseCallback()
        }
    }

    private fun startAutoCloseTimer() {
        autoCloseRunnable?.let { autoCloseHandler.removeCallbacks(it) }
        autoCloseRunnable = Runnable { close() }
        autoCloseHandler.postDelayed(autoCloseRunnable!!, 12000)
    }

    private fun applyHoldVisibility(hold: Boolean) {
        mView.findViewById<View>(R.id.hold_container).visibility = if (hold) View.VISIBLE else View.GONE
        mView.findViewById<View>(R.id.battery_row).visibility = if (hold) View.GONE else View.VISIBLE
    }

    /**
     * Playback-hold update; safe to call repeatedly on an open popup. Shows the phase text and
     * a progress bar, or Retry / Play on phone on FAILED. CONNECTED switches to the normal
     * popup content and starts the normal auto-close.
     */
    fun updateHold(status: HoldStatus, progress: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            autoCloseHandler.post { updateHold(status, progress) }
            return
        }
        if (isClosing || mView.parent == null || holdMorphed) return
        val text = mView.findViewById<TextView>(R.id.hold_status_text)
        val bar = mView.findViewById<ProgressBar>(R.id.hold_progress)
        val buttons = mView.findViewById<View>(R.id.hold_buttons)
        when (status.phase) {
            HoldPhase.CONNECTED -> {
                holdMorphed = true
                holdMode = false
                applyHoldVisibility(false)
                startAutoCloseTimer()
            }
            HoldPhase.FAILED -> {
                holdMode = true
                applyHoldVisibility(true)
                text.text = context.getString(R.string.hold_failed, status.deviceName)
                mView.findViewById<TextView>(R.id.hold_play_on_phone)?.text = context.getString(
                    if (status.kind == HoldKind.CALL) R.string.hold_use_phone else R.string.hold_play_on_phone
                )
                bar.visibility = View.GONE
                buttons.visibility = View.VISIBLE
            }
            else -> {
                holdMode = true
                applyHoldVisibility(true)
                text.text = when (status.phase) {
                    HoldPhase.REQUESTING_RELEASE ->
                        if (status.peerName.isNullOrEmpty()) context.getString(R.string.hold_asking_peer_generic)
                        else context.getString(R.string.hold_asking_peer, status.peerName)
                    HoldPhase.CONNECTING -> context.getString(
                        if (status.kind == HoldKind.CALL) R.string.hold_call_connecting else R.string.hold_connecting,
                        status.deviceName
                    )
                    else -> context.getString(
                        if (status.kind == HoldKind.CALL) R.string.hold_call_switching else R.string.hold_switching_audio
                    )
                }
                bar.visibility = View.VISIBLE
                buttons.visibility = View.GONE
                bar.progress = (progress.coerceIn(0f, 1f) * 100f).toInt()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBatteryUpdateReceiver() {
        batteryUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.BATTERY_DATA) {
                    val batteryList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra("data", Battery::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra("data")
                    }
                    if (batteryList != null) {
                        updateBatteryStatusFromList(batteryList)
                    }
                }
            }
        }

        val filter = IntentFilter(AirPodsNotifications.BATTERY_DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(batteryUpdateReceiver, filter)
        }
    }

    private fun unregisterBatteryUpdateReceiver() {
        batteryUpdateReceiver?.let {
            try {
                context.unregisterReceiver(it)
                batteryUpdateReceiver = null
            } catch (e: Exception) {
                Log.e("PopupWindow", "Error unregistering battery receiver: ${e.message}")
            }
        }
    }

    private fun updateBatteryStatusFromList(batteryList: List<Battery>) {
        val batteryLeftText = mView.findViewById<TextView>(R.id.left_battery)
        val batteryRightText = mView.findViewById<TextView>(R.id.right_battery)
        val batteryCaseText = mView.findViewById<TextView>(R.id.case_battery)

        batteryLeftText.text = batteryList.find { it.component == BatteryComponent.LEFT }?.let {
            if (it.status != BatteryStatus.DISCONNECTED) {
                "\uDBC3\uDC8E    ${it.level}%"
            } else {
                ""
            }
        } ?: ""

        batteryRightText.text = batteryList.find { it.component == BatteryComponent.RIGHT }?.let {
            if (it.status != BatteryStatus.DISCONNECTED) {
                "\uDBC3\uDC8D    ${it.level}%"
            } else {
                ""
            }
        } ?: ""

        batteryCaseText.text = batteryList.find { it.component == BatteryComponent.CASE }?.let {
            if (it.status != BatteryStatus.DISCONNECTED) {
                "\uDBC3\uDE6C    ${it.level}%"
            } else {
                ""
            }
        } ?: ""
    }

    @SuppressLint("SetTextI18s")
    fun updateBatteryStatus(batteryNotification: AirPodsNotifications.BatteryNotification) {
        val batteryStatus = batteryNotification.getBattery()
        updateBatteryStatusFromList(batteryStatus)
    }

    fun close() {
        try {
            if (isClosing) return
            isClosing = true

            autoCloseRunnable?.let { autoCloseHandler.removeCallbacks(it) }
            unregisterBatteryUpdateReceiver()

            val vid = mView.findViewById<VideoView>(R.id.video)
            vid.stopPlayback()

            ObjectAnimator.ofFloat(mView, "translationY", mView.height.toFloat()).apply {
                duration = 500
                interpolator = AccelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try {
                            mView.visibility = View.GONE
                            if (mView.parent != null) {
                                mWindowManager.removeView(mView)
                            }
                        } catch (e: Exception) {
                            Log.e("PopupWindow", "Error removing view: ${e.message}")
                        } finally {
                            isClosing = false
                            onCloseCallback()
                        }
                    }
                })
                start()
            }
        } catch (e: Exception) {
            Log.e("PopupWindow", "Error closing popup: ${e.message}")
            isClosing = false
            onCloseCallback()
        }
    }
}
