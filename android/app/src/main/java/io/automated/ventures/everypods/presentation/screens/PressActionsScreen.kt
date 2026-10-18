package io.automated.ventures.everypods.presentation.screens

import androidx.compose.ui.res.stringResource

import android.content.SharedPreferences
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.data.Capability
import io.automated.ventures.everypods.data.StemAction
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PressActionsScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    
    var selectedBud by rememberSaveable { mutableStateOf("left") }
    
    fun readAction(key: String, default: StemAction): StemAction =
        runCatching { StemAction.valueOf(sharedPrefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)
    
    val pressTypes = listOf(
        Pair(stringResource(R.string.ui_single_press), AACPManager.Companion.StemPressType.SINGLE_PRESS),
        Pair(stringResource(R.string.press_twice), AACPManager.Companion.StemPressType.DOUBLE_PRESS),
        Pair(stringResource(R.string.press_three_times), AACPManager.Companion.StemPressType.TRIPLE_PRESS),
        Pair(stringResource(R.string.press_and_hold_airpods), AACPManager.Companion.StemPressType.LONG_PRESS),
    )
    
    val actionOptions = listOf(
        StemAction.PLAY_PAUSE              to stringResource(R.string.action_play_pause),
        StemAction.NEXT_TRACK              to stringResource(R.string.action_next_track),
        StemAction.PREVIOUS_TRACK          to stringResource(R.string.action_previous_track),
        StemAction.DIGITAL_ASSISTANT       to stringResource(R.string.digital_assistant),
        StemAction.GOOGLE_VOICE_COMMAND    to stringResource(R.string.action_google_voice_command),
        StemAction.CYCLE_NOISE_CONTROL_MODES to stringResource(R.string.noise_control),
        StemAction.TOGGLE_GYM_MODE         to stringResource(R.string.action_toggle_gym),
    )
    
    StyledScaffold(title = stringResource(R.string.ui_press_actions)) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_press_actions")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            if (state.capabilities.contains(Capability.STEM_CONFIG)) {
                Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BudColumnHeader(
                            imageRes = state.instance?.model?.leftBudsRes ?: R.drawable.airpods_pro_2_left,
                            label = stringResource(R.string.ui_left_bud),
                            selected = selectedBud == "left",
                            dark = dark,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedBud = "left" }
                        )
                        BudColumnHeader(
                            imageRes = state.instance?.model?.rightBudsRes ?: R.drawable.airpods_pro_2_right,
                            label = stringResource(R.string.ui_right_bud),
                            selected = selectedBud == "right",
                            dark = dark,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedBud = "right" }
                        )
                    }
                    
                    pressTypes.forEach { (label, pressType) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatefulPressDropdown(
                                side = "left", label = label, pressType = pressType,
                                state = state, viewModel = viewModel,
                                actionOptions = actionOptions, enabled = selectedBud == "left",
                                dark = dark, modifier = Modifier.weight(1f),
                                readAction = { k, d -> readAction(k, d) }
                            )
                            StatefulPressDropdown(
                                side = "right", label = label, pressType = pressType,
                                state = state, viewModel = viewModel,
                                actionOptions = actionOptions, enabled = selectedBud == "right",
                                dark = dark, modifier = Modifier.weight(1f),
                                readAction = { k, d -> readAction(k, d) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ui_voice_assistant_behavior_depen),
                    style = captionStyle(dark),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text(stringResource(R.string.ui_stem_controls_not_available_on), style = captionStyle(dark))
                }
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}
