/*
    EveryPods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 EveryPods contributors

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

@file:OptIn(ExperimentalHazeMaterialsApi::class)

package io.automated.ventures.everypods.presentation.screens

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.data.AirPodsPro3
import io.automated.ventures.everypods.data.Capability
import io.automated.ventures.everypods.data.StemAction
import io.automated.ventures.everypods.presentation.components.AboutCard
import io.automated.ventures.everypods.presentation.components.AppInfoCard
import io.automated.ventures.everypods.presentation.components.AudioSettings
import io.automated.ventures.everypods.presentation.components.CallControlSettings
import io.automated.ventures.everypods.presentation.components.ConnectionSettings
import io.automated.ventures.everypods.presentation.components.DeviceInfoCard
import io.automated.ventures.everypods.presentation.components.MicrophoneSettings
import io.automated.ventures.everypods.presentation.components.NavigationButton
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.components.StyledSlider
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsUiState
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.presentation.viewmodel.AppSettingsUiState
import io.automated.ventures.everypods.presentation.viewmodel.AppSettingsViewModel
import io.automated.ventures.everypods.utils.GymModePrefs
import io.automated.ventures.everypods.utils.GymModeStemPressArbitration
import io.automated.ventures.everypods.utils.GymTimer
import io.automated.ventures.everypods.utils.SleepTimer
import io.automated.ventures.everypods.utils.SmartFeaturesPrefs
import io.automated.ventures.everypods.utils.openEveryPodsIssues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import io.automated.ventures.everypods.utils.openEveryPodsSupportEmail

// ─── Category metadata ────────────────────────────────────────────────────────
internal val categoryTitles = mapOf(
    "controls"    to R.string.category_controls,
    "settings"    to R.string.category_settings,
    "smart"       to R.string.category_smart,
    "appsettings" to R.string.app_settings,
    "audio"       to R.string.category_audio,
    "help"        to R.string.category_help,
)

internal val categoryEmojis = mapOf(
    "controls"    to "🎧",
    "settings"    to "⚙️",
    "smart"       to "✨",
    "appsettings" to "📱",
    "audio"       to "🔊",
    "help"        to "❓",
)

// ─── Entry point ──────────────────────────────────────────────────────────────




/**
 * Registry that lets nested sections register their Y position inside the scrollable column.
 * The owning CategoryScreen reads it to animate-scroll to a target anchor on entry.
 */
internal class AnchorRegistry {
    val positions = mutableStateMapOf<String, Int>()
}

internal val LocalAnchorRegistry = androidx.compose.runtime.compositionLocalOf<AnchorRegistry?> { null }
internal val LocalActiveAnchor = androidx.compose.runtime.compositionLocalOf<String?> { null }

@Composable
fun CategoryScreen(
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    categoryKey: String,
    anchor: String? = null,
) {
    val state    by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()
    val dark     = isSystemInDarkTheme()
    val context  = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("settings", MODE_PRIVATE)
    val title = categoryTitles[categoryKey]?.let { stringResource(it) } ?: categoryKey

    val anchorRegistry = remember { AnchorRegistry() }
    val scrollState = rememberScrollState()
    var didScroll by remember(anchor) { mutableStateOf(false) }

    LaunchedEffect(anchor, anchorRegistry.positions[anchor]) {
        if (anchor == null || didScroll) return@LaunchedEffect
        val y = anchorRegistry.positions[anchor] ?: return@LaunchedEffect
        // Wait one frame so layout settles, then scroll.
        scrollState.animateScrollTo((scrollState.value + y - 200).coerceAtLeast(0))
        didScroll = true
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAnchorRegistry provides anchorRegistry,
        LocalActiveAnchor provides anchor,
    ) {
        StyledScaffold(title = title) { topPadding, hazeState, bottomPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("dest_category/$categoryKey")
                    .hazeSource(hazeState)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(topPadding))
                when (categoryKey) {
                    "controls"    -> ControlsContent(state, viewModel, navController, sharedPrefs, dark)
                    "settings"    -> SettingsContent(state, appState, viewModel, appSettingsViewModel, navController, dark)
                    "smart"       -> SmartContent(state, viewModel, navController, sharedPrefs, dark)
                    "appsettings" -> AppSettingsContent(appState, appSettingsViewModel, navController, dark)
                    "audio"       -> AudioContent(state, appState, viewModel, appSettingsViewModel, navController, dark)
                    "help"        -> HelpContent(state, navController, dark)
                }
                Spacer(Modifier.height(bottomPadding))
            }
        }
    }
}

/**
 * Reusable composable that:
 *  - Registers its Y position in the active AnchorRegistry.
 *  - Briefly pulses a highlight background when its id == active anchor.
 */
@Composable
internal fun AnchorWrapper(
    id: String,
    content: @Composable (Modifier) -> Unit,
) {
    val registry = LocalAnchorRegistry.current
    val active = LocalActiveAnchor.current
    val isTarget = active == id

    // Auto-fade after a brief pulse: trigger a tween back to transparent.
    var pulsed by remember(id, active) { mutableStateOf(false) }
    LaunchedEffect(isTarget) {
        if (isTarget && !pulsed) {
            kotlinx.coroutines.delay(1400)
            pulsed = true
        }
    }
    val animated by animateColorAsState(
        targetValue = if (isTarget && !pulsed) Color(0x550A84FF) else Color.Transparent,
        animationSpec = tween(durationMillis = 700),
        label = "anchorPulse",
    )

    val mod = Modifier
        .onGloballyPositioned { coords ->
            registry?.positions?.set(id, coords.boundsInWindow().top.toInt())
        }
        .testTag("anchor_$id")
        .background(animated, RoundedCornerShape(18.dp))

    content(mod)
}

