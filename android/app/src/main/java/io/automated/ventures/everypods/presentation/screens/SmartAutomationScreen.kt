package io.automated.ventures.everypods.presentation.screens

import io.automated.ventures.everypods.R

import androidx.compose.ui.res.stringResource

import android.content.Context
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.data.Capability
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.utils.SmartFeaturesPrefs

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SmartAutomationScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val capabilities = state.capabilities
    
    StyledScaffold(title = stringResource(R.string.ui_automation)) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_smart_automation")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                    val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                    StyledToggle(label = stringResource(R.string.ui_sleep_detection),
                        checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                        onCheckedChange = { viewModel.setControlCommandBoolean(id, it) },
                        independent = false, enabled = state.isPremium && state.aacpAvailable)
                    Spacer(Modifier.height(12.dp))
                }
                
                StyledToggle(label = stringResource(R.string.ui_optimized_charging),
                    description = stringResource(R.string.ui_slows_charging_past_80_to_pres),
                    checked = state.dynamicEndOfCharge, onCheckedChange = viewModel::setDynamicEndOfCharge, independent = false)
                Spacer(Modifier.height(12.dp))
                
                var autoResume by remember { mutableStateOf(SmartFeaturesPrefs.autoResumeAfterCall(context)) }
                StyledToggle(label = stringResource(R.string.ui_resume_media_after_call), checked = autoResume, independent = false,
                    onCheckedChange = { autoResume = it; SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_AUTO_RESUME_AFTER_CALL, it).apply() })
                Spacer(Modifier.height(12.dp))
                
                var batteryAlerts by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertsEnabled(context)) }
                var batteryThreshold by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertThreshold(context)) }
                StyledToggle(label = stringResource(R.string.ui_speak_when_battery_is_low), checked = batteryAlerts, independent = false,
                    description = stringResource(R.string.ui_announces_at_threshold_then_ev),
                    onCheckedChange = { batteryAlerts = it; SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_BATTERY_ALERTS_ENABLED, it).apply() })
                if (batteryAlerts) {
                    Spacer(Modifier.height(6.dp))
                    StyledSelectList(items = listOf(10, 20, 30, 40).map { pct ->
                        SelectItem(
                            name = "$pct%",
                            selected = batteryThreshold == pct,
                            onClick = {
                                batteryThreshold = pct
                                SmartFeaturesPrefs.prefs(context).edit()
                                    .putInt(SmartFeaturesPrefs.KEY_BATTERY_ALERT_THRESHOLD, pct).apply()
                            }
                        )
                    })
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
