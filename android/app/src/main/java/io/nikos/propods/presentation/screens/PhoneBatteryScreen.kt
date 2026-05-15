package io.nikos.propods.presentation.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.R
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.components.StyledToggle
import io.nikos.propods.presentation.viewmodel.AppSettingsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PhoneBatteryScreen(appSettingsViewModel: AppSettingsViewModel) {
    val appState by appSettingsViewModel.uiState.collectAsState()
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    
    StyledScaffold(title = "Battery in Widget") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                StyledToggle(label = stringResource(R.string.show_phone_battery_in_widget),
                    description = stringResource(R.string.show_phone_battery_in_widget_description),
                    checked = appState.showPhoneBatteryInWidget,
                    onCheckedChange = appSettingsViewModel::setShowPhoneBatteryInWidget,
                    independent = true, enabled = appState.isPremium)
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
