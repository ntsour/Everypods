/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.screens

import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.AirPodsPro3
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.NoiseControlMode
import me.kavishdevar.librepods.presentation.components.AboutCard
import me.kavishdevar.librepods.presentation.components.AudioSettings
import me.kavishdevar.librepods.presentation.components.BatteryView
import me.kavishdevar.librepods.presentation.components.CallControlSettings
import me.kavishdevar.librepods.presentation.components.ConnectionSettings
import me.kavishdevar.librepods.presentation.components.HearingHealthSettings
import me.kavishdevar.librepods.presentation.components.MicrophoneSettings
import me.kavishdevar.librepods.presentation.components.NavigationButton
import me.kavishdevar.librepods.presentation.components.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.PressAndHoldSettings
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import kotlin.io.encoding.ExperimentalEncodingApi

// ─── Colours ────────────────────────────────────────────────────────────────
private val RootOrange = Color(0xFFFF9500)
private val DisabledAlpha = 0.45f

// ─── SF-Pro font shorthand ───────────────────────────────────────────────────
private val SfPro get() = FontFamily(Font(R.font.sf_pro))

// ─── Reusable text styles ────────────────────────────────────────────────────
@Composable
private fun bodyStyle(dark: Boolean) = TextStyle(
    fontSize = 16.sp,
    fontFamily = SfPro,
    color = if (dark) Color.White else Color.Black
)

@Composable
private fun captionStyle(dark: Boolean) = TextStyle(
    fontSize = 13.sp,
    fontFamily = SfPro,
    color = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)
)

// ─── Section header ──────────────────────────────────────────────────────────
@Composable
private fun MenuSectionHeader(label: String, dark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (dark) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = SfPro,
                color = if (dark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)
            )
        )
    }
}

// ─── Root-required banner ────────────────────────────────────────────────────
@Composable
private fun RootRequiredBanner(dark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (dark) Color(0xFF2C2C2E) else Color(0xFFFFF3E0),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("􀎠", style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = RootOrange))
        Text(
            text = "Requires device root. Bluetooth profile switching is not available without it.",
            style = TextStyle(
                fontSize = 13.sp,
                fontFamily = SfPro,
                color = RootOrange
            )
        )
    }
}

// ─── Listening Mode card (main page, tap-to-cycle) ───────────────────────────
@Composable
private fun ListeningModeCard(
    currentModeValue: Int,
    showOff: Boolean,
    hasRoot: Boolean,
    dark: Boolean,
    onModeChanged: (Int) -> Unit
) {
    val cardBg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    // ordinal is 0-based: OFF=0, NOISE_CANCELLATION=1, TRANSPARENCY=2, ADAPTIVE=3
    // API value = ordinal + 1
    val modes = if (showOff)
        listOf(NoiseControlMode.OFF, NoiseControlMode.NOISE_CANCELLATION, NoiseControlMode.TRANSPARENCY, NoiseControlMode.ADAPTIVE)
    else
        listOf(NoiseControlMode.NOISE_CANCELLATION, NoiseControlMode.TRANSPARENCY, NoiseControlMode.ADAPTIVE)

    val currentMode = NoiseControlMode.entries.getOrElse((currentModeValue - 1).coerceIn(0, 3)) { NoiseControlMode.NOISE_CANCELLATION }

    fun modeIcon(m: NoiseControlMode) = when (m) {
        NoiseControlMode.OFF          -> "􀺶"
        NoiseControlMode.NOISE_CANCELLATION -> "􀺸"
        NoiseControlMode.TRANSPARENCY -> "􀌀"
        NoiseControlMode.ADAPTIVE     -> "􀺻"
    }
    fun modeName(m: NoiseControlMode) = when (m) {
        NoiseControlMode.OFF          -> "Off"
        NoiseControlMode.NOISE_CANCELLATION -> "Noise Cancellation"
        NoiseControlMode.TRANSPARENCY -> "Transparency"
        NoiseControlMode.ADAPTIVE     -> "Adaptive"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(18.dp))
            .then(
                if (hasRoot) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    val idx = modes.indexOf(currentMode)
                    val next = modes[(idx + 1) % modes.size]
                    onModeChanged(next.ordinal + 1)
                } else Modifier
            )
            .alpha(if (hasRoot) 1f else DisabledAlpha)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Listening Mode",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SfPro,
                    color = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)
                )
            )
            if (!hasRoot) {
                Text("􀎠", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = RootOrange))
            }
        }

        // Current mode display
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = modeIcon(currentMode),
                style = TextStyle(fontSize = 22.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black)
            )
            Text(
                text = modeName(currentMode),
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = SfPro,
                    color = if (dark) Color.White else Color.Black
                )
            )
        }

        // Cycle pill indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            modes.forEach { m ->
                val active = m == currentMode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (active) {
                                if (dark) Color.White else Color.Black
                            } else {
                                if (dark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.15f)
                            },
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }

        // Hint or root warning
        Text(
            text = if (hasRoot) "Tap to change" else "Requires device root to function",
            style = TextStyle(
                fontSize = 12.sp,
                fontFamily = SfPro,
                color = if (hasRoot) {
                    if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
                } else RootOrange
            )
        )
    }
}

