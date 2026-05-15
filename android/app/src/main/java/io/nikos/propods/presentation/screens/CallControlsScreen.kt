package io.nikos.propods.presentation.screens

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
import io.nikos.propods.bluetooth.AACPManager
import io.nikos.propods.presentation.components.CallControlSettings
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun CallControlsScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    
    val bytes = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
    val flipped = try { bytes[1] == 0x02.toByte() } catch (_: Exception) { false }
    
    StyledScaffold(title = "Call Controls") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            CallControlSettings(
                hazeState = remember { HazeState() },
                flipped = flipped,
                onCallControlValueChanged = {
                    viewModel.setControlCommandValue(
                        AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                        if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03)
                    )
                }
            )
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
