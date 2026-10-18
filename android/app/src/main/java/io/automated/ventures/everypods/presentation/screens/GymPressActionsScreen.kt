/*
    EveryPods - AirPods liberated from Apple's ecosystem
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

package io.automated.ventures.everypods.presentation.screens

import androidx.compose.ui.res.stringResource

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.data.StemAction
import io.automated.ventures.everypods.utils.GymModeStemPressArbitration
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GymPressActionsScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    var selectedBud by rememberSaveable { mutableStateOf("left") }
    // Bumped on Reset so dropdown labels re-seed from prefs.
    var prefsEpoch by remember { mutableStateOf(0) }

    fun readGymAction(key: String, default: StemAction): StemAction =
        runCatching { StemAction.valueOf(sharedPrefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    val pressTypes = listOf(
        Triple(stringResource(R.string.press_twice), AACPManager.Companion.StemPressType.DOUBLE_PRESS,  StemAction.GYM_TIMER_START_STOP),
        Triple(stringResource(R.string.press_three_times), AACPManager.Companion.StemPressType.TRIPLE_PRESS,  StemAction.GYM_TIMER_LAP),
        Triple(stringResource(R.string.press_and_hold_airpods),   AACPManager.Companion.StemPressType.LONG_PRESS,    StemAction.GYM_TIMER_RESET),
    )

    val actionOptions = listOf(
        StemAction.PLAY_PAUSE              to stringResource(R.string.action_play_pause),
        StemAction.NEXT_TRACK              to stringResource(R.string.action_next_track),
        StemAction.PREVIOUS_TRACK          to stringResource(R.string.action_previous_track),
        StemAction.CYCLE_NOISE_CONTROL_MODES to stringResource(R.string.noise_control),
        // Gym Mode On/Off is owned by AirPods Controls long-press only (per bud).
        StemAction.GYM_TIMER_START_STOP    to stringResource(R.string.action_timer_start_stop),
        StemAction.GYM_TIMER_LAP           to stringResource(R.string.action_timer_lap),
        StemAction.GYM_TIMER_RESET         to stringResource(R.string.action_timer_reset),
    )
    // Display names for the Controls long-press shown in "Same as Controls: …".
    val controlsActionNames = mapOf(
        StemAction.PLAY_PAUSE                to stringResource(R.string.action_play_pause),
        StemAction.NEXT_TRACK                to stringResource(R.string.action_next_track),
        StemAction.PREVIOUS_TRACK            to stringResource(R.string.action_previous_track),
        StemAction.DIGITAL_ASSISTANT         to stringResource(R.string.digital_assistant),
        StemAction.CYCLE_NOISE_CONTROL_MODES to stringResource(R.string.noise_control),
        StemAction.TOGGLE_GYM_MODE           to stringResource(R.string.action_toggle_gym),
    )

    StyledScaffold(title = stringResource(R.string.ui_gym_press_actions)) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_gym_press_actions")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BudColumnHeader(
                        imageRes = R.drawable.airpods_pro_2_left,
                        label = stringResource(R.string.ui_left_bud),
                        selected = selectedBud == "left",
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedBud = "left" }
                    )
                    BudColumnHeader(
                        imageRes = R.drawable.airpods_pro_2_right,
                        label = stringResource(R.string.ui_right_bud),
                        selected = selectedBud == "right",
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedBud = "right" }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Single Press — fixed
                Column(Modifier.fillMaxWidth().background(if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.ui_single_press), style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                        color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
                    Text(stringResource(R.string.ui_play_pause_always_controls_med), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        fontFamily = SfPro, color = if (dark) Color.White.copy(0.4f) else Color.Black.copy(0.4f)))
                }

                Spacer(Modifier.height(8.dp))

                val controlsLongKey =
                    if (selectedBud == "left") "left_long_press_action" else "right_long_press_action"
                val controlsLongDefault = io.automated.ventures.everypods.data.StemPressDefaults
                    .defaultFor(selectedBud, AACPManager.Companion.StemPressType.LONG_PRESS)
                val controlsLongPress = runCatching {
                    StemAction.valueOf(
                        sharedPrefs.getString(controlsLongKey, controlsLongDefault.name)
                            ?: controlsLongDefault.name
                    )
                }.getOrDefault(controlsLongDefault)
                val longPressLockedByControls =
                    GymModeStemPressArbitration.isGymLongPressLockedByControls(controlsLongPress)
                // Unset gym long-press (key absent) behaves as the Controls long-press.
                val sameAsControlsLabel = stringResource(
                    R.string.gym_long_press_same_as_controls,
                    controlsActionNames[controlsLongPress] ?: controlsLongPress.name,
                )

                pressTypes.forEach { (label, pressType, defaultAction) ->
                    val prefKey = "gym_${selectedBud}_${pressType.name.lowercase()}_action"
                    val isLong = pressType == AACPManager.Companion.StemPressType.LONG_PRESS
                    val locked = isLong && longPressLockedByControls
                    val seedAction: StemAction? = when {
                        locked -> StemAction.TOGGLE_GYM_MODE
                        // null = "Same as Controls"; never seeded into prefs implicitly.
                        isLong && !sharedPrefs.contains(prefKey) -> null
                        else -> readGymAction(prefKey, defaultAction)
                    }
                    val options: List<Pair<StemAction?, String>> = when {
                        locked -> listOf(StemAction.TOGGLE_GYM_MODE to stringResource(R.string.action_toggle_gym))
                        isLong -> listOf<Pair<StemAction?, String>>(null to sameAsControlsLabel) + actionOptions
                        else -> actionOptions
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            StatefulGymPressDropdown(
                                seedKey = "$prefsEpoch-$selectedBud-$label-$locked",
                                seedAction = seedAction,
                                label = label,
                                options = options,
                                dark = dark,
                                enabled = !locked,
                                lockedHint = if (locked) {
                                    "Set in AirPods Controls for this bud"
                                } else null,
                                onSelect = { action ->
                                    if (action == null) {
                                        // "Same as Controls": remove the key so the bud
                                        // follows its Controls long-press again.
                                        sharedPrefs.edit().remove(prefKey).apply()
                                    } else {
                                        sharedPrefs.edit().putString(prefKey, action.name).apply()
                                        viewModel.setGymPressAction(selectedBud, pressType, action)
                                    }
                                }
                            )
                        }
                    }
                    if (label != stringResource(R.string.press_and_hold_airpods)) Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Reset to defaults
            Row(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                sharedPrefs.edit()
                    .putString("gym_left_double_press_action", StemAction.GYM_TIMER_START_STOP.name)
                    .putString("gym_right_double_press_action", StemAction.GYM_TIMER_START_STOP.name)
                    .putString("gym_left_triple_press_action", StemAction.GYM_TIMER_LAP.name)
                    .putString("gym_right_triple_press_action", StemAction.GYM_TIMER_LAP.name)
                    // Long press defaults to "Same as Controls" (key absent), not timer reset.
                    .remove("gym_left_long_press_action")
                    .remove("gym_right_long_press_action")
                    .apply()
                prefsEpoch++
            }.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ui_reset_to_defaults), style = bodyStyle(dark))
                Text("􀅉", style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f)))
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

/**
 * Owns [currentAction] so the label updates immediately on selection without
 * waiting for a SharedPreferences-driven recomposition.
 */