// ─── Menu toggle row ─────────────────────────────────────────────────────────
@Composable
private fun MenuToggleRow(label: String, chevron: String = "  ▶", onClick: () -> Unit, dark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = bodyStyle(dark))
        Text(
            text = chevron,
            style = TextStyle(
                fontSize = 14.sp,
                fontFamily = SfPro,
                color = if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
            )
        )
    }
}

// ─── Collapsible menu category ───────────────────────────────────────────────
@Composable
private fun MenuCategory(
    label: String,
    dark: Boolean,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val cardBg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = SfPro,
                    color = if (dark) Color.White else Color.Black
                )
            )
            Text(
                text = if (expanded) "  ▲" else "  ▼",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = SfPro,
                    color = if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
                )
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit  = shrinkVertically(tween(250)) + fadeOut(tween(200))
        ) {
            Column {
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                content()
            }
        }
    }
}

// ─── Divider between menu items ──────────────────────────────────────────────
@Composable
private fun MenuDivider() {
    HorizontalDivider(
        color = Color(0x30888888),
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MAIN SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AirPodsSettingsScreen(viewModel: AirPodsViewModel, navController: NavController) {
    val state by viewModel.uiState.collectAsState()
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)

    var deviceName by remember {
        mutableStateOf(TextFieldValue(sharedPreferences.getString("name", state.deviceName).toString()))
    }

    val nameChangeListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "name")
                deviceName = TextFieldValue(sharedPreferences.getString("name", "AirPods Pro").toString())
        }
    }

    DisposableEffect(Unit) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(nameChangeListener)
        onDispose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(nameChangeListener) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refreshInitialData() }

    val hazeStateS = remember { mutableStateOf(HazeState()) }

    StyledScaffold(
        title = deviceName.text,
        actionButtons = listOf({ scaffoldBackdrop ->
            StyledIconButton(
                onClick = { navController.navigate("app_settings") },
                icon = "􀍟",
                backdrop = scaffoldBackdrop
            )
        }),
        snackbarHostState = snackbarHostState
    ) { topPadding, hazeState, bottomPadding ->
        hazeStateS.value = hazeState

        var blockTouches by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            viewModel.demoActivated.collect {
                blockTouches = true; delay(1000); blockTouches = false
            }
        }

        if (state.isLocallyConnected) {
            ConnectedScreen(
                state        = state,
                viewModel    = viewModel,
                navController= navController,
                sharedPrefs  = sharedPreferences,
                topPadding   = topPadding,
                bottomPadding= bottomPadding,
                hazeState    = hazeState,
                dark         = dark,
                blockTouches = blockTouches
            )
        } else {
            DisconnectedScreen(
                state        = state,
                viewModel    = viewModel,
                navController= navController,
                topPadding   = topPadding,
                bottomPadding= bottomPadding,
                hazeState    = hazeState,
                dark         = dark
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  CONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ConnectedScreen(
    state: me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState,
    viewModel: AirPodsViewModel,
    navController: NavController,
    sharedPrefs: SharedPreferences,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    blockTouches: Boolean
) {
    val capabilities  = state.capabilities
    val hasRoot       = state.hasRootPermissions
    var menuExpanded  by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .then(
                if (blockTouches) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                } else Modifier
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "spacer_top") { Spacer(Modifier.height(topPadding)) }

        // ── Battery ────────────────────────────────────────────────────────
        item(key = "battery") {
            BatteryView(
                batteryList = state.battery,
                budsRes     = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_case,
                caseRes     = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case
            )
        }

        // ── Listening Mode (tap-to-cycle, root-gated) ─────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            item(key = "listening_mode") {
                ListeningModeCard(
                    currentModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]
                        ?.getOrNull(0)?.toInt() ?: 3,
                    showOff  = state.offListeningMode,
                    hasRoot  = hasRoot,
                    dark     = dark,
                    onModeChanged = {
                        viewModel.setControlCommandInt(
                            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE, it
                        )
                    }
                )
            }
        }

        // ── Transparency Settings ─────────────────────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            item(key = "transparency_nav") {
                NavigationButton(
                    to           = "transparency_customization",
                    name         = stringResource(R.string.customize_transparency_mode),
                    navController= navController
                )
            }
        }

        // ── Device Info (always expanded) ─────────────────────────────────
        item(key = "about") {
            AboutCard(
                navController = navController,
                modelName     = state.modelName,
                actualModel   = state.actualModel,
                serialNumbers = state.serialNumbers,
                version       = state.version3
            )
        }

        // ── Upgrade banner (non-premium) ──────────────────────────────────
        if (!state.isPremium) {
            item(key = "upgrade") {
                StyledButton(
                    onClick      = { navController.navigate("purchase_screen") },
                    backdrop     = rememberLayerBackdrop(),
                    modifier     = Modifier.fillMaxWidth(),
                    maxScale     = 0.05f,
                    surfaceColor = if (dark) Color(0xFF916100) else Color(0xFFE59900)
                ) {
                    Text(
                        stringResource(R.string.unlock_advanced_features),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SfPro,
                            color = Color.White
                        )
                    )
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        //  MORE OPTIONS TOGGLE
        // ══════════════════════════════════════════════════════════════════
        item(key = "menu_toggle") {
            Column {
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { menuExpanded = !menuExpanded }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (menuExpanded) "▲  More Options  ▲" else "▼  More Options  ▼",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SfPro,
                            color = if (dark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f)
                        )
                    )
                }
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        //  MENU CONTENT (animated)
        // ══════════════════════════════════════════════════════════════════
        item(key = "menu_body") {
            AnimatedVisibility(
                visible = menuExpanded,
                enter   = expandVertically(tween(300)) + fadeIn(tween(250)),
                exit    = shrinkVertically(tween(300)) + fadeOut(tween(250))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // ── 1. AirPods Controls ───────────────────────────────
                    MenuCategory(label = "🎧  AirPods Controls", dark = dark) {
                        if (capabilities.contains(Capability.STEM_CONFIG)) {
                            MenuToggleRow(
                                label   = "Press & Hold Settings",
                                dark    = dark,
                                onClick = { navController.navigate("long_press/left") }
                            )
                        } else {
                            MenuToggleRow(
                                label   = "Press & Hold Settings",
                                dark    = dark,
                                onClick = { navController.navigate("long_press/left") }
                            )
                        }
                    }

                    // ── 2. AirPods Settings ───────────────────────────────
                    MenuCategory(label = "⚙️  AirPods Settings", dark = dark) {
                        // Device Rename
                        MenuToggleRow(
                            label   = "Device Name",
                            dark    = dark,
                            onClick = { navController.navigate("rename") }
                        )
                        MenuDivider()

                        // Hearing Aid (capability-gated)
                        val hasHearingAid = state.instance?.model?.capabilities?.contains(Capability.HEARING_AID) == true
                        val hasPPE        = state.instance?.model?.capabilities?.contains(Capability.PPE) == true
                        if (hasHearingAid || hasPPE) {
                            MenuToggleRow(
                                label   = "Hearing Aid",
                                dark    = dark,
                                onClick = { navController.navigate("hearing_aid") }
                            )
                            MenuDivider()
                            MenuToggleRow(
                                label   = "Hearing Aid Adjustments",
                                dark    = dark,
                                onClick = { navController.navigate("hearing_aid_adjustments") }
                            )
                            MenuDivider()
                        }

                        // Hearing Protection
                        if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                            MenuToggleRow(
                                label   = "Hearing Protection",
                                dark    = dark,
                                onClick = { navController.navigate("hearing_protection") }
                            )
                            MenuDivider()
                        }

                        // Accessibility (other)
                        MenuToggleRow(
                            label   = "Accessibility",
                            dark    = dark,
                            onClick = { navController.navigate("accessibility") }
                        )
                        MenuDivider()

                        // ── ROOT REQUIRED: Disconnect ─────────────────────
                        MenuSectionHeader(label = "🔒  Bluetooth Control (Root Required)", dark = dark)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (hasRoot) 1f else DisabledAlpha)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!hasRoot) {
                                RootRequiredBanner(dark)
                            }
                            StyledButton(
                                onClick      = { if (hasRoot) viewModel.disconnect() },
                                backdrop     = rememberLayerBackdrop(),
                                isInteractive= hasRoot,
                                modifier     = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 50.dp)
                            ) {
                                Text(
                                    text  = stringResource(R.string.disconnect),
                                    style = TextStyle(
                                        fontSize   = 16.sp,
                                        fontWeight = FontWeight.Normal,
                                        color      = if (hasRoot) {
                                            if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
                                        } else {
                                            if (dark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.35f)
                                        },
                                        fontFamily = SfPro
                                    ),
                                    textAlign = TextAlign.Start,
                                    modifier  = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // ── 3. Smart Features ─────────────────────────────────
                    MenuCategory(label = "✨  AirPods Smart Features", dark = dark) {

                        // Notification Announcements
                        MenuToggleRow(
                            label   = "Notification Announcements",
                            dark    = dark,
                            onClick = { navController.navigate("notification_announcements") }
                        )
                        MenuDivider()

                        // Head Gestures
                        if (capabilities.contains(Capability.HEAD_GESTURES)) {
                            val headState = if (sharedPrefs.getBoolean("head_gestures", false))
                                "On" else "Off"
                            MenuToggleRow(
                                label   = "Head Gestures — $headState",
                                dark    = dark,
                                onClick = { navController.navigate("head_tracking") }
                            )
                            MenuDivider()
                        }

                        // Adaptive Strength / Volume
                        val model = state.instance?.model ?: AirPodsPro3()
                        if (model.capabilities.contains(Capability.ADAPTIVE_VOLUME)) {
                            MenuToggleRow(
                                label   = "Adaptive Audio",
                                dark    = dark,
                                onClick = { navController.navigate("adaptive_strength") }
                            )
                            MenuDivider()
                        }

                        // Camera Control
                        if (capabilities.contains(Capability.STEM_CONFIG) && !BuildConfig.PLAY_BUILD) {
                            MenuToggleRow(
                                label   = "Camera Control",
                                dark    = dark,
                                onClick = { navController.navigate("camera_control") }
                            )
                            MenuDivider()
                        }

                        // ANC Profiles
                        MenuToggleRow(
                            label   = "ANC Profiles",
                            dark    = dark,
                            onClick = { navController.navigate("anc_profiles") }
                        )
                        MenuDivider()

                        // Off Listening Mode toggle
                        if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(
                                    label          = stringResource(R.string.off_listening_mode),
                                    description    = stringResource(R.string.off_listening_mode_description),
                                    checked        = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                                    onCheckedChange= viewModel::setOffListeningMode
                                )
                            }
                            MenuDivider()
                        }

                        // Sleep Detection
                        if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(
                                    label          = stringResource(R.string.sleep_detection),
                                    checked        = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                                    onCheckedChange= { viewModel.setControlCommandBoolean(id, it) },
                                    enabled        = state.isPremium
                                )
                            }
                            MenuDivider()
                        }

                        // Optimized Charging
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StyledToggle(
                                label          = stringResource(R.string.optimized_charging),
                                description    = stringResource(R.string.optimized_charging_description),
                                checked        = state.dynamicEndOfCharge,
                                onCheckedChange= viewModel::setDynamicEndOfCharge
                            )
                        }
                        MenuDivider()

                        // Smart Features screen link (sleep timer, battery alerts etc.)
                        MenuToggleRow(
                            label   = "Smart Features (Sleep Timer, Battery Alerts…)",
                            dark    = dark,
                            onClick = { navController.navigate("smart_features") }
                        )
                    }

                    // ── 4. App Settings ───────────────────────────────────
                    MenuCategory(label = "📱  App Settings", dark = dark) {
                        MenuToggleRow(
                            label   = "Troubleshooting",
                            dark    = dark,
                            onClick = { navController.navigate("troubleshooting") }
                        )
                        MenuDivider()
                        MenuToggleRow(
                            label   = "Open Source Licenses",
                            dark    = dark,
                            onClick = { navController.navigate("open_source_licenses") }
                        )
                        MenuDivider()
                        MenuToggleRow(
                            label   = "Version Info",
                            dark    = dark,
                            onClick = { navController.navigate("version_info") }
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // ── Remaining audio / connection sections ─────────────
                    // Keep existing AudioSettings, ConnectionSettings,
                    // CallControlSettings, MicrophoneSettings in a
                    // "More Controls" category so nothing is lost.
                    MenuCategory(label = "🔊  Audio & Connection", dark = dark) {
                        val m = state.instance?.model ?: AirPodsPro3()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            AudioSettings(
                                navController                           = navController,
                                adaptiveVolumeCapability                = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                                conversationalAwarenessCapability       = m.capabilities.contains(Capability.CONVERSATION_AWARENESS),
                                loudSoundReductionCapability            = m.capabilities.contains(Capability.LOUD_SOUND_REDUCTION),
                                adaptiveAudioCapability                 = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                                adaptiveVolumeChecked                   = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte(),
                                onAdaptiveVolumeCheckedChange           = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG, it) },
                                conversationalAwarenessChecked          = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte() && state.isPremium,
                                onConversationalAwarenessCheckedChange  = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it) },
                                loudSoundReductionChecked               = state.loudSoundReductionEnabled,
                                onLoudSoundReductionCheckedChange       = { viewModel.setATTCharacteristicValue(ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(if (it) 0x01.toByte() else 0x00.toByte())) },
                                vendorIdHook                            = state.vendorIdHook,
                                isPremium                               = state.isPremium
                            )
                        }
                        MenuDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val bytes   = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
                            val flipped = try { bytes[1] == 0x02.toByte() } catch (_: Exception) { false }
                            CallControlSettings(
                                hazeState               = hazeStateStub(),
                                flipped                 = flipped,
                                onCallControlValueChanged = {
                                    viewModel.setControlCommandValue(
                                        AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                                        if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03)
                                    )
                                }
                            )
                        }
                        MenuDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ConnectionSettings(
                                automaticEarDetectionEnabled   = state.automaticEarDetectionEnabled,
                                onAutomaticEarDetectionChanged = { viewModel.setAutomaticEarDetectionEnabled(it) },
                                automaticConnectionEnabled     = state.automaticConnectionEnabled,
                                onAutomaticConnectionChanged   = { viewModel.setAutomaticConnectionEnabled(it) }
                            )
                        }
                        MenuDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
                            MicrophoneSettings(
                                hazeState         = hazeStateStub(),
                                micModeValue      = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(),
                                onMicModeValueChanged = { viewModel.setControlCommandByte(id, it) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding + 16.dp)) }
    }
}

