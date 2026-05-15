package io.nikos.propods.presentation.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.R
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.components.StyledToggle
import io.nikos.propods.presentation.viewmodel.AppSettingsViewModel
import io.nikos.propods.utils.XposedState

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun XposedSettingsScreen(appSettingsViewModel: AppSettingsViewModel) {
    val appState by appSettingsViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    
    StyledScaffold(title = "Act as Apple Device") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_xposed_settings")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    val restartMsg = stringResource(R.string.found_offset_restart_bluetooth)
                    StyledToggle(
                        label = stringResource(R.string.act_as_an_apple_device) + " (${stringResource(R.string.requires_xposed)})",
                        description = stringResource(R.string.act_as_an_apple_device_description),
                        checked = appState.vendorIdHook,
                        onCheckedChange = { Toast.makeText(context, restartMsg, Toast.LENGTH_SHORT).show(); appSettingsViewModel.setVendorIdHook(it) },
                        independent = true, enabled = appState.isPremium)
                }
            } else {
                Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("Xposed module not available", style = bodyStyle(dark))
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