@Composable
private fun StatefulGymPressDropdown(
    seedKey: String,
    seedAction: StemAction?,
    label: String,
    options: List<Pair<StemAction?, String>>,
    dark: Boolean,
    enabled: Boolean,
    lockedHint: String?,
    onSelect: (StemAction?) -> Unit,
) {
    var currentAction by remember(seedKey) { mutableStateOf(seedAction) }
    GymPressDropdown(
        label = label,
        currentAction = currentAction,
        options = options,
        dark = dark,
        enabled = enabled,
        lockedHint = lockedHint,
        onSelect = { action ->
            currentAction = action
            onSelect(action)
        },
    )
}

@Composable
private fun GymPressDropdown(
    label: String,
    currentAction: StemAction?,
    options: List<Pair<StemAction?, String>>,
    dark: Boolean,
    enabled: Boolean = true,
    lockedHint: String? = null,
    onSelect: (StemAction?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val actionName = options.find { it.first == currentAction }?.second ?: ""
    val bgColor = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val textColor = if (dark) Color.White else Color.Black

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .background(bgColor, RoundedCornerShape(12.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = true }
                } else Modifier
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label,
            style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                color = textColor.copy(alpha = 0.5f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(actionName,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        fontFamily = SfPro, color = textColor))
                if (lockedHint != null) {
                    Text(
                        lockedHint,
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontFamily = SfPro,
                            color = textColor.copy(alpha = 0.45f),
                        ),
                    )
                }
            }
            if (enabled) {
                Text("▾", style = TextStyle(fontSize = 11.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.4f)))
            }
        }
    }
    if (enabled) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(if (dark) Color(0xFF2C2C2E) else Color.White)
        ) {
            options.forEach { (action, name) ->
                DropdownMenuItem(
                    text = {
                        Text(name, style = TextStyle(fontSize = 15.sp, fontFamily = SfPro,
                            color = if (action == currentAction) Color(0xFF0A84FF) else textColor))
                    },
                    trailingIcon = if (action == currentAction) {{ Text("✓", style = TextStyle(
                        fontSize = 14.sp, color = Color(0xFF0A84FF))) }} else null,
                    onClick = { onSelect(action); expanded = false }
                )
            }
        }
    }
}