// ─── 1. AirPods Controls ─────────────────────────────────────────────────────

@Composable
private fun ControlsContent(
    state: AirPodsUiState,
    viewModel: AirPodsViewModel,
    navController: NavController,
    sharedPrefs: SharedPreferences,
    dark: Boolean,
) {
    val capabilities = state.capabilities
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    var selectedBud by rememberSaveable { mutableStateOf("left") }

    fun readAction(key: String, default: StemAction): StemAction =
        runCatching { StemAction.valueOf(sharedPrefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    val pressTypes = listOf(
        Pair(stringResource(R.string.ui_single_press), AACPManager.Companion.StemPressType.SINGLE_PRESS),
        Pair(stringResource(R.string.press_twice), AACPManager.Companion.StemPressType.DOUBLE_PRESS),
        Pair(stringResource(R.string.press_three_times), AACPManager.Companion.StemPressType.TRIPLE_PRESS),
        Pair(stringResource(R.string.press_and_hold_airpods), AACPManager.Companion.StemPressType.LONG_PRESS),
    )

    val actionOptions = listOf(
        StemAction.PLAY_PAUSE              to stringResource(R.string.action_play_pause),
        StemAction.NEXT_TRACK              to stringResource(R.string.action_next_track),
        StemAction.PREVIOUS_TRACK          to stringResource(R.string.action_previous_track),
        StemAction.DIGITAL_ASSISTANT       to stringResource(R.string.digital_assistant),
        StemAction.CYCLE_NOISE_CONTROL_MODES to stringResource(R.string.noise_control),
        StemAction.TOGGLE_GYM_MODE         to stringResource(R.string.action_toggle_gym),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (capabilities.contains(Capability.STEM_CONFIG)) {

            // ── Stem Actions Grid ────────────────────────────────────────────
            ExternalSectionHeader(stringResource(R.string.ui_press_actions), dark, requiresAacp = !state.aacpAvailable)
            Column(
                Modifier.fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BudColumnHeader(
                        imageRes = state.instance?.model?.leftBudsRes ?: R.drawable.airpods_pro_2_left,
                        label = stringResource(R.string.ui_left_bud),
                        selected = selectedBud == "left",
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedBud = "left" }
                    )
                    BudColumnHeader(
                        imageRes = state.instance?.model?.rightBudsRes ?: R.drawable.airpods_pro_2_right,
                        label = stringResource(R.string.ui_right_bud),
                        selected = selectedBud == "right",
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedBud = "right" }
                    )
                }

                pressTypes.forEach { (label, pressType) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Left cell
                        StatefulPressDropdown(
                            side = "left", label = label, pressType = pressType,
                            state = state, viewModel = viewModel,
                            actionOptions = actionOptions,
                            enabled = selectedBud == "left" && state.aacpAvailable,
                            dark = dark, modifier = Modifier.weight(1f),
                            readAction = { k, d -> readAction(k, d) }
                        )
                        // Right cell
                        StatefulPressDropdown(
                            side = "right", label = label, pressType = pressType,
                            state = state, viewModel = viewModel,
                            actionOptions = actionOptions,
                            enabled = selectedBud == "right" && state.aacpAvailable,
                            dark = dark, modifier = Modifier.weight(1f),
                            readAction = { k, d -> readAction(k, d) }
                        )
                    }
                    if (pressType == AACPManager.Companion.StemPressType.LONG_PRESS) {
                        GymLongPressOverrideHints(
                            state = state,
                            sharedPrefs = sharedPrefs,
                            navController = navController,
                            dark = dark,
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(stringResource(R.string.ui_stem_controls_not_available_on), style = captionStyle(dark))
            }
        }

        // ── Listening Mode Configuration ──────────────────────────────────────
        val currentByte = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        Column(Modifier.fillMaxWidth()) {
            MenuSectionHeader(stringResource(R.string.ui_listening_mode_configuration), dark, requiresAacp = !state.aacpAvailable)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.press_and_hold_noise_control_description), style = captionStyle(dark))
                Spacer(Modifier.height(8.dp))
                val lmEnabled = state.aacpAvailable
                StyledSelectList(items = buildList {
                    if (state.offListeningMode) add(SelectItem(stringResource(R.string.off),
                        description = stringResource(R.string.listening_mode_off_description),
                        selected = (currentByte and 0x01) != 0, enabled = lmEnabled,
                        onClick = { viewModel.toggleListeningMode(0x01) }))
                    add(SelectItem(stringResource(R.string.transparency),
                        description = stringResource(R.string.listening_mode_transparency_description),
                        selected = (currentByte and 0x04) != 0, enabled = lmEnabled,
                        onClick = { viewModel.toggleListeningMode(0x04) }))
                    add(SelectItem(stringResource(R.string.adaptive),
                        description = stringResource(R.string.listening_mode_adaptive_description),
                        selected = (currentByte and 0x08) != 0, enabled = lmEnabled,
                        onClick = { viewModel.toggleListeningMode(0x08) }))
                    add(SelectItem(stringResource(R.string.noise_cancellation),
                        description = stringResource(R.string.listening_mode_noise_cancellation_description),
                        selected = (currentByte and 0x02) != 0, enabled = lmEnabled,
                        onClick = { viewModel.toggleListeningMode(0x02) }))
                })
            }
        }

        // ── Call Controls ─────────────────────────────────────────────────────
        val bytes = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
        val flipped = try { bytes[1] == 0x02.toByte() } catch (_: Exception) { false }
        CallControlSettings(hazeState = remember { HazeState() }, flipped = flipped,
            onCallControlValueChanged = {
                viewModel.setControlCommandValue(AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                    if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03))
            })

        // ── Controls Configuration ────────────────────────────────────────────
        ExternalSectionHeader(stringResource(R.string.controls_configuration), dark)
        Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
            ControlsConfigurationContent(
                state = state,
                viewModel = viewModel,
                navController = navController,
                dark = dark,
            )
        }
    }
}

