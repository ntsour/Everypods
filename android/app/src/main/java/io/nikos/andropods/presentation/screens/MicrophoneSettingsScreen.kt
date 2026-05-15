package io.nikos.andropods.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.andropods.presentation.components.MicrophoneSettings
import io.nikos.andropods.presentation.components.StyledScaffold
import io.nikos.andropods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MicrophoneSettingsScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    
    StyledScaffold(title = "Microphone Settings") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            MicrophoneSettings(
                hazeState = remember { HazeState() },
                micModeValue = state.controlStates[io.nikos.andropods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE]?.getOrNull(0) ?: 0x00,
                onMicModeValueChanged = { viewModel.setControlCommandByte(io.nikos.andropods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE, it) }
            )
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