/** Placeholder HazeState for components that need it but we don't have it in scope here. */
@Composable
private fun hazeStateStub() = remember { HazeState() }

// ═══════════════════════════════════════════════════════════════════════════════
//  DISCONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun DisconnectedScreen(
    state: me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState,
    viewModel: AirPodsViewModel,
    navController: NavController,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean
) {
    val backdrop   = rememberLayerBackdrop()
    val cardBg     = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor  = if (dark) Color.White else Color.Black

    // Hidden demo-mode tap trigger (preserved from original)
    val tapCount   = remember { mutableIntStateOf(0) }
    val lastTapTime = remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop         = rememberLayerBackdrop(),
                exportedBackdrop = backdrop,
                shape            = { RoundedCornerShape(0.dp) },
                highlight        = { Highlight.Ambient.copy(alpha = 0f) },
                effects          = {}
            )
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime.longValue > 400) tapCount.intValue = 0
                    tapCount.intValue++
                    lastTapTime.longValue = now
                    if (tapCount.intValue >= 5) { tapCount.intValue = 0; viewModel.activateDemoMode() }
                })
            },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "spacer_top") { Spacer(Modifier.height(topPadding + 16.dp)) }

        // ── Status header ─────────────────────────────────────────────────
        item(key = "status") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text  = stringResource(R.string.airpods_not_connected),
                    style = TextStyle(
                        fontSize   = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color      = textColor,
                        fontFamily = SfPro
                    ),
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
                Text(
                    text  = stringResource(R.string.airpods_not_connected_description),
                    style = TextStyle(
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.Light,
                        color      = textColor.copy(alpha = 0.7f),
                        fontFamily = SfPro
                    ),
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
            }
        }

        // ── Card 1: Reconnect ─────────────────────────────────────────────
        if (state.connectionSuccessful) {
            item(key = "reconnect_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(18.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text  = "Reconnect to Previous Device",
                        style = TextStyle(
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = textColor.copy(alpha = 0.6f),
                            fontFamily = SfPro
                        )
                    )
                    Text(
                        text  = "Your AirPods were previously connected to this device.",
                        style = TextStyle(
                            fontSize   = 14.sp,
                            fontFamily = SfPro,
                            color      = textColor.copy(alpha = 0.55f)
                        )
                    )
                    StyledButton(
                        onClick  = { viewModel.reconnectFromSavedMac() },
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text  = stringResource(R.string.reconnect_to_last_device),
                            style = TextStyle(
                                fontSize   = 16.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = SfPro,
                                color      = textColor
                            )
                        )
                    }
                }
            }
        }

        // ── Card 2: Find Nearby ───────────────────────────────────────────
        item(key = "find_nearby_card") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBg, RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text  = "Find My AirPods",
                    style = TextStyle(
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = textColor.copy(alpha = 0.6f),
                        fontFamily = SfPro
                    )
                )
                Text(
                    text  = "Locate your AirPods using Bluetooth signal proximity.",
                    style = TextStyle(
                        fontSize   = 14.sp,
                        fontFamily = SfPro,
                        color      = textColor.copy(alpha = 0.55f)
                    )
                )
                StyledButton(
                    onClick  = { navController.navigate("proximity_finder") },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text  = "Find Nearby",
                        style = TextStyle(
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SfPro,
                            color      = textColor
                        )
                    )
                }
            }
        }

        // ── Card 3: Troubleshooting ───────────────────────────────────────
        if (!BuildConfig.PLAY_BUILD) {
            item(key = "troubleshooting_card") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(18.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text  = "Troubleshooting",
                        style = TextStyle(
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = textColor.copy(alpha = 0.6f),
                            fontFamily = SfPro
                        )
                    )
                    Text(
                        text  = "Can't reconnect? Get help with common connection issues.",
                        style = TextStyle(
                            fontSize   = 14.sp,
                            fontFamily = SfPro,
                            color      = textColor.copy(alpha = 0.55f)
                        )
                    )
                    StyledButton(
                        onClick  = { navController.navigate("troubleshooting") },
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text  = stringResource(R.string.troubleshooting),
                            style = TextStyle(
                                fontSize   = 16.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = SfPro,
                                color      = textColor
                            )
                        )
                    }
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding)) }
    }
}
