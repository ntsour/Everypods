/*
    ProPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 ProPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.R
import io.nikos.propods.bluetooth.AACPManager
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.components.StyledSelectList
import io.nikos.propods.presentation.components.SelectItem
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun VolumeControlScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    // Extract volume swipe speed options from ControlsConfigurationContent in CategoryScreen.kt
    val volumeSwipeSpeedOptions = listOf(
        1.toByte() to stringResource(R.string.default_option),
        2.toByte() to stringResource(R.string.longer),
        3.toByte() to stringResource(R.string.longest),
    )

    fun selectedByte(identifier: AACPManager.Companion.ControlCommandIdentifiers): Byte? =
        state.controlStates[identifier]?.getOrNull(0)

    StyledScaffold(title = "Volume Control") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_controls_configuration")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Volume Swipe Speed", style = TextStyle(
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro,
                    color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
                ))
                Text(stringResource(R.string.volume_swipe_speed_description), style = captionStyle(dark))
                Spacer(Modifier.height(8.dp))
                StyledSelectList(items = volumeSwipeSpeedOptions.map { (value, label) ->
                    SelectItem(
                        label,
                        selected = selectedByte(AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL) == value ||
                            (value == 1.toByte() && selectedByte(AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL) == null),
                        enabled = state.isPremium,
                        onClick = {
                            viewModel.setControlCommandByte(
                                AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
                                value
                            )
                        }
                    )
                })
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}