@Composable
internal fun ControlsConfigurationContent(
    state: AirPodsUiState,
    viewModel: AirPodsViewModel,
    navController: NavController,
    dark: Boolean,
) {
    val pressSpeedOptions = listOf(
        0.toByte() to stringResource(R.string.default_option),
        1.toByte() to stringResource(R.string.slower),
        2.toByte() to stringResource(R.string.slowest),
    )
    val pressAndHoldDurationOptions = listOf(
        0.toByte() to stringResource(R.string.default_option),
        1.toByte() to stringResource(R.string.slower),
        2.toByte() to stringResource(R.string.slowest),
    )
    val volumeSwipeSpeedOptions = listOf(
        1.toByte() to stringResource(R.string.default_option),
        2.toByte() to stringResource(R.string.longer),
        3.toByte() to stringResource(R.string.longest),
    )

    fun selectedByte(identifier: AACPManager.Companion.ControlCommandIdentifiers): Byte? =
        state.controlStates[identifier]?.getOrNull(0)

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ConfigSubheader(stringResource(R.string.press_speed), dark)
        Text(stringResource(R.string.press_speed_description), style = captionStyle(dark))
        Spacer(Modifier.height(4.dp))
        StyledSelectList(items = pressSpeedOptions.map { (value, label) ->
            SelectItem(
                label,
                selected = selectedByte(AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL) == value ||
                    (value == 0.toByte() && selectedByte(AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL) == null),
                enabled = state.isPremium && state.aacpAvailable,
                onClick = {
                    viewModel.setControlCommandByte(
                        AACPManager.Companion.ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
                        value
                    )
                }
            )
        })

        MenuDivider()
        ConfigSubheader(stringResource(R.string.press_and_hold_duration), dark)
        Text(stringResource(R.string.press_and_hold_duration_description), style = captionStyle(dark))
        Spacer(Modifier.height(4.dp))
        StyledSelectList(items = pressAndHoldDurationOptions.map { (value, label) ->
            SelectItem(
                label,
                selected = selectedByte(AACPManager.Companion.ControlCommandIdentifiers.CLICK_HOLD_INTERVAL) == value ||
                    (value == 0.toByte() && selectedByte(AACPManager.Companion.ControlCommandIdentifiers.CLICK_HOLD_INTERVAL) == null),
                enabled = state.isPremium && state.aacpAvailable,
                onClick = {
                    viewModel.setControlCommandByte(
                        AACPManager.Companion.ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
                        value
                    )
                }
            )
        })

        MenuDivider()
        StyledToggle(
            label = stringResource(R.string.noise_cancellation_single_airpod),
            description = stringResource(R.string.noise_cancellation_single_airpod_description),
            independent = true,
            checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ONE_BUD_ANC_MODE]?.getOrNull(0) == 0x01.toByte(),
            onCheckedChange = {
                viewModel.setControlCommandBoolean(
                    AACPManager.Companion.ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
                    it
                )
            },
            enabled = state.isPremium && state.aacpAvailable
        )

        val toneVolumeValue =
            state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CHIME_VOLUME]?.getOrNull(0)?.toFloat() ?: 75f
        MenuDivider()
        StyledSlider(
            label = stringResource(R.string.tone_volume),
            description = stringResource(R.string.tone_volume_description),
            value = toneVolumeValue,
            onValueChange = {
                viewModel.setControlCommandValue(
                    AACPManager.Companion.ControlCommandIdentifiers.CHIME_VOLUME,
                    byteArrayOf(it.toInt().toByte(), 0x50)
                )
            },
            valueRange = 0f..100f,
            snapPoints = listOf(75f),
            startIcon = "\uDBC0\uDEA1",
            endIcon = "\uDBC0\uDEA9",
            independent = true,
            enabled = state.isPremium && state.aacpAvailable
        )

        if (state.capabilities.contains(Capability.SWIPE_FOR_VOLUME)) {
            val volumeSwipeEnabled =
                state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_MODE]?.getOrNull(0)?.toInt() == 0x01
            MenuDivider()
            StyledToggle(
                label = stringResource(R.string.volume_control),
                description = stringResource(R.string.volume_control_description),
                checked = volumeSwipeEnabled,
                onCheckedChange = {
                    viewModel.setControlCommandBoolean(
                        AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
                        it
                    )
                },
                enabled = state.isPremium && state.aacpAvailable
            )

            MenuDivider()
            ConfigSubheader(stringResource(R.string.volume_swipe_speed), dark)
            Text(stringResource(R.string.volume_swipe_speed_description), style = captionStyle(dark))
            Spacer(Modifier.height(4.dp))
            StyledSelectList(items = volumeSwipeSpeedOptions.map { (value, label) ->
                SelectItem(
                    label,
                    selected = selectedByte(AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL) == value ||
                        (value == 1.toByte() && selectedByte(AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL) == null),
                    enabled = state.isPremium && state.aacpAvailable,
                    onClick = {
                        viewModel.setControlCommandByte(
                            AACPManager.Companion.ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
                            value
                        )
                    }
                )
            })
        }
    }
}

// ─── 2. AirPods Settings ─────────────────────────────────────────────────────

