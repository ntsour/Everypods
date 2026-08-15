package io.automated.ventures.everypods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ListeningModeConfigScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val currentByte = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
    
    StyledScaffold(title = "Listening Mode Configuration") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_listening_mode_config")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(stringResource(R.string.press_and_hold_noise_control_description), style = captionStyle(dark))
                Spacer(Modifier.height(8.dp))
                StyledSelectList(items = buildList {
                    if (state.offListeningMode) add(SelectItem(stringResource(R.string.off),
                        description = stringResource(R.string.listening_mode_off_description),
                        selected = (currentByte and 0x01) != 0,
                        onClick = { viewModel.toggleListeningMode(0x01) }))
                    add(SelectItem(stringResource(R.string.transparency),
                        description = stringResource(R.string.listening_mode_transparency_description),
                        selected = (currentByte and 0x04) != 0,
                        onClick = { viewModel.toggleListeningMode(0x04) }))
                    add(SelectItem(stringResource(R.string.adaptive),
                        description = stringResource(R.string.listening_mode_adaptive_description),
                        selected = (currentByte and 0x08) != 0,
                        onClick = { viewModel.toggleListeningMode(0x08) }))
                    add(SelectItem(stringResource(R.string.noise_cancellation),
                        description = stringResource(R.string.listening_mode_noise_cancellation_description),
                        selected = (currentByte and 0x02) != 0,
                        onClick = { viewModel.toggleListeningMode(0x02) }))
                })
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
