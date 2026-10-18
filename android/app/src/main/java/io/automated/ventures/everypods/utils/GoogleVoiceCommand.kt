package io.automated.ventures.everypods.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent

/** Uses Android's headset voice-command contract, with Google as the explicit receiver. */
object GoogleVoiceCommand {
    fun launch(context: Context, headset: BluetoothDevice) {
        context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).apply {
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Android's voice-command contract identifies which headset requested the session.
            // Google owns microphone selection and assistant behavior; EveryPods does not record.
            putExtra(BluetoothDevice.EXTRA_DEVICE, headset)
            putExtra(BluetoothProfile.EXTRA_PROFILE, BluetoothProfile.HEADSET)
        })
    }
}