@Composable
private fun SettingsContent(
    state: AirPodsUiState,
    appState: AppSettingsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    dark: Boolean,
) {
    val capabilities = state.capabilities
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
        val hasPPE = state.instance?.model?.capabilities?.contains(Capability.PPE) == true

        // ── Ungated ───────────────────────────────────────────────────────────
        MenuNavRow(stringResource(R.string.ui_device_name), dark, subtitle = state.deviceName) { navController.navigate("rename") }

        // Conversation Awareness
        val caEnabled = state.controlStates[
            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG
        ]?.getOrNull(0) == 0x01.toByte()
        MenuDivider()
        MenuSectionHeader(stringResource(R.string.conversational_awareness), dark, requiresAacp = !state.aacpAvailable)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            val masterEnabled = state.isPremium && state.aacpAvailable
            StyledToggle(label = stringResource(R.string.conversational_awareness),
                description = stringResource(R.string.conversational_awareness_master_description),
                checked = caEnabled && masterEnabled,
                onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it) },
                independent = true, enabled = masterEnabled)
            Spacer(Modifier.height(4.dp))
            val subEnabled = caEnabled && appState.isPremium && state.aacpAvailable
            StyledToggle(label = stringResource(R.string.conversational_awareness_pause_music),
                description = stringResource(R.string.conversational_awareness_pause_music_description),
                checked = appState.conversationalAwarenessPauseMusicEnabled,
                onCheckedChange = appSettingsViewModel::setConversationalAwarenessPauseMusicEnabled,
                independent = true, enabled = subEnabled)
            Spacer(Modifier.height(4.dp))
            StyledToggle(label = stringResource(R.string.relative_conversational_awareness_volume),
                description = stringResource(R.string.relative_conversational_awareness_volume_description),
                checked = appState.relativeConversationalAwarenessVolumeEnabled,
                onCheckedChange = appSettingsViewModel::setRelativeConversationalAwarenessVolumeEnabled,
                independent = true, enabled = subEnabled)
            Spacer(Modifier.height(4.dp))
            StyledSlider(label = stringResource(R.string.conversational_awareness_volume),
                value = appState.conversationalAwarenessVolume, valueRange = 10f..85f,
                snapPoints = listOf(44f), startLabel = "10%", endLabel = "85%",
                onValueChange = { appSettingsViewModel.setConversationalAwarenessVolume(it) },
                independent = true, enabled = subEnabled)
        }

        // Hearing Protection (PPE)
        if (hasPPE) {
            MenuDivider()
            MenuSectionHeader(stringResource(R.string.hearing_protection), dark, requiresAacp = !state.aacpAvailable)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                StyledToggle(label = stringResource(R.string.ppe),
                    description = stringResource(R.string.workspace_use_description),
                    checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG]?.getOrNull(0)?.toInt() == 1,
                    onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG, it) },
                    independent = true, enabled = state.isPremium && state.aacpAvailable)
            }
        }

        // ── Bluetooth Control ────────────────────────────────────────────────
        MenuDivider()
        MenuSectionHeader(stringResource(R.string.ui_bluetooth_control), dark)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StyledButton(onClick = { viewModel.disconnect() },
                backdrop = rememberLayerBackdrop(), isInteractive = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text(stringResource(R.string.disconnect), style = TextStyle(fontSize = 16.sp,
                    fontWeight = FontWeight.Normal, fontFamily = SfPro, textAlign = TextAlign.Start,
                    color = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ─── 3. Smart Features ───────────────────────────────────────────────────────

@Composable
private fun SmartContent(
    state: AirPodsUiState,
    viewModel: AirPodsViewModel,
    navController: NavController,
    sharedPrefs: SharedPreferences,
    dark: Boolean,
) {
    val context      = LocalContext.current
    val capabilities = state.capabilities
    val cardColor    = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val scope        = rememberCoroutineScope()

    // Gym Mode Card (separate card)
    AnchorWrapper(id = "gym_mode") { highlightMod ->
        Column(Modifier.fillMaxWidth().then(highlightMod).background(cardColor, RoundedCornerShape(18.dp))) {
        var gymModeEnabled by remember { mutableStateOf(GymModePrefs.isEnabled(context)) }
        
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Toggle
            StyledToggle(
                label = stringResource(R.string.ui_gym_mode),
                description = stringResource(R.string.ui_alternate_stem_actions_for_wor),
                checked = gymModeEnabled,
                onCheckedChange = {
                    gymModeEnabled = it
                    GymModePrefs.setEnabled(context, it)
                },
                independent = true
            )
            
            // Gym Timer row (always visible, but disabled when off)
            if (gymModeEnabled) {
                Spacer(Modifier.height(8.dp))
                MenuDivider()
                Spacer(Modifier.height(8.dp))
                
                val gymTimerMode = GymTimer.mode()
                val gymTimerState = when (GymTimer.state()) {
                    GymTimer.State.IDLE -> {
                        val modeLabel = when (gymTimerMode) {
                            GymTimer.Mode.COUNTDOWN -> stringResource(R.string.ui_countdown_ready)
                            GymTimer.Mode.STOPWATCH -> stringResource(R.string.ui_stopwatch_ready)
                            GymTimer.Mode.HIIT -> stringResource(R.string.ui_hiit_ready)
                        }
                        modeLabel
                    }
                    GymTimer.State.PREPARING ->
                        stringResource(R.string.ui_timer_starting_in, GymTimer.preparationSecondsRemaining())
                    GymTimer.State.RUNNING -> {
                        when (gymTimerMode) {
                            GymTimer.Mode.COUNTDOWN -> {
                                val r = GymTimer.countdownRemainingMs() / 1000
                                stringResource(R.string.ui_timer_running, r / 60, r % 60)
                            }
                            GymTimer.Mode.STOPWATCH -> {
                                val e = GymTimer.elapsedMs() / 1000
                                stringResource(R.string.ui_timer_running, e / 60, e % 60)
                            }
                            GymTimer.Mode.HIIT -> {
                                val (phase, round, remaining) = GymTimer.hiitPhaseInfo()
                                val phaseLabel = stringResource(if (phase == GymTimer.Phase.WORK) R.string.ui_timer_phase_work else R.string.ui_timer_phase_rest)
                                stringResource(R.string.ui_timer_running_hiit, phaseLabel, round, remaining / 60000, (remaining % 60000) / 1000)
                            }
                        }
                    }
                    GymTimer.State.PAUSED -> {
                        when (gymTimerMode) {
                            GymTimer.Mode.COUNTDOWN -> {
                                val r = GymTimer.countdownRemainingMs() / 1000
                                stringResource(R.string.ui_timer_paused, r / 60, r % 60)
                            }
                            GymTimer.Mode.STOPWATCH -> {
                                val e = GymTimer.elapsedMs() / 1000
                                stringResource(R.string.ui_timer_paused, e / 60, e % 60)
                            }
                            GymTimer.Mode.HIIT -> {
                                val (phase, round, _) = GymTimer.hiitPhaseInfo()
                                val phaseLabel = stringResource(if (phase == GymTimer.Phase.WORK) R.string.ui_timer_phase_work else R.string.ui_timer_phase_rest)
                                stringResource(R.string.ui_timer_paused_hiit, phaseLabel, round)
                            }
                        }
                    }
                }
                MenuNavRow(stringResource(R.string.ui_gym_timer), dark, subtitle = gymTimerState) { navController.navigate("gym_timer") }
                
                Spacer(Modifier.height(8.dp))
                MenuDivider()
                Spacer(Modifier.height(8.dp))
                
                // Configure Gym Press Actions row
        MenuNavRow(stringResource(R.string.ui_configure_gym_press_actions), dark) { navController.navigate("gym_press_actions") }
            }
        }
        } // end AnchorWrapper Column
    } // end AnchorWrapper

    run {
        val hrSharing by viewModel.heartRateSharingState.collectAsState()
        val hrSession by viewModel.heartRateState.collectAsState()
        Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
            MenuNavRow(stringResource(R.string.hrs_title), dark,
                enabled = state.aacpAvailable,
                subtitle = stringResource(when {
                    hrSharing.recovering -> R.string.hrs_reconnecting
                    hrSharing.error != null || hrSession.failure != null -> R.string.hrs_needs_attention
                    hrSharing.active && hrSharing.wahooDiagnostic -> R.string.hrs_tickr_sending
                    hrSharing.active -> R.string.hrs_ble_ready
                    hrSession.running -> R.string.hrs_monitoring
                    else -> R.string.hrm_stopped
                })) { navController.navigate("heart_rate_screen") }
        }
    }

    // Rest of Smart Features in another outer Column
    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
        // Notification Announcements
        MenuNavRow(stringResource(R.string.ui_notification_announcements), dark) { navController.navigate("notification_announcements") }
        MenuDivider()

        // Find my Case (proximity finder — works even when buds are out of case)
        MenuNavRow(stringResource(R.string.ui_find_my_case), dark, subtitle = stringResource(R.string.ui_find_my_case_description)) { navController.navigate("proximity_finder") }
    }

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
        // Head Gestures
        if (capabilities.contains(Capability.HEAD_GESTURES)) {
            MenuDivider()
            val headOn = sharedPrefs.getBoolean("head_gestures_enabled", false) &&
                (sharedPrefs.getBoolean("head_gestures_answer_call", true) || sharedPrefs.getBoolean("head_gestures_mute_call", true))
            MenuNavRow(stringResource(R.string.ui_head_gestures_status, stringResource(if (headOn) R.string.on else R.string.off)), dark,
                enabled = state.aacpAvailable) { navController.navigate("head_tracking") }
        }

        // Adaptive Audio
        val model = state.instance?.model ?: AirPodsPro3()
        if (model.capabilities.contains(Capability.ADAPTIVE_VOLUME)) {
            MenuDivider()
            MenuSectionHeader(stringResource(R.string.adaptive_audio), dark, requiresAacp = !state.aacpAvailable)
            val adaptiveVal = remember {
                mutableFloatStateOf(100f - (state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toFloat() ?: 50f))
            }
            var adaptiveJob by remember { mutableStateOf<Job?>(null) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                StyledSlider(label = stringResource(R.string.customize_adaptive_audio),
                    value = adaptiveVal.floatValue,
                    onValueChange = {
                        adaptiveVal.floatValue = it
                        adaptiveJob?.cancel()
                        adaptiveJob = scope.launch {
                            delay(150)
                            viewModel.setControlCommandValue(AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
                                byteArrayOf((100 - it).toInt().toByte()))
                        }
                    },
                    valueRange = 0f..100f, snapPoints = listOf(0f, 50f, 100f),
                    startIcon = "􀊥", endIcon = "􀊩", independent = true,
                    description = stringResource(R.string.adaptive_audio_description),
                    enabled = state.isPremium && state.aacpAvailable)
            }
        }

        // Automation
        MenuDivider()
        MenuSectionHeader(stringResource(R.string.ui_automation), dark)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                StyledToggle(label = stringResource(R.string.sleep_detection),
                    checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                    onCheckedChange = { viewModel.setControlCommandBoolean(id, it) },
                    independent = true, enabled = state.isPremium && state.aacpAvailable)
                Spacer(Modifier.height(4.dp))
            }
            StyledToggle(label = stringResource(R.string.optimized_charging),
                description = stringResource(R.string.optimized_charging_description),
                checked = state.dynamicEndOfCharge, onCheckedChange = viewModel::setDynamicEndOfCharge,
                independent = true, enabled = state.aacpAvailable)
        }

        // Resume media + battery alerts (merged into Automation section)
        MenuDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            var autoResume by remember { mutableStateOf(SmartFeaturesPrefs.autoResumeAfterCall(context)) }
            StyledToggle(label = stringResource(R.string.ui_resume_media_after_call), checked = autoResume, independent = true,
                onCheckedChange = { autoResume = it; SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_AUTO_RESUME_AFTER_CALL, it).apply() })
            Spacer(Modifier.height(4.dp))
            var batteryAlerts by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertsEnabled(context)) }
            var batteryThreshold by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertThreshold(context)) }
            StyledToggle(label = stringResource(R.string.ui_speak_when_battery_is_low), checked = batteryAlerts, independent = true,
                description = stringResource(R.string.ui_announces_at_threshold_then_ev_10f37),
                onCheckedChange = { batteryAlerts = it; SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_BATTERY_ALERTS_ENABLED, it).apply() })
            if (batteryAlerts) {
                Spacer(Modifier.height(6.dp))
                // 4 discrete threshold options — no slider
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

        // Sleep Timer
        MenuDivider()
        MenuSectionHeader(stringResource(R.string.ui_sleep_timer), dark)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var sleepRemainingMs by remember { mutableLongStateOf(SleepTimer.remainingMs(context)) }
            DisposableEffect(Unit) {
                val l: () -> Unit = { sleepRemainingMs = SleepTimer.remainingMs(context) }
                SleepTimer.addListener(l); onDispose { SleepTimer.removeListener(l) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { mins ->
                    StyledButton(onClick = { SleepTimer.start(context, mins * 60_000L); sleepRemainingMs = SleepTimer.remainingMs(context) },
                        backdrop = rememberLayerBackdrop(), modifier = Modifier.weight(1f).heightIn(min = 40.dp)) {
                        Text(stringResource(R.string.ui_sleep_preset_minutes, mins), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                    }
                }
            }
            if (sleepRemainingMs > 0L) {
                val mins = (sleepRemainingMs / 60_000L).toInt()
                val secs = ((sleepRemainingMs % 60_000L) / 1000L).toInt()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_sleep_remaining, mins, secs), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.7f) else Color.Black.copy(0.7f)))
                    StyledButton(onClick = { SleepTimer.cancel(context); sleepRemainingMs = 0L }, backdrop = rememberLayerBackdrop(), modifier = Modifier.heightIn(min = 36.dp)) {
                        Text(stringResource(R.string.ui_cancel), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                    }
                }
            } else {
                Text(stringResource(R.string.ui_no_timer_running), style = captionStyle(dark))
            }
        }
    }
}

