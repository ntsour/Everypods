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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.ui.layout.positionInWindow
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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
import io.automated.ventures.everypods.utils.GymTimer
import io.automated.ventures.everypods.utils.SleepTimer
import io.automated.ventures.everypods.utils.SmartFeaturesPrefs
import io.automated.ventures.everypods.utils.openEveryPodsIssues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import io.automated.ventures.everypods.presentation.components.SettingsSection
import io.automated.ventures.everypods.presentation.components.SettingsGroup
import io.automated.ventures.everypods.presentation.components.SettingsHeading
import io.automated.ventures.everypods.utils.openEveryPodsSupportEmail

// ─── Category metadata ────────────────────────────────────────────────────────
internal val categoryTitles = mapOf(
    "controls"    to "Controls",
    "connection"  to "Connection",
    "smart"       to "Smart features",
    "appsettings" to "App settings",
    "audio"       to "Audio",
    "help"        to "Help",
)

internal val categoryEmojis = mapOf(
    "controls"    to "🎧",
    "connection"  to "🔗",
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
    initialBud: String = "left",
) {
    val state    by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()
    val dark     = isSystemInDarkTheme()
    val context  = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("settings", MODE_PRIVATE)
    val title = when (categoryKey) {
        "controls" -> stringResource(R.string.search_category_controls)
        "settings", "connection" -> stringResource(R.string.design_connection)
        "smart" -> stringResource(R.string.search_category_smart)
        "appsettings" -> stringResource(R.string.search_category_app)
        "audio" -> stringResource(R.string.search_category_audio)
        "help" -> stringResource(R.string.search_category_help)
        else -> categoryKey
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetTop = with(density) { 112.dp.roundToPx() }
    val anchorRegistry = remember(categoryKey) { AnchorRegistry() }
    val scrollState = rememberScrollState()
    var didScroll by remember(categoryKey, anchor) { mutableStateOf(false) }

    LaunchedEffect(anchor) {
        if (anchor == null || didScroll) return@LaunchedEffect
        // Wait for layout to report this anchor once. Do not key this effect to the
        // measured position: that position changes during scrolling and would
        // cancel/restart animateScrollTo on every frame.
        val y = snapshotFlow { anchorRegistry.positions[anchor] }
            .filterNotNull()
            .first()
        scrollState.animateScrollTo((scrollState.value + y - targetTop).coerceAtLeast(0))
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
                    "controls"    -> ControlsContent(state, viewModel, navController, sharedPrefs, dark, initialBud)
                    "settings", "connection" -> ConnectionContent(state, appState, viewModel, appSettingsViewModel, navController, dark)
                    "smart"       -> SmartContent(state, viewModel, navController, sharedPrefs, dark)
                    "appsettings" -> AppSettingsContent(appState, appSettingsViewModel, navController, dark)
                    "audio"       -> AudioContent(state, appState, viewModel, appSettingsViewModel, navController, dark)
                    "help"        -> HelpContent(state, navController, dark)
                }
                Spacer(Modifier.height(if (anchor != null) androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp else bottomPadding))
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
            // boundsInWindow clips off-screen sections to an empty rectangle at
            // zero. The unclipped origin keeps below-the-fold anchors reachable.
            registry?.positions?.set(id, coords.positionInWindow().y.toInt())
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
    initialBud: String = "left",
) {
    val capabilities = state.capabilities
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    var selectedBud by rememberSaveable(initialBud) { mutableStateOf(initialBud) }

    fun readAction(key: String, default: StemAction): StemAction =
        runCatching { StemAction.valueOf(sharedPrefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    val pressTypes = listOf(
        Triple("Single Press", AACPManager.Companion.StemPressType.SINGLE_PRESS,  StemAction.PLAY_PAUSE),
        Triple("Double Press", AACPManager.Companion.StemPressType.DOUBLE_PRESS,  StemAction.NEXT_TRACK),
        Triple("Triple Press", AACPManager.Companion.StemPressType.TRIPLE_PRESS,  StemAction.PREVIOUS_TRACK),
        Triple("Long Press",   AACPManager.Companion.StemPressType.LONG_PRESS,    StemAction.CYCLE_NOISE_CONTROL_MODES),
    )

    val actionOptions = listOf(
        StemAction.PLAY_PAUSE              to stringResource(R.string.action_play_pause),
        StemAction.NEXT_TRACK              to stringResource(R.string.action_next_track),
        StemAction.PREVIOUS_TRACK          to stringResource(R.string.action_previous_track),
        StemAction.DIGITAL_ASSISTANT       to stringResource(R.string.digital_assistant),
        StemAction.GOOGLE_VOICE_COMMAND    to stringResource(R.string.action_google_voice_command),
        StemAction.CYCLE_NOISE_CONTROL_MODES to stringResource(R.string.noise_control),
        StemAction.TOGGLE_GYM_MODE         to stringResource(R.string.action_toggle_gym),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (capabilities.contains(Capability.STEM_CONFIG)) {

            // ── Stem Actions Grid ────────────────────────────────────────────
            ExternalSectionHeader(stringResource(R.string.design_stem_actions), dark, requiresAacp = !state.aacpAvailable)
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

                pressTypes.forEach { (label, pressType, defaultAction) ->
                    AnchorWrapper("stem_${pressType.name}") { gestureModifier ->
                    Row(gestureModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Left cell
                        StatefulPressDropdown(
                            side = "left", label = label, pressType = pressType,
                            defaultAction = defaultAction, state = state, viewModel = viewModel,
                            actionOptions = actionOptions,
                            enabled = selectedBud == "left" && state.aacpAvailable,
                            dark = dark, modifier = Modifier.weight(1f),
                            readAction = { k, d -> readAction(k, d) }
                        )
                        // Right cell
                        StatefulPressDropdown(
                            side = "right", label = label, pressType = pressType,
                            defaultAction = defaultAction, state = state, viewModel = viewModel,
                            actionOptions = actionOptions,
                            enabled = selectedBud == "right" && state.aacpAvailable,
                            dark = dark, modifier = Modifier.weight(1f),
                            readAction = { k, d -> readAction(k, d) }
                        )
                    }
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
        AnchorWrapper("listening_modes") { anchorModifier -> Column(anchorModifier.fillMaxWidth()) {
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
        } }

        // ── Call Controls ─────────────────────────────────────────────────────
        val bytes = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
        val flipped = try { bytes[1] == 0x02.toByte() } catch (_: Exception) { false }
        AnchorWrapper("call_controls") { modifier -> Box(modifier) {
        CallControlSettings(hazeState = remember { HazeState() }, flipped = flipped,
            onCallControlValueChanged = {
                viewModel.setControlCommandValue(AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                    if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03))
            })

        } }
        // ── Controls Configuration ────────────────────────────────────────────
        AnchorWrapper("controls_configuration") { anchorModifier ->
            Column(anchorModifier.fillMaxWidth()) {
                ControlsConfigurationContent(
                    state = state,
                    viewModel = viewModel,
                    navController = navController,
                    dark = dark,
                )
            }
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

    Column(Modifier.fillMaxWidth()) {
        TargetSection("press_speed", stringResource(R.string.press_speed)) {
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

        }
        TargetSection("hold_duration", stringResource(R.string.press_and_hold_duration)) {
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

        }
        TargetSection("listening_controls", stringResource(R.string.design_listening_controls)) {
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

        }
        if (state.capabilities.contains(Capability.SWIPE_FOR_VOLUME)) {
            TargetSection("volume_control", stringResource(R.string.volume_control)) {
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

            }
            TargetSection("volume_swipe_speed", stringResource(R.string.volume_swipe_speed)) {
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
    Column(Modifier.fillMaxWidth()) {
        val hasPPE = state.instance?.model?.capabilities?.contains(Capability.PPE) == true
        // Conversation Awareness
        val caEnabled = state.controlStates[
            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG
        ]?.getOrNull(0) == 0x01.toByte()
        AnchorWrapper("conversation_awareness") { anchorModifier ->
        SettingsSection(stringResource(R.string.conversational_awareness), anchorModifier) {
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

        }
        // Hearing Protection (PPE)
        if (hasPPE) {
            AnchorWrapper("hearing_protection") { anchorModifier ->
            SettingsSection(stringResource(R.string.hearing_protection), anchorModifier) {
                StyledToggle(label = stringResource(R.string.ppe),
                    description = stringResource(R.string.workspace_use_description),
                    checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG]?.getOrNull(0)?.toInt() == 1,
                    onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG, it) },
                    independent = true, enabled = state.isPremium && state.aacpAvailable)
            }
        }
        }
    }
}

// ─── 3. Smart Features ───────────────────────────────────────────────────────

@Composable
private fun SmartContent(state: AirPodsUiState, viewModel: AirPodsViewModel, navController: NavController,
    sharedPrefs: SharedPreferences, dark: Boolean) {
    val context = LocalContext.current
    AnchorWrapper("gym_mode") { modifier ->
        SettingsSection(stringResource(R.string.ui_gym_mode), modifier) {
            var enabled by remember { mutableStateOf(GymModePrefs.isEnabled(context)) }
            StyledToggle(label = stringResource(R.string.ui_gym_mode),
                description = stringResource(R.string.ui_alternate_stem_actions_for_wor), checked = enabled,
                onCheckedChange = { enabled = it; GymModePrefs.setEnabled(context, it) })
            MenuNavRow(stringResource(R.string.ui_gym_timer), dark) { navController.navigate("gym_timer") }
            MenuNavRow(stringResource(R.string.ui_configure_gym_press_actions), dark) { navController.navigate("gym_press_actions") }
        }
    }
    AnchorWrapper("heart_rate") { modifier -> SettingsSection(stringResource(R.string.design_smart_tools), modifier) {
        MenuNavRow(stringResource(R.string.hrs_title), dark) { navController.navigate("heart_rate_screen") }
        MenuDivider()
        MenuNavRow(stringResource(R.string.ui_notification_announcements), dark) { navController.navigate("notification_announcements") }
        MenuDivider()
        MenuNavRow(stringResource(R.string.ui_find_my_case), dark, subtitle = stringResource(R.string.ui_find_my_case_description)) { navController.navigate("proximity_finder") }
        if (state.capabilities.contains(Capability.HEAD_GESTURES)) {
            MenuDivider()
            MenuNavRow(stringResource(R.string.ui_head_gestures), dark, enabled = state.aacpAvailable) { navController.navigate("head_tracking") }
        }
    } }
    AnchorWrapper("automation") { modifier -> SettingsSection(stringResource(R.string.ui_automation), modifier) {
        if (state.capabilities.contains(Capability.SLEEP_DETECTION)) {
            val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
            StyledToggle(label = stringResource(R.string.sleep_detection),
                checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                onCheckedChange = { viewModel.setControlCommandBoolean(id, it) }, enabled = state.isPremium && state.aacpAvailable)
        }
        AnchorWrapper("sleep_timer") { timerModifier ->
            Column(timerModifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.ui_sleep_timer),
                    style = bodyStyle(dark),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
                SleepTimerSettings()
            }
        }
        StyledToggle(label = stringResource(R.string.optimized_charging), description = stringResource(R.string.optimized_charging_description),
            checked = state.dynamicEndOfCharge, onCheckedChange = viewModel::setDynamicEndOfCharge, enabled = state.aacpAvailable)
        var resume by remember { mutableStateOf(SmartFeaturesPrefs.autoResumeAfterCall(context)) }
        StyledToggle(label = stringResource(R.string.ui_resume_media_after_call), checked = resume,
            onCheckedChange = { resume = it; SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_AUTO_RESUME_AFTER_CALL, it).apply() })
    } }
    AnchorWrapper("battery_alert_threshold") { modifier -> SettingsSection(stringResource(R.string.design_battery_alerts), modifier) {
        var enabled by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertsEnabled(context)) }
        var threshold by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertThreshold(context)) }
        StyledToggle(label = stringResource(R.string.ui_speak_when_battery_is_low), checked = enabled,
            description = stringResource(R.string.ui_announces_at_threshold_then_ev_10f37),
            onCheckedChange = { enabled = it; SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_BATTERY_ALERTS_ENABLED, it).apply() })
        if (enabled) StyledSelectList(items = listOf(10,20,30,40).map { percent ->
            SelectItem("$percent%", selected = threshold == percent, onClick = {
                threshold = percent; SmartFeaturesPrefs.prefs(context).edit().putInt(SmartFeaturesPrefs.KEY_BATTERY_ALERT_THRESHOLD, percent).apply()
            })
        })
    } }
}



@Composable
private fun AdaptiveAudioContent(state: AirPodsUiState, viewModel: AirPodsViewModel, dark: Boolean) {
    val scope = rememberCoroutineScope()
        // Adaptive Audio
        val model = state.instance?.model ?: AirPodsPro3()
        if (model.capabilities.contains(Capability.ADAPTIVE_VOLUME)) {
            val adaptiveVal = remember {
                mutableFloatStateOf(100f - (state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toFloat() ?: 50f))
            }
            var adaptiveJob by remember { mutableStateOf<Job?>(null) }
            AnchorWrapper("adaptive_audio") { anchorModifier -> SettingsSection(stringResource(R.string.customize_adaptive_audio), anchorModifier) {
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
        } }

 }

// ─── 4. App Settings ─────────────────────────────────────────────────────────

@Composable
private fun AppSettingsContent(appState: AppSettingsUiState, appSettingsViewModel: AppSettingsViewModel,
    navController: NavController, dark: Boolean) {
    AnchorWrapper("app_language") { modifier -> Column(modifier.fillMaxWidth()) { AppLanguageSetting() } }
    AnchorWrapper("phone_battery") { modifier -> SettingsSection(stringResource(R.string.design_widget), modifier) {
        StyledToggle(label = stringResource(R.string.show_phone_battery_in_widget), description = stringResource(R.string.show_phone_battery_in_widget_description),
            checked = appState.showPhoneBatteryInWidget, onCheckedChange = appSettingsViewModel::setShowPhoneBatteryInWidget, enabled = appState.isPremium)
    } }
    AnchorWrapper("popup_animations") { modifier -> SettingsSection(stringResource(R.string.ui_pop_up_animations), modifier) {
        StyledToggle(label = stringResource(R.string.show_bottom_sheet_popup), description = stringResource(R.string.show_bottom_sheet_popup_description),
            checked = appState.showBottomSheetPopup, onCheckedChange = appSettingsViewModel::setShowBottomSheetPopup)
        MenuDivider()
        StyledToggle(label = stringResource(R.string.show_island_popup), description = stringResource(R.string.show_island_popup_description),
            checked = appState.showIslandPopup, onCheckedChange = appSettingsViewModel::setShowIslandPopup)
    } }
    BackgroundActivitySection()
    SettingsSection(stringResource(R.string.ui_permissions)) {
        MenuNavRow(stringResource(R.string.ui_permissions), dark) { navController.navigate("permissions") }
    }
}



// ─── 5. Audio & Connection ───────────────────────────────────────────────────

@Composable
internal fun AudioSettingsSection(
    state: AirPodsUiState,
    appState: AppSettingsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
) {
    val model = state.instance?.model ?: AirPodsPro3()
    AudioSettings(
        navController = navController,
        adaptiveVolumeCapability = model.capabilities.contains(Capability.ADAPTIVE_VOLUME),
        conversationalAwarenessCapability = false,
        adaptiveAudioCapability = false,
        customEqCapability = model.capabilities.contains(Capability.CUSTOM_EQ),
        adaptiveVolumeChecked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte(),
        onAdaptiveVolumeCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG, it) },
        conversationalAwarenessChecked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte() && state.isPremium,
        onConversationalAwarenessCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it) },
        isPremium = state.isPremium,
        aacpAvailable = state.aacpAvailable,
    )
}

