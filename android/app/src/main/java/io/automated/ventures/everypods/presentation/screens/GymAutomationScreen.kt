/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.chrisbanes.haze.hazeSource
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.automation.Event
import io.automated.ventures.everypods.automation.GymAutomationCoordinator
import io.automated.ventures.everypods.automation.GymAutomationRepository
import io.automated.ventures.everypods.automation.GymAutomationRule
import io.automated.ventures.everypods.automation.GymAutomationStatus
import io.automated.ventures.everypods.automation.GymGeofenceRegistrar
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingPrefs
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.presentation.components.SettingsSection
import io.automated.ventures.everypods.utils.GymModePrefs

/** Editor for the single V1 gym location rule. Works without AirPods connected. */
@SuppressLint("MissingPermission")
@Composable
fun GymAutomationScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var rule by remember { mutableStateOf(GymAutomationRepository.rule(context)) }
    var latText by remember { mutableStateOf(rule.latitude?.toString().orEmpty()) }
    var lonText by remember { mutableStateOf(rule.longitude?.toString().orEmpty()) }
    var message by remember { mutableStateOf<Int?>(null) }
    var fine by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(false) }
    val status by GymAutomationCoordinator.status.collectAsState()
    val method = remember(rule) { HeartRateSharingPrefs.configuredMethod(context) }
    val cancel = remember { CancellationTokenSource() }

    fun recheck() {
        fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        background = GymGeofenceRegistrar.hasPermissions(context)
        GymAutomationCoordinator.refreshStatus(context)
    }
    fun save(next: GymAutomationRule) {
        rule = GymAutomationRepository.saveRule(context, next)
        GymAutomationCoordinator.dispatch(context, Event.RuleChanged)
        GymGeofenceRegistrar.sync(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Re-check after returning from system settings; never assume a grant.
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { recheck(); GymGeofenceRegistrar.sync(context) } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel.cancel() }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        recheck()
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) message = R.string.gym_auto_permission_denied
    }

    StyledScaffold(title = stringResource(R.string.gym_auto_title)) { topPadding, hazeState, _ ->
        Column(
            Modifier.fillMaxSize().hazeSource(hazeState).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            Text(stringResource(statusText(status)), style = bodyStyle(dark), modifier = Modifier.padding(vertical = 8.dp))
            if (status == GymAutomationStatus.PAUSED) {
                Button(onClick = { GymAutomationCoordinator.dispatch(context, Event.ResumeVisit) }) {
                    Text(stringResource(R.string.gym_auto_resume))
                }
            } else if (status == GymAutomationStatus.ACTIVE) {
                OutlinedButton(onClick = { GymModePrefs.setEnabled(context, false) }) {
                    Text(stringResource(R.string.gym_auto_stop))
                }
            }
            SettingsSection(stringResource(R.string.gym_auto_title)) {
                StyledToggle(label = stringResource(R.string.gym_auto_enable), checked = rule.enabled,
                    description = if (!rule.hasLocation || !background) stringResource(R.string.gym_auto_status_setup) else null,
                    enabled = rule.hasLocation && background,
                    onCheckedChange = { save(rule.copy(enabled = it)) })
            }
            SettingsSection(stringResource(R.string.gym_auto_permissions)) {
                Text(stringResource(R.string.gym_auto_disclosure), style = bodyStyle(dark), modifier = Modifier.padding(16.dp))
                if (!fine) Button(modifier = Modifier.padding(horizontal = 16.dp), onClick = {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(stringResource(R.string.gym_auto_grant_precise)) }
                else if (!background) {
                    Text(stringResource(R.string.gym_auto_background_hint), style = bodyStyle(dark), modifier = Modifier.padding(horizontal = 16.dp))
                    Button(modifier = Modifier.padding(16.dp), onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(stringResource(R.string.gym_auto_grant_background)) }
                }
            }
            SettingsSection(stringResource(R.string.gym_auto_location)) {
                Text(
                    if (rule.hasLocation) stringResource(R.string.gym_auto_location_saved, "%.5f".format(rule.latitude), "%.5f".format(rule.longitude))
                    else stringResource(R.string.gym_auto_no_location),
                    style = bodyStyle(dark), modifier = Modifier.padding(16.dp))
                Button(enabled = fine, modifier = Modifier.padding(horizontal = 16.dp), onClick = {
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                        .addOnSuccessListener { loc ->
                            val ageMs = loc?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
                            if (loc == null || !loc.hasAccuracy() || loc.accuracy > 75f || (ageMs ?: Long.MAX_VALUE) > 120_000) {
                                message = R.string.gym_auto_fix_failed
                            } else {
                                latText = loc.latitude.toString(); lonText = loc.longitude.toString()
                                save(rule.copy(latitude = loc.latitude, longitude = loc.longitude)); message = null
                            }
                        }
                        .addOnFailureListener { message = R.string.gym_auto_fix_failed }
                }) { Text(stringResource(R.string.gym_auto_use_current)) }
                OutlinedTextField(latText, { latText = it }, label = { Text(stringResource(R.string.gym_auto_latitude)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                OutlinedTextField(lonText, { lonText = it }, label = { Text(stringResource(R.string.gym_auto_longitude)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                OutlinedButton(modifier = Modifier.padding(16.dp), onClick = {
                    val lat = latText.trim().replace(',', '.').toDoubleOrNull()
                    val lon = lonText.trim().replace(',', '.').toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) message = R.string.gym_auto_invalid_coordinates
                    else { save(rule.copy(latitude = lat, longitude = lon)); message = null }
                }) { Text(stringResource(R.string.gym_auto_save_location)) }
                message?.let { Text(stringResource(it), style = bodyStyle(dark), modifier = Modifier.padding(horizontal = 16.dp)) }
                Text(stringResource(R.string.gym_auto_radius, rule.radiusMeters), style = bodyStyle(dark), modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Text(stringResource(R.string.gym_auto_radius_hint), style = bodyStyle(dark), modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GymAutomationRule.RADIUS_PRESETS.forEach { r ->
                        FilterChip(selected = rule.radiusMeters == r, onClick = { save(rule.copy(radiusMeters = r)) },
                            label = { Text(stringResource(R.string.gym_auto_radius_value, r)) })
                    }
                }
            }
            SettingsSection(stringResource(R.string.gym_auto_actions)) {
                val methodName = method?.let { stringResource(when (it) {
                    HeartRateSharingMethod.TICKR -> R.string.hrs_tickr
                    HeartRateSharingMethod.BLUETOOTH -> R.string.hrs_bluetooth
                    HeartRateSharingMethod.DIRCON -> R.string.dircon_method
                }) }
                StyledToggle(label = stringResource(R.string.gym_auto_hr), checked = rule.hrActionEnabled,
                    description = methodName?.let { stringResource(R.string.gym_auto_hr_desc, it) } ?: stringResource(R.string.gym_auto_hr_none),
                    onCheckedChange = { save(rule.copy(hrActionEnabled = it)) })
                StyledToggle(label = stringResource(R.string.gym_auto_restore), checked = rule.restoreOnExit,
                    description = stringResource(R.string.gym_auto_restore_desc),
                    onCheckedChange = { save(rule.copy(restoreOnExit = it)) })
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

private fun statusText(s: GymAutomationStatus) = when (s) {
    GymAutomationStatus.OFF -> R.string.gym_auto_status_off
    GymAutomationStatus.NEEDS_SETUP -> R.string.gym_auto_status_setup
    GymAutomationStatus.WAITING_ARRIVAL -> R.string.gym_auto_status_waiting_arrival
    GymAutomationStatus.WAITING_AIRPODS -> R.string.gym_auto_status_waiting_airpods
    GymAutomationStatus.ACTIVE -> R.string.gym_auto_status_active
    GymAutomationStatus.PAUSED -> R.string.gym_auto_status_paused
    GymAutomationStatus.LEAVING -> R.string.gym_auto_status_leaving
    GymAutomationStatus.LOCATION_UNAVAILABLE -> R.string.gym_auto_status_unavailable
}