// ─── 4. App Settings ─────────────────────────────────────────────────────────

@Composable
private fun AppSettingsContent(
    appState: AppSettingsUiState,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    dark: Boolean,
) {
    val context   = LocalContext.current
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
        io.automated.ventures.everypods.presentation.components.AppLanguagePicker()
        MenuDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            StyledToggle(label = stringResource(R.string.show_phone_battery_in_widget),
                description = stringResource(R.string.show_phone_battery_in_widget_description),
                checked = appState.showPhoneBatteryInWidget,
                onCheckedChange = appSettingsViewModel::setShowPhoneBatteryInWidget,
                independent = true, enabled = appState.isPremium)
        }
        MenuDivider()
        MenuSectionHeader(stringResource(R.string.popup_animations), dark)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            StyledToggle(label = stringResource(R.string.show_bottom_sheet_popup),
                description = stringResource(R.string.show_bottom_sheet_popup_description),
                checked = appState.showBottomSheetPopup,
                onCheckedChange = appSettingsViewModel::setShowBottomSheetPopup, independent = true)
            Spacer(Modifier.height(4.dp))
            StyledToggle(label = stringResource(R.string.show_island_popup),
                description = stringResource(R.string.show_island_popup_description),
                checked = appState.showIslandPopup,
                onCheckedChange = appSettingsViewModel::setShowIslandPopup, independent = true)
        }
        MenuDivider()
        // ── Background activity / battery optimization ───────────────────────
        // When the system applies battery optimization to EveryPods, the foreground
        // service can be delayed or killed — handover, BLE proximity, and the boot
        // autostart all become unreliable. Surface the current state and a button
        // to open the system dialog. Same standard API works on Pixel/AOSP and
        // every OEM (Xiaomi, OnePlus, etc.).
        MenuSectionHeader(stringResource(R.string.background_activity), dark)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pm = remember { context.getSystemService(android.os.PowerManager::class.java) }
            // Re-evaluate every time the screen recomposes (user may have changed it).
            var unrestricted by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == true) }
            // Refresh whenever the user returns from the settings dialog.
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        unrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Text(
                stringResource(if (unrestricted) R.string.background_unrestricted else R.string.background_restricted),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SfPro,
                    color = if (unrestricted) Color(0xFF34C759)
                            else Color(0xFFFF9500)
                )
            )
            Text(
                stringResource(if (unrestricted) R.string.background_unrestricted_description else R.string.background_restricted_description),
                style = captionStyle(dark)
            )
            if (!unrestricted) {
                StyledButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = "package:${context.packageName}".toUri()
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            // Fallback: open the general battery-optimization list
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                ) {
                    Text(stringResource(R.string.ui_allow_unrestricted_background),
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SfPro,
                            color = if (dark) Color.White else Color.Black
                        )
                    )
                }
            }
        }
        MenuDivider()
        MenuNavRow(stringResource(R.string.ui_permissions), dark) { navController.navigate("permissions") }
    }
}

