/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import io.automated.ventures.everypods.automation.GymLocationPermission
import io.automated.ventures.everypods.automation.GymZone
import io.automated.ventures.everypods.automation.GymRadiusSliderState
import io.automated.ventures.everypods.automation.GymZoneEdits
import androidx.compose.runtime.rememberUpdatedState
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingMethod
import io.automated.ventures.everypods.bluetooth.hrm.HeartRateSharingPrefs
import io.automated.ventures.everypods.presentation.components.SettingsSection
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSlider
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.utils.GymModePrefs
import kotlinx.coroutines.delay
import java.util.Locale

/** Editor for the gym automation: shared actions plus a list of up to 10 locations. */
@SuppressLint("MissingPermission")
@Composable
fun GymAutomationScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var rule by remember { mutableStateOf(GymAutomationRepository.rule(context)) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var fine by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(false) }
    // The user tapped "Enable" before "Allow all the time" was granted; finish once it is.
    var pendingEnable by rememberSaveable { mutableStateOf(false) }
    val status by GymAutomationCoordinator.status.collectAsState()
    val method = remember(rule) { HeartRateSharingPrefs.configuredMethod(context) }

    fun recheck() {
        val step = GymLocationPermission.step(context)
        fine = step != GymLocationPermission.Step.NEED_PRECISE
        background = step == GymLocationPermission.Step.GRANTED
        val current = GymAutomationRepository.rule(context)
        if (GymLocationPermission.shouldCompleteEnable(pendingEnable, step, current.hasLocation)) {
            pendingEnable = false
            if (!current.enabled) {
                rule = GymAutomationRepository.saveRule(context, current.copy(enabled = true))
                GymAutomationCoordinator.dispatch(context, Event.RuleChanged)
                GymGeofenceRegistrar.sync(context)
            }
        }
        GymAutomationCoordinator.refreshStatus(context)
    }
    /**
     * Applies [change] to the freshly stored rule (debounced callers may run late), then
     * re-syncs the state machine and geofences only if something registration-relevant
     * changed. Renames never touch geofences or the visit.
     */
    fun update(change: (GymAutomationRule) -> GymAutomationRule) {
        val before = GymAutomationRepository.rule(context)
        val after = GymAutomationRepository.saveRule(context, change(before))
        rule = after
        if (after.enabled != before.enabled || after.zoneRevisions != before.zoneRevisions ||
            after.hrActionEnabled != before.hrActionEnabled || after.restoreOnExit != before.restoreOnExit) {
            GymAutomationCoordinator.dispatch(context, Event.RuleChanged)
            GymGeofenceRegistrar.sync(context)
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Re-check after returning from system settings; never assume a grant.
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { recheck(); GymGeofenceRegistrar.sync(context) } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Live: zones/settings written elsewhere (coordinator, debug hook) show up immediately.
    DisposableEffect(context) {
        val stop = GymAutomationRepository.observeRule(context) { rule = it }
        onDispose { stop() }
    }
    val requestLocation = rememberGymLocationPermissionAction { granted ->
        recheck()
        if (!granted) message = R.string.gym_auto_permission_denied
    }

    val editing = rule.zone(editingId)
    BackHandler(enabled = editing != null) { editingId = null }

    StyledScaffold(title = stringResource(R.string.gym_auto_title)) { topPadding, hazeState, _ ->
        Column(
            Modifier.fillMaxSize().hazeSource(hazeState).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            if (editing != null) {
                val index = rule.zones.indexOf(editing)
                ZoneEditor(
                    zone = editing, displayName = zoneDisplayName(editing, index), fine = fine, dark = dark,
                    start = GymZoneEdits.startPosition(null, rule),
                    onBack = { editingId = null },
                    onEdit = { change -> update(change) },
                    onRemove = {
                        update { r -> r.copy(zones = r.zones.filterNot { it.id == editing.id }) }
                        editingId = null
                    },
                )
            } else {
                Text(stringResource(statusText(status)), style = bodyStyle(dark), modifier = Modifier.padding(vertical = 8.dp))
                if (status == GymAutomationStatus.PAUSED) {
                    GymButton(stringResource(R.string.gym_auto_resume)) { GymAutomationCoordinator.dispatch(context, Event.ResumeVisit) }
                } else if (status == GymAutomationStatus.ACTIVE) {
                    GymButton(stringResource(R.string.gym_auto_stop)) { GymModePrefs.setEnabled(context, false) }
                }
                SettingsSection(stringResource(R.string.gym_auto_title)) {
                    StyledToggle(label = stringResource(R.string.gym_auto_enable), checked = rule.enabled,
                        description = if (!rule.hasLocation || !background) stringResource(R.string.gym_auto_status_setup) else null,
                        enabled = rule.hasLocation,
                        onCheckedChange = { on ->
                            if (on && !background) { pendingEnable = true; requestLocation() }
                            else { pendingEnable = false; update { it.copy(enabled = on) } }
                        })
                }
                SettingsSection(stringResource(R.string.gym_auto_permissions)) {
                    Text(stringResource(R.string.gym_auto_disclosure), style = bodyStyle(dark), modifier = Modifier.padding(16.dp))
                    if (!fine) GymButton(stringResource(R.string.gym_auto_grant_precise), onClick = requestLocation)
                    else if (!background) {
                        Text(stringResource(R.string.gym_auto_while_using), style = bodyStyle(dark), modifier = Modifier.padding(horizontal = 16.dp))
                        GymButton(stringResource(R.string.gym_auto_grant_background), onClick = requestLocation)
                    }
                    message?.let { Text(stringResource(it), style = bodyStyle(dark), modifier = Modifier.padding(16.dp)) }
                    Spacer(Modifier.height(8.dp))
                }
                SettingsSection(stringResource(R.string.gym_zones_title)) {
                    if (rule.zones.isEmpty()) {
                        Text(stringResource(R.string.gym_zone_empty), style = bodyStyle(dark), modifier = Modifier.padding(16.dp))
                    }
                    rule.zones.forEachIndexed { i, z ->
                        ZoneRow(zoneDisplayName(z, i), zoneSummary(z), dark) { editingId = z.id }
                    }
                    if (rule.canAddZone) {
                        val defaultNameTemplate = stringResource(R.string.gym_zone_default_name)
                        GymButton(stringResource(R.string.gym_zone_add), modifier = Modifier.padding(vertical = 8.dp)) {
                            val number = rule.zones.size + 1
                            val zone = GymZone(name = String.format(Locale.getDefault(), defaultNameTemplate, number))
                            update { it.copy(zones = it.zones + zone) }
                            editingId = zone.id
                        }
                    } else {
                        Text(stringResource(R.string.gym_zone_cap, GymAutomationRule.MAX_ZONES), style = bodyStyle(dark),
                            modifier = Modifier.padding(16.dp))
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
                        onCheckedChange = { on -> update { it.copy(hrActionEnabled = on) } })
                    StyledToggle(label = stringResource(R.string.gym_auto_restore), checked = rule.restoreOnExit,
                        description = stringResource(R.string.gym_auto_restore_desc),
                        onCheckedChange = { on -> update { it.copy(restoreOnExit = on) } })
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ZoneEditor(
    zone: GymZone,
    displayName: String,
    fine: Boolean,
    dark: Boolean,
    start: Pair<Double, Double>,
    onBack: () -> Unit,
    onEdit: ((GymAutomationRule) -> GymAutomationRule) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember(zone.id) { mutableStateOf(zone.name) }
    var center by remember(zone.id) { mutableStateOf(if (zone.hasLocation) zone.latitude!! to zone.longitude!! else null) }
    var latText by remember(zone.id) { mutableStateOf(zone.latitude?.let(::coord).orEmpty()) }
    var lonText by remember(zone.id) { mutableStateOf(zone.longitude?.let(::coord).orEmpty()) }
    var typed by remember(zone.id) { mutableStateOf(false) }
    var move by remember(zone.id) { mutableStateOf<GymMapMove?>(null) }
    val slider = remember(zone.id) { GymRadiusSliderState(zone.radiusMeters) }
    // What this editor last wrote or accepted from the store, so our own echoes are ignored
    // and changes made elsewhere (debug hook, another screen) show up live.
    var savedName by remember(zone.id) { mutableStateOf(zone.name) }
    var savedPos by remember(zone.id) { mutableStateOf(zone.latitude to zone.longitude) }
    val latestZone by rememberUpdatedState(zone)
    val id = zone.id
    var note by remember(zone.id) { mutableStateOf<Int?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val cancel = remember { CancellationTokenSource() }
    DisposableEffect(Unit) { onDispose { cancel.cancel() } }
    val step = slider.meters

    // Debounced, always-persistent edits: no save button anywhere. Each write changes only
    // its own field of the latest stored zone.
    LaunchedEffect(id, name) {
        val target = name.trim().take(60)
        if (target != savedName) { delay(500); savedName = target; onEdit { GymZoneEdits.withName(it, id, name) } }
    }
    LaunchedEffect(id, step) {
        val target = slider.pendingWrite() ?: return@LaunchedEffect
        delay(400); slider.markPersisted(target); onEdit { GymZoneEdits.withRadius(it, id, target) }
    }
    // Live updates from elsewhere.
    LaunchedEffect(id, zone.radiusMeters) { slider.onStored(zone.radiusMeters) }
    LaunchedEffect(id, zone.name) { if (zone.name != savedName) { savedName = zone.name; name = zone.name } }
    LaunchedEffect(id, zone.latitude, zone.longitude) {
        val la = zone.latitude; val lo = zone.longitude
        if ((la to lo) != savedPos) {
            savedPos = la to lo
            if (la != null && lo != null) {
                center = la to lo; latText = coord(la); lonText = coord(lo); typed = false
                move = GymMapMove(la, lo, persist = false)
            }
        }
    }
    // A zone without a position opens near the current location when one is cached.
    LaunchedEffect(id) {
        if (!zone.hasLocation && fine) runCatching {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !latestZone.hasLocation) move = GymMapMove(loc.latitude, loc.longitude, persist = false)
            }
        }
    }
    LaunchedEffect(zone.id, latText, lonText, typed) {
        if (!typed) return@LaunchedEffect
        delay(800)
        val lat = latText.trim().replace(',', '.').toDoubleOrNull()
        val lon = lonText.trim().replace(',', '.').toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) note = R.string.gym_auto_invalid_coordinates
        else { note = null; typed = false; move = GymMapMove(lat, lon) }
    }

    GymButton(stringResource(R.string.gym_zone_back), modifier = Modifier.padding(vertical = 8.dp), onClick = onBack)
    SettingsSection(displayName) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.gym_zone_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp))
        Text(
            center?.let { (la, lo) -> stringResource(R.string.gym_zone_pin_at, coord(la), coord(lo)) }
                ?: stringResource(R.string.gym_zone_no_position),
            style = bodyStyle(dark), modifier = Modifier.padding(horizontal = 16.dp))
        GymMapPicker(zone.latitude ?: start.first, zone.longitude ?: start.second, step, move,
            startIsZone = zone.hasLocation,
            onCenterChanged = { la, lo -> center = la to lo; if (!typed) { latText = coord(la); lonText = coord(lo) } },
            onCenterSettled = { la, lo ->
                if (GymZoneEdits.shouldPersistCenter(true, latestZone, la, lo)) {
                    savedPos = la to lo
                    onEdit { GymZoneEdits.withPosition(it, id, la, lo) }
                }
            },
        )
        GymButton(stringResource(R.string.gym_auto_use_current), enabled = fine) {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                .addOnSuccessListener { loc ->
                    val ageMs = loc?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
                    if (loc == null || !loc.hasAccuracy() || loc.accuracy > 75f || (ageMs ?: Long.MAX_VALUE) > 120_000) {
                        note = R.string.gym_auto_fix_failed
                    } else { note = null; move = GymMapMove(loc.latitude, loc.longitude) }
                }
                .addOnFailureListener { note = R.string.gym_auto_fix_failed }
        }
        OutlinedTextField(latText, { latText = it; typed = true }, label = { Text(stringResource(R.string.gym_auto_latitude)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        OutlinedTextField(lonText, { lonText = it; typed = true }, label = { Text(stringResource(R.string.gym_auto_longitude)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        note?.let { Text(stringResource(it), style = bodyStyle(dark), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        Spacer(Modifier.height(8.dp))
    }
    StyledSlider(
        label = stringResource(R.string.gym_auto_radius, step),
        value = slider.raw,
        onValueChange = slider::onValueChange,
        valueRange = GymAutomationRule.MIN_RADIUS_M.toFloat()..GymAutomationRule.MAX_RADIUS_M.toFloat(),
        startLabel = stringResource(R.string.gym_auto_radius_value, GymAutomationRule.MIN_RADIUS_M),
        endLabel = stringResource(R.string.gym_auto_radius_value, GymAutomationRule.MAX_RADIUS_M),
        description = if (step < GymAutomationRule.MIN_GEOFENCE_RADIUS_M)
            stringResource(R.string.gym_auto_radius_hint) + "\n" + stringResource(R.string.gym_auto_radius_small_warning)
            else stringResource(R.string.gym_auto_radius_hint),
        independent = true,
    )
    GymButton(stringResource(R.string.gym_zone_remove), modifier = Modifier.padding(vertical = 8.dp)) { confirmRemove = true }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.gym_zone_remove_title, displayName)) },
            text = { Text(stringResource(R.string.gym_zone_remove_body)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text(stringResource(R.string.gym_zone_remove_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.gym_zone_cancel)) } },
        )
    }
}

@Composable
private fun ZoneRow(name: String, summary: String, dark: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = bodyStyle(dark).copy(fontWeight = FontWeight.SemiBold))
            Text(summary, style = bodyStyle(dark))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.gym_zone_edit),
            tint = bodyStyle(dark).color)
    }
}

/** One button style for the whole Gym screen: full width, same height, shape and weight. */
@Composable
private fun GymButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
    ) { Text(text, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium) }
}

@Composable
private fun zoneDisplayName(z: GymZone, index: Int) =
    z.name.ifBlank { stringResource(R.string.gym_zone_default_name, index + 1) }

@Composable
private fun zoneSummary(z: GymZone): String {
    val radius = stringResource(R.string.gym_auto_radius_value, z.radiusMeters)
    return if (z.hasLocation) stringResource(R.string.gym_zone_summary, radius, coord(z.latitude!!), coord(z.longitude!!))
        else stringResource(R.string.gym_zone_summary_no_position, radius)
}

/** Coordinates are machine values (dot decimal) so they round-trip through the text fields. */
private fun coord(v: Double) = String.format(Locale.ROOT, "%.5f", v)

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
