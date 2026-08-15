package io.automated.ventures.everypods.presentation.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.presentation.components.AudioSettings
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.presentation.viewmodel.AppSettingsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AudioSettingsScreen(
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController
) {
    val state by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()
    
    StyledScaffold(title = "Audio Settings") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_audio_settings")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            AudioSettings(
                navController = navController,
                adaptiveVolumeCapability = false,
                conversationalAwarenessCapability = appState.isPremium,
                adaptiveAudioCapability = state.capabilities.contains(io.automated.ventures.everypods.data.Capability.ADAPTIVE_VOLUME),
                customEqCapability = state.capabilities.contains(io.automated.ventures.everypods.data.Capability.CUSTOM_EQ),
                adaptiveVolumeChecked = false,
                onAdaptiveVolumeCheckedChange = {},
                conversationalAwarenessChecked = appState.conversationalAwarenessPauseMusicEnabled,
                onConversationalAwarenessCheckedChange = appSettingsViewModel::setConversationalAwarenessPauseMusicEnabled,
                isPremium = state.isPremium,
                aacpAvailable = state.aacpAvailable
            )
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
