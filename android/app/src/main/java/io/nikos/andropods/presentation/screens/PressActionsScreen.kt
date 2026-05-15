package io.nikos.andropods.presentation.screens

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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.andropods.R
import io.nikos.andropods.bluetooth.AACPManager
import io.nikos.andropods.data.Capability
import io.nikos.andropods.data.StemAction
import io.nikos.andropods.presentation.components.StyledScaffold
import io.nikos.andropods.presentation.viewmodel.AirPodsViewModel

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
        Triple("Single Press", AACPManager.Companion.StemPressType.SINGLE_PRESS,  StemAction.PLAY_PAUSE),
        Triple("Double Press", AACPManager.Companion.StemPressType.DOUBLE_PRESS,  StemAction.NEXT_TRACK),
        Triple("Triple Press", AACPManager.Companion.StemPressType.TRIPLE_PRESS,  StemAction.PREVIOUS_TRACK),
        Triple("Long Press",   AACPManager.Companion.StemPressType.LONG_PRESS,    StemAction.CYCLE_NOISE_CONTROL_MODES),
    )
    
    val actionOptions = listOf(
        StemAction.PLAY_PAUSE              to "Play / Pause",
        StemAction.NEXT_TRACK              to "Next Track",
        StemAction.PREVIOUS_TRACK          to "Prev. Track",
        StemAction.DIGITAL_ASSISTANT       to "Voice Assistant",
        StemAction.CYCLE_NOISE_CONTROL_MODES to "Listening Mode",
    )
    
    StyledScaffold(title = "Press Actions") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                            label = "Left Bud",
                            selected = selectedBud == "left",
                            dark = dark,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedBud = "left" }
                        )
                        BudColumnHeader(
                            imageRes = state.instance?.model?.rightBudsRes ?: R.drawable.airpods_pro_2_right,
                            label = "Right Bud",
                            selected = selectedBud == "right",
                            dark = dark,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedBud = "right" }
                        )
                    }
                    
                    pressTypes.forEach { (label, pressType, defaultAction) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatefulPressDropdown(
                                side = "left", label = label, pressType = pressType,
                                defaultAction = defaultAction, state = state, viewModel = viewModel,
                                actionOptions = actionOptions, enabled = selectedBud == "left",
                                dark = dark, modifier = Modifier.weight(1f),
                                readAction = { k, d -> readAction(k, d) }
                            )
                            StatefulPressDropdown(
                                side = "right", label = label, pressType = pressType,
                                defaultAction = defaultAction, state = state, viewModel = viewModel,
                                actionOptions = actionOptions, enabled = selectedBud == "right",
                                dark = dark, modifier = Modifier.weight(1f),
                                readAction = { k, d -> readAction(k, d) }
                            )
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("Stem controls not available on this model.", style = captionStyle(dark))
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
