/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.hrm.*
import io.automated.ventures.everypods.bluetooth.hrm.SharingError
import io.automated.ventures.everypods.data.Capability
import io.automated.ventures.everypods.presentation.components.SettingsGroup
import io.automated.ventures.everypods.presentation.components.SettingsHeading
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun HeartRateScreen(viewModel: AirPodsViewModel) {
    val ui by viewModel.uiState.collectAsState()
    val session by viewModel.heartRateState.collectAsState()
    val sharing by viewModel.heartRateSharingState.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var method by remember { mutableStateOf(HeartRateSharingPrefs.method(context)) }
    var gymAuto by remember { mutableStateOf(HeartRateSharingPrefs.gymAuto(context)) }
    var denied by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var pendingMethod by remember { mutableStateOf(method) }
    var pendingAuto by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    // Preference listener also reflects changes made outside this screen.
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            method = HeartRateSharingPrefs.method(context)
            gymAuto = HeartRateSharingPrefs.gymAuto(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun applyPending() {
        HeartRateSharingPrefs.setMethod(context, pendingMethod)
        if (pendingAuto) HeartRateSharingPrefs.setGymAuto(context, true)
        if (pendingStart) viewModel.startHeartRateSharing(pendingMethod)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        denied = granted.values.any { !it }
        if (!denied) applyPending()
    }
    fun prepare(selected: HeartRateSharingMethod, auto: Boolean = false, start: Boolean = true) {
        pendingMethod = selected
        pendingAuto = auto
        pendingStart = start
        denied = false
        val needed = buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (selected == HeartRateSharingMethod.BLUETOOTH) add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) applyPending() else permission.launch(needed.toTypedArray())
    }
    val supported = ui.modelIdentified && ui.instance?.model?.capabilities?.contains(Capability.HRM) == true
    val owner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopHeartRatePreview()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.stopHeartRatePreview() }
    }
    StyledScaffold(title = stringResource(R.string.hrs_title)) { top, _, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(top))
            if (!ui.aacpAvailable) {
                Text(stringResource(R.string.requires_aacp_message), fontSize = 16.sp)
            } else if (!supported) {
                SettingsGroup {
                    Text(
                        stringResource(if (ui.modelIdentified) R.string.hrm_unsupported else R.string.hrs_identifying),
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                    )
                }
            } else {
                val failure = when (session.failure) {
                    HeartRateFailure.UNSUPPORTED -> R.string.hrm_unsupported
                    HeartRateFailure.NO_CONNECTION -> R.string.hrm_connection
                    HeartRateFailure.NOT_OWNER -> R.string.hrm_not_owner
                    HeartRateFailure.SEND_FAILED -> R.string.hrm_send_failed
                    HeartRateFailure.NO_DATA -> R.string.hrm_no_data
                    HeartRateFailure.CONNECTION_LOST -> R.string.hrm_lost
                    HeartRateFailure.DEVICE_CHANGED -> R.string.hrm_device_changed
                    null -> null
                }
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(session.bpm?.let { stringResource(R.string.hrm_bpm, it) } ?: "—",
                            fontSize = 48.sp, fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)))
                        if (!sharing.active) {
                            Button(
                                onClick = { if (session.running) viewModel.stopHeartRate() else viewModel.startHeartRate() },
                                enabled = session.running || ui.isLocallyConnected,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(if (session.running) R.string.hrm_stop else R.string.hrs_start_monitoring))
                            }
                        }
                        Text(stringResource(if (sharing.recovering) R.string.hrs_reconnecting else when (session.status) {
                            HeartRateStatus.STOPPED -> R.string.hrm_stopped
                            HeartRateStatus.WAITING -> R.string.hrm_waiting
                            HeartRateStatus.LIVE -> R.string.hrm_live
                            HeartRateStatus.STALE -> R.string.hrm_stale
                            HeartRateStatus.ERROR -> R.string.hrm_error
                        }), fontSize = 13.sp)
                        if (session.bpm != null) session.sampleTimeMillis?.let {
                            Text(stringResource(R.string.hrm_updated, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (failure != null && !sharing.recovering) Text(
                            stringResource(failure), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                SettingsHeading(stringResource(R.string.hrs_share))
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeartRateSharingMethod.entries.forEach { option ->
                            Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                                if (method != option) {
                                    if (sharing.active || gymAuto) prepare(option, start = sharing.active)
                                    else HeartRateSharingPrefs.setMethod(context, option)
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = method == option, onClick = null)
                                Text(stringResource(if (option == HeartRateSharingMethod.TICKR) R.string.hrs_tickr else R.string.hrs_bluetooth),
                                    fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        Text(stringResource(if (method == HeartRateSharingMethod.TICKR) R.string.hrs_tickr_hint else R.string.hrs_bluetooth_hint),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sharing.active) Text(stringResource(when {
                            sharing.recovering -> R.string.hrs_reconnecting
                            session.status == HeartRateStatus.STALE -> R.string.hrm_stale
                            session.bpm == null -> R.string.hrm_waiting
                            sharing.wahooDiagnostic -> R.string.hrs_tickr_sending
                            sharing.subscriptions > 0 -> R.string.hrs_ble_subscribed
                            sharing.clients > 0 -> R.string.hrs_ble_connected
                            sharing.advertised -> R.string.hrs_ble_ready
                            else -> R.string.hrm_ble_preparing
                        }), fontSize = 13.sp)
                        val error = when (sharing.error) {
                            SharingError.WAHOO_UNAVAILABLE -> R.string.hrs_wahoo_missing
                            SharingError.PERMISSION, SharingError.BLUETOOTH_PERMISSION -> R.string.hrm_share_permission
                            SharingError.BLUETOOTH_UNSUPPORTED -> R.string.hrm_ble_unsupported
                            SharingError.BLUETOOTH_GATT -> R.string.hrm_ble_gatt
                            SharingError.BLUETOOTH_ADVERTISING -> R.string.hrm_ble_advertising
                            null -> null
                            else -> R.string.hrm_share_server
                        }
                        if (denied || error != null) Text(stringResource(if (denied) R.string.hrm_share_permission else error!!),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { if (sharing.active) viewModel.stopHeartRate() else prepare(method) },
                            enabled = sharing.active || ui.isLocallyConnected,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if (sharing.active) R.string.hrm_share_stop else R.string.hrs_start_sharing))
                        }
                        Text(stringResource(R.string.hrs_background_hint), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsHeading(stringResource(R.string.ui_automation))
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.hrs_gym_auto), fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = gymAuto, onCheckedChange = {
                                if (it) prepare(method, auto = true, start = false)
                                else HeartRateSharingPrefs.setGymAuto(context, false)
                            })
                        }
                        Text(stringResource(R.string.hrs_gym_hint), fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                TextButton(onClick = { diagnostics = !diagnostics }) { Text(stringResource(R.string.hrs_diagnostics)) }
                if (diagnostics) SettingsGroup {
                    Text(stringResource(R.string.hrm_share_counts, sharing.clients, sharing.subscriptions, sharing.sent),
                        modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(bottom))
        }
    }
}