// ─── 5. Audio & Connection ───────────────────────────────────────────────────

@Composable
private fun AudioContent(
    state: AirPodsUiState,
    appState: AppSettingsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    dark: Boolean,
) {
    val context   = LocalContext.current
    val m         = state.instance?.model ?: AirPodsPro3()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AudioSettings(navController = navController,
                adaptiveVolumeCapability = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                conversationalAwarenessCapability = m.capabilities.contains(Capability.CONVERSATION_AWARENESS),
                adaptiveAudioCapability = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                customEqCapability = m.capabilities.contains(Capability.CUSTOM_EQ),

                adaptiveVolumeChecked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte(),
                onAdaptiveVolumeCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG, it) },
                conversationalAwarenessChecked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte() && state.isPremium,
                onConversationalAwarenessCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it) },
                isPremium = state.isPremium,
                aacpAvailable = state.aacpAvailable)
        }
        // Connection Settings — shown for both AACP and limited-mode devices.
        // Cross-device handover is the core feature; hiding it on non-AACP devices
        // made the toggle unreachable (connectionSuccessful is false there).
        MenuDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ConnectionSettings(
                crossDeviceEnabled = state.crossDeviceEnabled,
                onCrossDeviceChanged = { viewModel.setCrossDeviceEnabled(it) },
                crossDevicePeers = state.crossDevicePeers,
                navController = navController,
                automaticEarDetectionEnabled = state.automaticEarDetectionEnabled,
                onAutomaticEarDetectionChanged = { viewModel.setAutomaticEarDetectionEnabled(it) },
                automaticConnectionEnabled = state.automaticConnectionEnabled,
                onAutomaticConnectionChanged = { viewModel.setAutomaticConnectionEnabled(it) },
                earDetectionAvailable = state.aacpAvailable,
                lidOpenLastHolderAutoconnect = state.lidOpenLastHolderAutoconnect,
                onLidOpenLastHolderAutoconnectChanged = { viewModel.setLidOpenLastHolderAutoconnect(it) })
        }
        MenuDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
            if (state.aacpAvailable) {
                MicrophoneSettings(hazeState = remember { HazeState() },
                    micModeValue = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(),
                    onMicModeValueChanged = { viewModel.setControlCommandByte(id, it) })
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f).alpha(0.4f)) {
                        MicrophoneSettings(hazeState = remember { HazeState() },
                            micModeValue = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(),
                            onMicModeValueChanged = { /* no-op */ })
                    }
                    io.automated.ventures.everypods.presentation.components.RequiresAacpIcon()
                }
            }
        }
        if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
            MenuDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StyledToggle(label = stringResource(R.string.disconnect_when_not_wearing),
                    description = stringResource(R.string.disconnect_when_not_wearing_description),
                    checked = appState.disconnectWhenNotWearing,
                    onCheckedChange = appSettingsViewModel::setDisconnectWhenNotWearing,
                    independent = true, enabled = appState.isPremium)
            }
        }
        MenuDivider()
        MenuSectionHeader(stringResource(R.string.takeover_phone_state), dark)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            StyledToggle(label = stringResource(R.string.takeover_ringing_call), description = stringResource(R.string.takeover_ringing_call_desc), checked = appState.takeoverWhenRingingCall, onCheckedChange = appSettingsViewModel::setTakeoverWhenRingingCall, independent = true)
            Spacer(Modifier.height(4.dp))
            StyledToggle(label = stringResource(R.string.takeover_media_start), description = stringResource(R.string.takeover_media_start_desc), checked = appState.takeoverWhenMediaStart, onCheckedChange = appSettingsViewModel::setTakeoverWhenMediaStart, independent = true)
        }
    }
}