@Composable
private fun AudioContent(state: AirPodsUiState, appState: AppSettingsUiState, viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel, navController: NavController, dark: Boolean) {
    AnchorWrapper("audio_controls") { modifier -> AnchorWrapper("personalized_volume") { volumeModifier ->
        Column(modifier.then(volumeModifier).fillMaxWidth()) {
            AudioSettingsSection(state, appState, viewModel, appSettingsViewModel, navController)
        }
    } }
    SettingsContent(state, appState, viewModel, appSettingsViewModel, navController, dark)
    AdaptiveAudioContent(state, viewModel, dark)
    AnchorWrapper("microphone") { modifier -> SettingsSection(stringResource(R.string.ui_microphone_settings), modifier) {
        val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
        Box(Modifier.fillMaxWidth().alpha(if (state.aacpAvailable) 1f else DisabledAlpha)) {
            MicrophoneSettings(hazeState = remember { HazeState() },
                micModeValue = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(),
                onMicModeValueChanged = { if (state.aacpAvailable) viewModel.setControlCommandByte(id, it) })
        }
        if (!state.aacpAvailable) Text(stringResource(R.string.ui_connect_airpods_to_configure), style = captionStyle(dark), modifier = Modifier.padding(16.dp))
    } }
}



@Composable
private fun ConnectionContent(state: AirPodsUiState, appState: AppSettingsUiState, viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel, navController: NavController, dark: Boolean) {
    val context = LocalContext.current
    AnchorWrapper("device") { deviceModifier -> SettingsSection(stringResource(R.string.design_device), deviceModifier) {
        MenuNavRow(stringResource(R.string.ui_device_name), dark, subtitle = state.deviceName) { navController.navigate("rename") }
        MenuDivider()
        MenuNavRow(stringResource(R.string.disconnect), dark) { viewModel.disconnect() }
    }
    }
    AnchorWrapper("handover") { modifier -> SettingsSection(stringResource(R.string.design_automatic_connection), modifier) {
        StyledToggle(label = stringResource(R.string.cross_device_handover), description = stringResource(R.string.cross_device_handover_description),
            checked = state.crossDeviceEnabled, onCheckedChange = viewModel::setCrossDeviceEnabled)
        MenuNavRow(stringResource(R.string.ui_connected_devices), dark) { navController.navigate("paired_devices") }
        MenuDivider()
        AnchorWrapper("lid_open_autoconnect") { rowModifier -> Box(rowModifier) {
            StyledToggle(label = stringResource(R.string.lid_open_last_holder_autoconnect), description = stringResource(R.string.lid_open_last_holder_autoconnect_description),
                checked = state.lidOpenLastHolderAutoconnect, onCheckedChange = viewModel::setLidOpenLastHolderAutoconnect)
        } }
        AnchorWrapper("takeover_ringing") { rowModifier -> Box(rowModifier) {
            StyledToggle(label = stringResource(R.string.takeover_ringing_call), description = stringResource(R.string.takeover_ringing_call_desc),
                checked = appState.takeoverWhenRingingCall, onCheckedChange = appSettingsViewModel::setTakeoverWhenRingingCall)
        } }
        AnchorWrapper("takeover_media") { rowModifier -> Box(rowModifier) {
            StyledToggle(label = stringResource(R.string.takeover_media_start), description = stringResource(R.string.takeover_media_start_desc),
                checked = appState.takeoverWhenMediaStart, onCheckedChange = appSettingsViewModel::setTakeoverWhenMediaStart)
        } }
    } }
    AnchorWrapper("ear_detection") { modifier -> SettingsSection(stringResource(R.string.ear_detection), modifier) {
        StyledToggle(label = stringResource(R.string.ear_detection), checked = state.automaticEarDetectionEnabled,
            onCheckedChange = viewModel::setAutomaticEarDetectionEnabled, enabled = state.aacpAvailable)
        AnchorWrapper("disconnect_when_not_wearing") { rowModifier -> Box(rowModifier) {
            if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                StyledToggle(label = stringResource(R.string.disconnect_when_not_wearing), description = stringResource(R.string.disconnect_when_not_wearing_description),
                    checked = appState.disconnectWhenNotWearing, onCheckedChange = appSettingsViewModel::setDisconnectWhenNotWearing, enabled = appState.isPremium)
            } else Text(stringResource(R.string.ui_bluetooth_privileged_required), style = captionStyle(dark), modifier = Modifier.padding(16.dp))
        } }
    } }
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

    Column(Modifier.fillMaxWidth()) {
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
        Column(Modifier.padding(vertical = 8.dp)) {
            AboutCard(navController = navController, modelName = state.modelName,
                actualModel = state.actualModel, serialNumbers = state.serialNumbers,
                version = state.version3)
        }
        MenuDivider()
        Column(Modifier.padding(vertical = 8.dp)) {
            DeviceInfoCard()
            Spacer(Modifier.height(8.dp))
            AnchorWrapper("app_version") { anchorModifier -> Box(anchorModifier.fillMaxWidth()) { AppInfoCard() } }
        }
        MenuDivider()
        MenuNavRow(stringResource(R.string.ui_device_protocol_versions), dark) { navController.navigate("version_info") }
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
    MenuSectionHeader(label, dark, requiresAacp)
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
                modifier = Modifier.height(46.dp).fillMaxWidth()
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
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 10.sp, fontFamily = SfPro,
                color = textColor.copy(alpha = 0.5f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(actionName, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
                val optionEnabled = isPremium || action !in setOf(
                    StemAction.DIGITAL_ASSISTANT,
                    StemAction.GOOGLE_VOICE_COMMAND,
                )
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
    defaultAction: StemAction,
    state: AirPodsUiState,
    viewModel: AirPodsViewModel,
    actionOptions: List<Pair<StemAction, String>>,
    enabled: Boolean,
    dark: Boolean,
    modifier: Modifier,
    readAction: (String, StemAction) -> StemAction,
) {
    val prefKey = "${side}_${pressType.name.lowercase()}_action"
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

@Composable
private fun BackgroundActivitySection() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    AnchorWrapper("background_activity") { modifier -> SettingsSection(stringResource(R.string.design_background_activity), modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (unrestricted) "✅ Unrestricted — service runs reliably"
                else "⚠ Battery optimization is active",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SfPro,
                    color = if (unrestricted) Color(0xFF34C759)
                            else Color(0xFFFF9500)
                )
            )
            Text(
                if (unrestricted)
                    "EveryPods is allowed to run in the background. Handover, " +
                    "BLE proximity, and boot autostart will work."
                else
                    "The system may kill the EveryPods service to save battery. " +
                    "Handover may miss events, and EveryPods may not autostart on boot. " +
                    "Tap below to set it to Unrestricted.",
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
    } }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SleepTimerSettings() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var remaining by remember { mutableLongStateOf(SleepTimer.remainingMs(context)) }
    DisposableEffect(context) {
        val listener: () -> Unit = { remaining = SleepTimer.remainingMs(context) }
        SleepTimer.addListener(listener)
        onDispose { SleepTimer.removeListener(listener) }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15,30,45,60,90).forEach { minutes ->
                StyledButton(onClick = { SleepTimer.start(context, minutes * 60_000L) }, backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ui_sleep_preset_minutes, minutes), style = bodyStyle(dark))
                }
            }
        }
        if (remaining > 0) {
            Text(stringResource(R.string.ui_sleep_remaining, (remaining/60_000).toInt(), ((remaining%60_000)/1000).toInt()), style = captionStyle(dark))
            StyledButton(onClick = { SleepTimer.cancel(context) }, backdrop = rememberLayerBackdrop(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_cancel), style = bodyStyle(dark))
            }
        } else Text(stringResource(R.string.ui_no_timer_running), style = captionStyle(dark))
    }
}

@Composable
private fun TargetSection(id: String, title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    AnchorWrapper(id) { modifier -> SettingsSection(title, modifier, content) }
}
