/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

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

package me.kavishdevar.librepods.presentation.screens

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GymPressActionsScreen(viewModel: AirPodsViewModel) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    var selectedBud by rememberSaveable { mutableStateOf("left") }

    fun readGymAction(key: String, default: StemAction): StemAction =
        runCatching { StemAction.valueOf(sharedPrefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    val pressTypes = listOf(
        Triple("Double Press", AACPManager.Companion.StemPressType.DOUBLE_PRESS,  StemAction.GYM_TIMER_START_STOP),
        Triple("Triple Press", AACPManager.Companion.StemPressType.TRIPLE_PRESS,  StemAction.GYM_TIMER_LAP),
        Triple("Long Press",   AACPManager.Companion.StemPressType.LONG_PRESS,    StemAction.GYM_TIMER_RESET),
    )

    val actionOptions = listOf(
        StemAction.PLAY_PAUSE              to "Play / Pause",
        StemAction.NEXT_TRACK              to "Next Track",
        StemAction.PREVIOUS_TRACK          to "Prev. Track",
        StemAction.CYCLE_NOISE_CONTROL_MODES to "Listening Mode",
        StemAction.GYM_TIMER_START_STOP    to "Timer Start / Stop",
        StemAction.GYM_TIMER_LAP           to "Timer Lap",
        StemAction.GYM_TIMER_RESET         to "Timer Reset",
    )

    StyledScaffold(title = "Gym Press Actions") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BudColumnHeader(
                        imageRes = R.drawable.airpods_pro_2_left,
                        label = "Left Bud",
                        selected = selectedBud == "left",
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedBud = "left" }
                    )
                    BudColumnHeader(
                        imageRes = R.drawable.airpods_pro_2_right,
                        label = "Right Bud",
                        selected = selectedBud == "right",
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedBud = "right" }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Single Press — fixed
                Column(Modifier.fillMaxWidth().background(if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("Single Press", style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                        color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
                    Text("Play / Pause — always controls media", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        fontFamily = SfPro, color = if (dark) Color.White.copy(0.4f) else Color.Black.copy(0.4f)))
                }

                Spacer(Modifier.height(8.dp))

                pressTypes.forEach { (label, pressType, defaultAction) ->
                    val prefKey = "gym_${selectedBud}_${pressType.name.lowercase()}_action"
                    val currentAction = readGymAction(prefKey, defaultAction)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            GymPressDropdown(
                                label = label,
                                currentAction = currentAction,
                                options = actionOptions,
                                dark = dark,
                                onSelect = { action ->
                                    sharedPrefs.edit().putString(prefKey, action.name).apply()
                                    viewModel.setGymPressAction(selectedBud, pressType, action)
                                }
                            )
                        }
                    }
                    if (label != "Long Press") Spacer(Modifier.height(8.dp))
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
                    .putString("gym_left_long_press_action", StemAction.GYM_TIMER_RESET.name)
                    .putString("gym_right_long_press_action", StemAction.GYM_TIMER_RESET.name)
                    .apply()
            }.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Reset to defaults", style = bodyStyle(dark))
                Text("􀅉", style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f)))
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun GymPressDropdown(
    label: String,
    currentAction: StemAction,
    options: List<Pair<StemAction, String>>,
    dark: Boolean,
    onSelect: (StemAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val actionName = options.find { it.first == currentAction }?.second ?: ""
    val bgColor = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val textColor = if (dark) Color.White else Color.Black

    Column(
        Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                expanded = true
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                color = textColor.copy(alpha = 0.5f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(actionName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    fontFamily = SfPro, color = textColor), modifier = Modifier.weight(1f))
            Text("▾", style = TextStyle(fontSize = 11.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.4f)))
        }
    }
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