// ─── 6. Help & Troubleshooting ───────────────────────────────────────────────

@Composable
private fun HelpContent(
    state: AirPodsUiState,
    navController: NavController,
    dark: Boolean,
) {
    val context   = LocalContext.current
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
        // Support EveryPods — first card above Email / GitHub / issues
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) {
                    navController.navigate("purchase_screen")
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.VolunteerActivism,
                contentDescription = null,
                tint = if (dark) Color(0xFF64D2FF) else Color(0xFF0088FF),
                modifier = Modifier.size(26.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.support_everypods_title),
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = SfPro,
                        color = if (dark) Color.White else Color.Black
                    )
                )
                Text(
                    stringResource(R.string.support_everypods_card_subtitle),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontFamily = SfPro,
                        color = if (dark) Color.White.copy(0.55f) else Color.Black.copy(0.55f)
                    )
                )
            }
            Text(
                "›",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontFamily = SfPro,
                    color = if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f)
                )
            )
        }
        MenuDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            AboutCard(navController = navController, modelName = state.modelName,
                actualModel = state.actualModel, serialNumbers = state.serialNumbers,
                version = state.version3)
        }
        MenuDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            DeviceInfoCard()
            Spacer(Modifier.height(8.dp))
            AppInfoCard()
        }
        MenuDivider()
        MenuNavRow(stringResource(R.string.ui_version_info), dark) { navController.navigate("version_info") }
        if (!BuildConfig.PLAY_BUILD) {
            MenuDivider()
            MenuNavRow(stringResource(R.string.ui_troubleshooting), dark) { navController.navigate("debug") }
        }
        MenuDivider()
        MenuNavRow(stringResource(R.string.ui_email_support), dark) {
            context.openEveryPodsSupportEmail()
        }
        MenuNavRow(stringResource(R.string.github_issues), dark) {
            context.openEveryPodsIssues()
        }
        MenuDivider()
        MenuNavRow(stringResource(R.string.open_source_licenses), dark) { navController.navigate("open_source_licenses") }
    }
}

// ─── Shared helper composables for Controls screen ───────────────────────────

