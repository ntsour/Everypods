package io.automated.ventures.everypods.presentation.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.data.*
import io.automated.ventures.everypods.utils.HeadGesturePrefs

/** Observe the entire settings snapshot so conflict clearing and resets update all rows atomically. */
@Composable
fun rememberHeadGestureSettings(): Pair<SharedPreferences, HeadGestureSettings> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var settings by remember(prefs) { mutableStateOf(HeadGesturePrefs.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("head_gesture")) settings = HeadGesturePrefs.read(prefs)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return prefs to settings
}

@Composable
fun headGestureLabel(gesture: HeadGesture): String = stringResource(
    if (gesture == HeadGesture.NOD) R.string.hg_nod else R.string.hg_shake)

@Composable
fun controlActionLabel(action: StemAction?): String = stringResource(when (action) {
    null -> R.string.hg_do_nothing
    StemAction.PLAY_PAUSE -> R.string.action_play_pause
    StemAction.NEXT_TRACK -> R.string.action_next_track
    StemAction.PREVIOUS_TRACK -> R.string.action_previous_track
    StemAction.DIGITAL_ASSISTANT -> R.string.digital_assistant
    StemAction.GOOGLE_VOICE_COMMAND -> R.string.action_google_voice_command
    StemAction.CYCLE_NOISE_CONTROL_MODES -> R.string.noise_control
    StemAction.MUTE_CALL -> R.string.mute_unmute
    StemAction.TOGGLE_GYM_MODE -> R.string.action_toggle_gym
    StemAction.GYM_TIMER_START_STOP -> R.string.action_timer_start_stop
    StemAction.GYM_TIMER_LAP -> R.string.action_timer_lap
    StemAction.GYM_TIMER_RESET -> R.string.action_timer_reset
})

/** Shared settings typography matches the Android UI contract, independent of dynamic colors. */
@Composable
fun gestureLabelStyle() = TextStyle(fontFamily = FontFamily(Font(R.font.sf_pro)), fontSize = 16.sp,
    fontWeight = FontWeight.Normal, color = if (isSystemInDarkTheme()) Color.White else Color.Black)

@Composable
fun gestureSupportingStyle() = gestureLabelStyle().copy(fontSize = 13.sp,
    color = gestureLabelStyle().color.copy(alpha = 0.6f))

/** One inline settings row; the dropdown is a modal, not a nested settings card. */
@Composable
fun <T> GestureSettingPicker(title: String, value: T, options: List<Pair<T, String>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second.orEmpty()
    val labelStyle = gestureLabelStyle()
    val supportingStyle = gestureSupportingStyle()
    val dark = isSystemInDarkTheme()
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title: $selectedLabel" }
            .clickable(enabled = enabled, role = Role.Button) { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = labelStyle.copy(color = labelStyle.color.copy(alpha = if (enabled) 1f else .45f)))
                Text(selectedLabel, style = supportingStyle)
            }
            Text("⌄", style = supportingStyle, modifier = Modifier.padding(horizontal = 4.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp).background(if (dark) Color(0xFF2C2C2E) else Color.White)) {
            options.forEach { (choice, label) ->
                DropdownMenuItem(text = { Text(label, style = labelStyle) }, enabled = enabled,
                    trailingIcon = if (choice == value) {{ Text("✓", style = labelStyle.copy(color = Color(0xFF0A84FF))) }} else null,
                    onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}

@Composable
fun GestureSettingAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, style = gestureLabelStyle().copy(color = if (enabled) Color(0xFF0A84FF) else gestureSupportingStyle().color),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp))
}

@Composable
fun HeadGestureActions(gym: Boolean = false, enabled: Boolean = true, onMasterChange: ((Boolean) -> Unit)? = null, onOpenGymControls: (() -> Unit)? = null) {
    val (prefs, settings) = rememberHeadGestureSettings()
    var gymActive by remember(prefs) { mutableStateOf(prefs.getBoolean("gym_mode_enabled", false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "gym_mode_enabled") gymActive = prefs.getBoolean("gym_mode_enabled", false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsSection(stringResource(R.string.ui_head_gestures)) {
        if (!gym) StyledToggle(
            label = stringResource(R.string.ui_head_gestures),
            checked = settings.enabled,
            onCheckedChange = { onMasterChange?.invoke(it) ?: prefs.edit().putBoolean("head_gestures_enabled", it).apply() },
            enabled = enabled || settings.enabled,
            description = stringResource(R.string.ui_master_switch_for_all_head_ges),
        )
        if (!gym) StyledToggle(
            label = stringResource(R.string.hg_stop_announcements),
            description = stringResource(R.string.hg_stop_announcements_help),
            checked = settings.shakeStopsAnnouncements,
            onCheckedChange = { HeadGesturePrefs.setShakeStopsAnnouncements(prefs, it) },
            enabled = enabled,
        )
        val actions = listOf<StemAction?>(null) + StemAction.entries
        val options = actions.map { it to controlActionLabel(it) }
        HeadGesture.entries.forEach { gesture ->
            GestureSettingPicker(headGestureLabel(gesture), (if (gym) settings.gym else settings.normal)[gesture], options,
                enabled = enabled, onSelect = { HeadGesturePrefs.setAction(prefs, gesture, gym, it) })
        }
    }
    if (!gym && gymActive) {
        Text(stringResource(R.string.hg_gym_active_hint), style = gestureSupportingStyle(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onOpenGymControls != null) GestureSettingAction(stringResource(R.string.hg_gym_controls), onClick = onOpenGymControls)
    }
    // The notice flag is independent of mappings, so retain separate observable state.
    var showNotice by remember { mutableStateOf(prefs.getBoolean(HeadGesturePrefs.NOTICE, false)) }
    if (!gym && showNotice) {
        Text(stringResource(R.string.hg_migration_notice), style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        GestureSettingAction(stringResource(R.string.hg_dismiss)) {
            prefs.edit().putBoolean(HeadGesturePrefs.NOTICE, false).apply(); showNotice = false
        }
    }
}

@Composable
fun HeadGestureCallPicker(action: HeadGestureCallAction, enabled: Boolean = true) {
    val (prefs, settings) = rememberHeadGestureSettings()
    val options = listOf<Pair<HeadGesture?, String>>(null to stringResource(R.string.hg_none)) +
        HeadGesture.entries.map { it to headGestureLabel(it) }
    GestureSettingPicker(stringResource(R.string.hg_head_gesture), settings.calls[action], options, enabled) {
        HeadGesturePrefs.setCall(prefs, action, it)
    }
}
