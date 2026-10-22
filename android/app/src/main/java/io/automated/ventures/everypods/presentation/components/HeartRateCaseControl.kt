/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateFailure
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingPrefs
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateStatus
import io.automated.ventures.everypods.bluetooth.hrm.SharingError
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel

/** Home is another control for the service session; composition never starts or stops it. */
@Composable
fun HeartRateCaseControl(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsState()
    val session by viewModel.heartRateState.collectAsState()
    val sharing by viewModel.heartRateSharingState.collectAsState()
    var pendingMethod by rememberSaveable { mutableStateOf(HeartRateSharingPrefs.method(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            // The service rechecks capability, ownership and the current AACP connection.
            viewModel.startHeartRateSharing(pendingMethod)
        } else {
            Toast.makeText(context, R.string.hrm_share_permission, Toast.LENGTH_LONG).show()
        }
    }
    val active = session.running || sharing.active
    val ready = ui.aacpAvailable && ui.isLocallyConnected
    val bpm = session.bpm.takeIf { session.status == HeartRateStatus.LIVE && !sharing.recovering }
    val failureText = when (sharing.error) {
        SharingError.WAHOO_UNAVAILABLE -> R.string.hrs_wahoo_missing
        SharingError.PERMISSION, SharingError.BLUETOOTH_PERMISSION -> R.string.hrm_share_permission
        SharingError.BLUETOOTH_UNSUPPORTED -> R.string.hrm_ble_unsupported
        SharingError.BLUETOOTH_GATT -> R.string.hrm_ble_gatt
        SharingError.BLUETOOTH_ADVERTISING -> R.string.hrm_ble_advertising
        null -> when (session.failure) {
            HeartRateFailure.UNSUPPORTED -> R.string.hrm_unsupported
            HeartRateFailure.NO_CONNECTION -> R.string.hrm_connection
            HeartRateFailure.NOT_OWNER -> R.string.hrm_not_owner
            HeartRateFailure.SEND_FAILED -> R.string.hrm_send_failed
            HeartRateFailure.NO_DATA -> R.string.hrm_no_data
            HeartRateFailure.CONNECTION_LOST -> R.string.hrm_lost
            HeartRateFailure.DEVICE_CHANGED -> R.string.hrm_device_changed
            null -> null
        }
        else -> R.string.hrm_share_server
    }
    LaunchedEffect(failureText, sharing.recovering) {
        if (failureText != null && !sharing.recovering) {
            Toast.makeText(context, failureText, Toast.LENGTH_LONG).show()
        }
    }
    val status = when {
        sharing.recovering -> stringResource(R.string.hrs_reconnecting)
        failureText != null -> stringResource(failureText)
        !ui.aacpAvailable -> stringResource(R.string.requires_aacp_message)
        !ready && !active -> stringResource(R.string.hrm_connection)
        bpm != null -> stringResource(R.string.hrm_bpm, bpm)
        session.status == HeartRateStatus.STALE -> stringResource(R.string.hrm_stale)
        active -> stringResource(R.string.hrm_waiting)
        else -> stringResource(R.string.hrm_stopped)
    }
    val action = stringResource(if (active) R.string.hrm_share_stop else R.string.hrs_start_sharing)
    val title = stringResource(R.string.hrs_title)
    // Case artwork stays white in both themes. Keep the overlay legible on its surface.
    val ink = Color(0xFF34393C).copy(alpha = if (ready || active) 1f else 0.45f)
    val liveColor = Color(0xFFC34E58)
    val broadcastColor = Color(0xFF39764A)
    val sending = sharing.active && !sharing.recovering && bpm != null &&
        (sharing.wahooDiagnostic || (sharing.bluetoothPeripheral && sharing.advertised))
    BoxWithConstraints(
        Modifier.widthIn(min = 48.dp, max = 110.dp).heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = status
            }
            .clickable(enabled = active || ready, role = Role.Button, onClickLabel = action) {
                if (viewModel.heartRateState.value.running || viewModel.heartRateSharingState.value.active) {
                    viewModel.stopHeartRate()
                } else {
                    pendingMethod = HeartRateSharingPrefs.method(context)
                    val missing = buildList {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        if (pendingMethod == HeartRateSharingMethod.BLUETOOTH) add(Manifest.permission.BLUETOOTH_ADVERTISE)
                    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) viewModel.startHeartRateSharing(pendingMethod)
                    else permission.launch(missing.toTypedArray())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val largeText = LocalDensity.current.fontScale > 1.3f || maxWidth < 80.dp
        val heart: @Composable () -> Unit = {
            Icon(
                if (!active && failureText != null) Icons.Outlined.Refresh else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (bpm != null) liveColor else ink,
                modifier = Modifier.size(if (active) 16.dp else 21.dp),
            )
        }
        val signal: @Composable () -> Unit = {
            if (sharing.recovering || (active && bpm == null && session.status != HeartRateStatus.STALE)) {
                CircularProgressIndicator(Modifier.size(12.dp), color = ink, strokeWidth = 1.dp)
            } else {
                BroadcastArcs(if (sending) broadcastColor else ink.copy(alpha = 0.45f))
            }
        }
        if (active && largeText) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    heart()
                    signal()
                }
                Text(bpm?.toString() ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                heart()
                if (active) Text(bpm?.toString() ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink)
                signal()
            }
        }
    }
}

@Composable
private fun BroadcastArcs(color: Color) {
    Canvas(Modifier.size(width = 13.dp, height = 22.dp)) {
        for (radius in listOf(4.dp, 8.dp, 12.dp)) {
            val r = radius.toPx()
            drawArc(
                color, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(-r, size.height / 2 - r), size = Size(r * 2, r * 2),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
}