@Composable
private fun ExternalSectionHeader(label: String, dark: Boolean, requiresAacp: Boolean = false) {
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SfPro,
                color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
            )
        )
        if (requiresAacp) {
            io.automated.ventures.everypods.presentation.components.RequiresAacpIcon()
        }
    }
}

@Composable
private fun ConfigSubheader(label: String, dark: Boolean) {
    Text(
        label,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
        ),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
internal fun BudColumnHeader(
    imageRes: Int,
    label: String,
    selected: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bgColor  = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val textColor = if (dark) Color.White else Color.Black
    Box(
        modifier = modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color  = if (selected) Color(0xFF0A84FF) else Color.Transparent,
                shape  = RoundedCornerShape(14.dp)
            )
            .background(bgColor, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(min = 46.dp).fillMaxWidth()
            )
            Text(label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                fontFamily = SfPro, color = if (selected) Color(0xFF0A84FF) else textColor))
        }
    }
}

@Composable
private fun PressDropdown(
    label: String,
    currentAction: StemAction,
    options: List<Pair<StemAction, String>>,
    isPremium: Boolean,
    enabled: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (StemAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val actionName = options.find { it.first == currentAction }?.second ?: ""
    val bgColor   = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    val textColor = if (dark) Color.White else Color.Black

    Box(modifier = modifier.alpha(if (enabled) 1f else 0.55f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (enabled) expanded = true
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label,
                style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                color = textColor.copy(alpha = 0.5f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(actionName,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    fontFamily = SfPro, color = textColor), modifier = Modifier.weight(1f))
                Text("▾", style = TextStyle(fontSize = 11.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.4f)))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(if (dark) Color(0xFF2C2C2E) else Color.White)
        ) {
            options.forEach { (action, name) ->
                val optionEnabled = action != StemAction.DIGITAL_ASSISTANT || isPremium
                DropdownMenuItem(
                    text = {
                        Text(name, style = TextStyle(fontSize = 15.sp, fontFamily = SfPro,
                            color = when {
                                !optionEnabled       -> textColor.copy(alpha = 0.35f)
                                action == currentAction -> Color(0xFF0A84FF)
                                else                 -> textColor
                            }))
                    },
                    trailingIcon = if (action == currentAction) {{ Text("✓", style = TextStyle(
                        fontSize = 14.sp, color = Color(0xFF0A84FF))) }} else null,
                    onClick = { if (optionEnabled) { onSelect(action); expanded = false } }
                )
            }
        }
    }
}

/**
 * Per-bud hint under Long Press when Gym Mode is on and that bud has an explicit
 * Gym Press Actions long-press (which then replaces the Controls long-press).
 */
@Composable
private fun GymLongPressOverrideHints(
    state: AirPodsUiState,
    sharedPrefs: SharedPreferences,
    navController: NavController,
    dark: Boolean,
) {
    val context = LocalContext.current
    // Re-read gym prefs whenever they change (Gym Mode toggle / Gym Press Actions edits).
    var prefsTick by remember { mutableStateOf(0) }
    DisposableEffect(sharedPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("gym_")) prefsTick++
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val (leftOverride, rightOverride) = remember(prefsTick, state.leftAction, state.rightAction) {
        val gymOn = GymModePrefs.isEnabled(context)
        GymModeStemPressArbitration.gymLongPressOverridesControls(
            gymOn, state.leftAction, GymModePrefs.getGymLongPressOverride(context, "left")
        ) to GymModeStemPressArbitration.gymLongPressOverridesControls(
            gymOn, state.rightAction, GymModePrefs.getGymLongPressOverride(context, "right")
        )
    }
    if (!leftOverride && !rightOverride) return
    val textColor = if (dark) Color.White else Color.Black
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(leftOverride, rightOverride).forEach { show ->
            Column(Modifier.weight(1f)) {
                if (show) {
                    Text(
                        stringResource(R.string.gym_long_press_overrides_controls_hint),
                        style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                            color = textColor.copy(alpha = 0.55f)),
                    )
                    Text(
                        stringResource(R.string.gym_long_press_open_gym_press_actions),
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            fontFamily = SfPro, color = Color(0xFF0A84FF)),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { navController.navigate("gym_press_actions") },
                    )
                }
            }
        }
    }
}

/**
 * Wrapper that owns the [currentAction] state so it updates immediately on selection
 * without waiting for a recomposition triggered by SharedPreferences changes.
 * [remember(seed)] ensures the value resets if the external source-of-truth changes
 * (e.g. ViewModel emits a new long-press action, or the bud is switched).
 */
@Composable
internal fun StatefulPressDropdown(
    side: String,
    label: String,
    pressType: AACPManager.Companion.StemPressType,
    state: AirPodsUiState,
    viewModel: AirPodsViewModel,
    actionOptions: List<Pair<StemAction, String>>,
    enabled: Boolean,
    dark: Boolean,
    modifier: Modifier,
    readAction: (String, StemAction) -> StemAction,
) {
    val prefKey = io.automated.ventures.everypods.data.StemPressDefaults.prefKey(side, pressType)
    val defaultAction = io.automated.ventures.everypods.data.StemPressDefaults.defaultFor(side, pressType)
    val seed = if (pressType == AACPManager.Companion.StemPressType.LONG_PRESS) {
        if (side == "left") state.leftAction else state.rightAction
    } else {
        readAction(prefKey, defaultAction)
    }
    var currentAction by remember(seed) { mutableStateOf(seed) }
    PressDropdown(
        label = label,
        currentAction = currentAction,
        options = actionOptions,
        isPremium = state.isPremium,
        enabled = enabled,
        dark = dark,
        modifier = modifier,
        onSelect = { action ->
            viewModel.setPressAction(side, pressType, action)
            currentAction = action  // immediate local update — no recompose needed
        }
    )
}
