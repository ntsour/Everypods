/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.automated.ventures.everypods.automation.HeartRateSource
import io.automated.ventures.everypods.automation.HrStartDecision
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
    val preferences = remember(context) { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var configuredMethod by remember(context) { mutableStateOf(HeartRateSharingPrefs.configuredMethod(context)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HeartRateSharingPrefs.METHOD) configuredMethod = HeartRateSharingPrefs.configuredMethod(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var pendingMethod by rememberSaveable { mutableStateOf(HeartRateSharingPrefs.method(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            // Re-run the shared start step: it rechecks selection, running state and permissions.
            viewModel.ensureHeartRateStarted(HeartRateSource.CASE_SHORTCUT)
        } else {
            Toast.makeText(context, R.string.hrm_share_permission, Toast.LENGTH_LONG).show()
        }
    }
    val presentation = heartRateCasePresentation(session, sharing, configuredMethod)
    val active = session.running || session.status == HeartRateStatus.RECOVERING || sharing.active
    val ready = ui.aacpAvailable && ui.isLocallyConnected
    val bpm = presentation.bpm
    val failureText = when (sharing.error) {
        SharingError.WIFI_REQUIRED -> R.string.hrm_share_wifi
        SharingError.UNSUPPORTED_NETWORK -> R.string.dircon_ipv4_required
        SharingError.NETWORK_LOST -> R.string.hrm_share_network_lost
        SharingError.ADVERTISEMENT -> R.string.hrm_share_advertisement
        SharingError.PORT_IN_USE -> R.string.dircon_port_in_use
        SharingError.WAHOO_UNAVAILABLE -> R.string.hrs_wahoo_missing
        SharingError.PERMISSION, SharingError.BLUETOOTH_PERMISSION ->
            if (pendingMethod == HeartRateSharingMethod.DIRCON) R.string.dircon_permission else R.string.hrm_share_permission
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
    // Returning to Home recreates this control while the service can still hold an old
    // error. Only announce changes observed while the control is on screen.
    var failureObserved by remember { mutableStateOf(false) }
    LaunchedEffect(failureText, sharing.recovering) {
        if (failureObserved && failureText != null && !sharing.recovering) {
            Toast.makeText(context, failureText, Toast.LENGTH_LONG).show()
        }
        failureObserved = true
    }
    val status = when {
        sharing.recovering || session.status == HeartRateStatus.RECOVERING -> stringResource(R.string.hrs_reconnecting)
        failureText != null -> stringResource(failureText)
        !ui.aacpAvailable -> stringResource(R.string.requires_aacp_message)
        !ready && !active -> stringResource(R.string.hrm_connection)
        bpm != null -> stringResource(R.string.hrm_bpm, bpm)
        session.status == HeartRateStatus.STALE -> stringResource(R.string.hrm_stale)
        active -> stringResource(R.string.hrm_waiting)
        else -> stringResource(R.string.hrm_stopped)
    }
    val action = stringResource(when {
        active -> R.string.hrm_share_stop
        configuredMethod == null -> R.string.hrs_start_monitoring
        else -> R.string.hrs_start_sharing
    })
    val title = stringResource(R.string.hrs_title)
    // The physical case artwork is white in both themes.
    val ink = Color(0xFF202124)
    val inactiveColor = Color(0xFF9A9A9A)
    val liveColor = Color(0xFFE53935)
    val sharingDescription = presentation.method?.let { method ->
        val name = stringResource(when (method) {
            HeartRateSharingMethod.TICKR -> R.string.hrs_tickr
            HeartRateSharingMethod.BLUETOOTH -> R.string.hrs_bluetooth
            HeartRateSharingMethod.DIRCON -> R.string.dircon_method
        })
        val state = stringResource(if (presentation.sharing) when (method) {
            HeartRateSharingMethod.TICKR -> R.string.hrs_tickr_sending
            HeartRateSharingMethod.BLUETOOTH -> R.string.hrs_ble_ready
            HeartRateSharingMethod.DIRCON -> R.string.dircon_ready
        } else R.string.hrs_sharing_off)
        "$name, $state"
    }
    BoxWithConstraints(
        Modifier.widthIn(min = 48.dp, max = 110.dp).heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = listOfNotNull(status, sharingDescription).joinToString(", ")
            }
            .clickable(enabled = active || ready, role = Role.Button, onClickLabel = action) {
                if (viewModel.heartRateState.value.running ||
                    viewModel.heartRateState.value.status == HeartRateStatus.RECOVERING ||
                    viewModel.heartRateSharingState.value.active) {
                    // The shortcut is a toggle; only this manual tap ever stops.
                    viewModel.stopHeartRate(HeartRateSource.CASE_SHORTCUT)
                } else when (val decision = viewModel.ensureHeartRateStarted(HeartRateSource.CASE_SHORTCUT)) {
                    // Manual tap with no sharing selection keeps the existing monitoring-only
                    // start; the automation treats NoneSelected as "start nothing".
                    HrStartDecision.NoneSelected -> viewModel.startHeartRate(HeartRateSource.CASE_SHORTCUT)
                    is HrStartDecision.NeedsPermission -> {
                        pendingMethod = decision.method
                        permission.launch(decision.permissions.toTypedArray())
                    }
                    else -> Unit
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val largeText = LocalDensity.current.fontScale > 1.3f || maxWidth < 80.dp
        val heart: @Composable () -> Unit = {
            Icon(
                if (presentation.monitoring) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = if (presentation.monitoring) liveColor else inactiveColor,
                modifier = Modifier.size(if (presentation.monitoring) 16.dp else 21.dp),
            )
        }
        val signal: @Composable () -> Unit = {
            presentation.method?.let { method ->
                val color = if (presentation.sharing) ink else inactiveColor
                when (method) {
                    HeartRateSharingMethod.TICKR -> Text(stringResource(R.string.hrs_tickr_case_symbol), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    HeartRateSharingMethod.BLUETOOTH -> Icon(Icons.Outlined.Bluetooth, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
                    HeartRateSharingMethod.DIRCON -> Icon(Icons.Outlined.Wifi, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
                }
            }
        }
        if (presentation.monitoring && largeText) {
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
                if (presentation.monitoring) Text(bpm?.toString() ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink)
                signal()
            }
        }
    }
}
