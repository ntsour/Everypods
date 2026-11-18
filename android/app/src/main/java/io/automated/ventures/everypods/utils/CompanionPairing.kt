/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.util.Log

/**
 * A companion association with the AirPods lets this app call the hidden
 * BluetoothDevice.connect(), which brings HFP up together with A2DP during a call
 * handover instead of waiting for the AirPods to connect it themselves.
 */
object CompanionPairing {
    private const val TAG = "CompanionPairing"

    fun savedMac(context: Context): String =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("mac_address", "") ?: ""

    fun isAssociated(context: Context, mac: String = savedMac(context)): Boolean {
        if (mac.isBlank()) return false
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching {
            cdm.myAssociations.any { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }
        }.getOrDefault(false)
    }

    /** Shows Android's one-time confirmation for the saved AirPods. */
    fun request(
        context: Context,
        onConfirmationNeeded: (IntentSender) -> Unit,
        onDone: (associated: Boolean, error: String?) -> Unit,
    ) {
        val mac = savedMac(context)
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        if (mac.isBlank() || cdm == null) { onDone(false, null); return }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(mac).build())
            .setSingleDevice(true)
            .build()
        cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onConfirmationNeeded(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                Log.i(TAG, "Associated with ${associationInfo.deviceMacAddress}")
                onDone(true, null)
            }
            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "Association failed: $error")
                onDone(false, error?.toString())
            }
        })
    }
}
