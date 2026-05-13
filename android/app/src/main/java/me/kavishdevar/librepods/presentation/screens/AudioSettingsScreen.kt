package me.kavishdevar.librepods.presentation.screens

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
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.presentation.components.AudioSettings
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel

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
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            AudioSettings(
                navController = navController,
                adaptiveVolumeCapability = false,
                conversationalAwarenessCapability = appState.isPremium,
                loudSoundReductionCapability = state.capabilities.contains(me.kavishdevar.librepods.data.Capability.LOUD_SOUND_REDUCTION),
                adaptiveAudioCapability = state.capabilities.contains(me.kavishdevar.librepods.data.Capability.ADAPTIVE_VOLUME),
                adaptiveVolumeChecked = false,
                onAdaptiveVolumeCheckedChange = {},
                conversationalAwarenessChecked = appState.conversationalAwarenessPauseMusicEnabled,
                onConversationalAwarenessCheckedChange = appSettingsViewModel::setConversationalAwarenessPauseMusicEnabled,
                loudSoundReductionChecked = state.loudSoundReductionEnabled,
                onLoudSoundReductionCheckedChange = { if (it) viewModel.setATTCharacteristicValue(me.kavishdevar.librepods.bluetooth.ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(1)) else viewModel.setATTCharacteristicValue(me.kavishdevar.librepods.bluetooth.ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(0)) },
                vendorIdHook = state.vendorIdHook,
                isPremium = state.isPremium
            )
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
