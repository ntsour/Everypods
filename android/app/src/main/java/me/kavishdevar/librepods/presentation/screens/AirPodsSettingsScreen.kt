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
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
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
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
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
import me.kavishdevar.librepods.presentation.components.AboutCard
import me.kavishdevar.librepods.presentation.components.AppInfoCard
import me.kavishdevar.librepods.presentation.components.AudioSettings
import me.kavishdevar.librepods.presentation.components.BatteryView
import me.kavishdevar.librepods.presentation.components.CallControlSettings
import me.kavishdevar.librepods.presentation.components.ConnectionSettings
import me.kavishdevar.librepods.presentation.components.DeviceInfoCard
import me.kavishdevar.librepods.presentation.components.MicrophoneSettings
import me.kavishdevar.librepods.presentation.components.NavigationButton
import me.kavishdevar.librepods.presentation.components.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.components.StyledInputField
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.utils.XposedState
import kotlin.io.encoding.ExperimentalEncodingApi

// ─── Constants ────────────────────────────────────────────────────────────────
private val RootOrange      = Color(0xFFFF9500)
private const val DisabledAlpha = 0.45f
private val SfPro get()     = FontFamily(Font(R.font.sf_pro))

// ─── Shared text style ────────────────────────────────────────────────────────
@Composable
private fun bodyStyle(dark: Boolean) = TextStyle(
    fontSize = 16.sp, fontFamily = SfPro,
    color = if (dark) Color.White else Color.Black
)

// ─── Section header inside a category ────────────────────────────────────────
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
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro,
                color = if (dark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f)
            )
        )
    }
}

// ─── Orange root-required banner ──────────────────────────────────────────────
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
            style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = RootOrange)
        )
    }
}

// ─── Thin divider between rows ────────────────────────────────────────────────
@Composable
private fun MenuDivider() {
    HorizontalDivider(
        color = Color(0x30888888), thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

// ─── Simple nav row with chevron ──────────────────────────────────────────────
@Composable
private fun MenuNavRow(label: String, dark: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = bodyStyle(dark))
        Text(
            text = "  ›",
            style = TextStyle(
                fontSize = 20.sp, fontFamily = SfPro,
                color = if (dark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.35f)
            )
        )
    }
}

// ─── Collapsible category card ────────────────────────────────────────────────
@Composable
private fun MenuCategory(label: String, dark: Boolean, content: @Composable () -> Unit) {
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
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro,
                    color = if (dark) Color.White else Color.Black
                )
            )
            Text(
                text = if (expanded) "  ▲" else "  ▼",
                style = TextStyle(
                    fontSize = 13.sp, fontFamily = SfPro,
                    color = if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
                )
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter   = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit    = shrinkVertically(tween(250)) + fadeOut(tween(200))
        ) {
            Column {
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                content()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MAIN COMPOSABLE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AirPodsSettingsScreen(
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController
) {
    val state    by viewModel.uiState.collectAsState()
    val appState by appSettingsViewModel.uiState.collectAsState()
    val dark     = isSystemInDarkTheme()
    val context  = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)

    // Contact bottom sheet state (moved from AppSettingsScreen)
    val contactBottomSheet  = remember { mutableStateOf(false) }
    val subjectState        = remember { TextFieldState() }
    val descriptionState    = remember { TextFieldState() }
    val backdrop            = rememberLayerBackdrop()

    var deviceName by remember {
        mutableStateOf(TextFieldValue(sharedPreferences.getString("name", state.deviceName).toString()))
    }
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "name")
                deviceName = TextFieldValue(sharedPreferences.getString("name", "AirPods Pro").toString())
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refreshInitialData() }

    // ⚙️ icon removed — no actionButtons
    StyledScaffold(
        title             = deviceName.text,
        actionButtons     = emptyList(),
        snackbarHostState = snackbarHostState
    ) { topPadding, hazeState, bottomPadding ->

        var blockTouches by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            viewModel.demoActivated.collect { blockTouches = true; delay(1000); blockTouches = false }
        }

        if (state.isLocallyConnected) {
            ConnectedScreen(
                state                = state,
                appState             = appState,
                viewModel            = viewModel,
                appSettingsViewModel = appSettingsViewModel,
                navController        = navController,
                sharedPrefs          = sharedPreferences,
                topPadding           = topPadding,
                bottomPadding        = bottomPadding,
                hazeState            = hazeState,
                dark                 = dark,
                blockTouches         = blockTouches,
                onOpenContact        = { contactBottomSheet.value = true }
            )
        } else {
            DisconnectedScreen(
                state         = state,
                viewModel     = viewModel,
                navController = navController,
                topPadding    = topPadding,
                bottomPadding = bottomPadding,
                hazeState     = hazeState,
                dark          = dark,
                onOpenContact = { contactBottomSheet.value = true }
            )
        }
    }

    // Contact bottom sheet (shared between connected + disconnected)
    StyledBottomSheet(
        visible   = contactBottomSheet.value,
        onDismiss = { contactBottomSheet.value = false },
        backdrop  = backdrop
    ) { innerBackdrop, progress ->
        val animatedPadding = lerp(16.dp, 2.dp, progress)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = animatedPadding)
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                StyledIconButton(
                    icon    = "\uDBC0\uDD84",
                    backdrop = innerBackdrop,
                    onClick = { contactBottomSheet.value = false }
                )
                Text(
                    text  = stringResource(R.string.describe_your_issue),
                    style = TextStyle(
                        fontSize = 18.sp, fontFamily = SfPro, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = if (dark) Color.White else Color.Black
                    )
                )
                StyledIconButton(
                    icon        = "\uDBC0\uDE1F",
                    backdrop    = innerBackdrop,
                    surfaceColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF),
                    iconTint    = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray,
                    enabled     = subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty(),
                    onClick     = {
                        contactBottomSheet.value = false
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = "mailto:".toUri()
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("contact@kavish.xyz"))
                            putExtra(Intent.EXTRA_SUBJECT, "LibrePods: ${subjectState.text}")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "${descriptionState.text}\n\n----------" +
                                    "\nPhone details:" +
                                    "\nMANUFACTURER: ${Build.MANUFACTURER}" +
                                    "\nMODEL: ${Build.MODEL} (${Build.PRODUCT})" +
                                    "\nDISPLAY_VERSION: ${Build.DISPLAY}" +
                                    "\nID: ${Build.ID} (SDK ${Build.VERSION.SDK_INT_FULL})" +
                                    "\nXposed enabled/active: ${XposedState.isAvailable}/${XposedState.bluetoothScopeEnabled}" +
                                    "\n\nApp details:" +
                                    "\nVERSION: ${BuildConfig.VERSION_NAME}" +
                                    "\nVERSION_CODE: ${BuildConfig.VERSION_CODE}" +
                                    "\nFLAVOR: ${BuildConfig.FLAVOR}" +
                                    "\nBUILD_TYPE: ${BuildConfig.BUILD_TYPE}"
                            )
                        }
                        context.startActivity(intent)
                        subjectState.clearText()
                        descriptionState.clearText()
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            StyledInputField(inputState = subjectState, focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }, placeholder = stringResource(R.string.subject))
            Spacer(Modifier.height(12.dp))
            StyledInputField(inputState = descriptionState, focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }, placeholder = stringResource(R.string.describe_your_issue), singleLine = false)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  CONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ConnectedScreen(
    state:                me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState,
    appState:             me.kavishdevar.librepods.presentation.viewmodel.AppSettingsUiState,
    viewModel:            AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController:        NavController,
    sharedPrefs:          SharedPreferences,
    topPadding:           androidx.compose.ui.unit.Dp,
    bottomPadding:        androidx.compose.ui.unit.Dp,
    hazeState:            HazeState,
    dark:                 Boolean,
    blockTouches:         Boolean,
    onOpenContact:        () -> Unit
) {
    val context      = LocalContext.current
    val capabilities = state.capabilities
    val hasRoot      = state.hasRootPermissions
    var menuExpanded by remember { mutableStateOf(false) }

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

        // ── Listening Mode (graphical, root-gated) ─────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            item(key = "listening_mode") {
                val cardBg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(18.dp))
                        .alpha(if (hasRoot) 1f else DisabledAlpha.toFloat())
                ) {
                    if (!hasRoot) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("􀎠", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = RootOrange))
                            Text("Requires device root to switch modes", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = RootOrange))
                        }
                        HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Box(
                        modifier = if (!hasRoot) Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } else Modifier
                    ) {
                        NoiseControlSettings(
                            showOffListeningMode      = state.offListeningMode,
                            noiseControlModeValue     = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                            onNoiseControlModeChanged = { viewModel.setControlCommandInt(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE, it) }
                        )
                    }
                }
            }
        }

        // ── Transparency ───────────────────────────────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            item(key = "transparency_nav") {
                NavigationButton(to = "transparency_customization", name = stringResource(R.string.customize_transparency_mode), navController = navController)
            }
        }

        // ── Device Info ────────────────────────────────────────────────────
        item(key = "about") {
            AboutCard(navController = navController, modelName = state.modelName, actualModel = state.actualModel, serialNumbers = state.serialNumbers, version = state.version3)
        }

        // ── Upgrade banner ─────────────────────────────────────────────────
        if (!state.isPremium) {
            item(key = "upgrade") {
                StyledButton(
                    onClick = { navController.navigate("purchase_screen") },
                    backdrop = rememberLayerBackdrop(), modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = if (dark) Color(0xFF916100) else Color(0xFFE59900)
                ) {
                    Text(stringResource(R.string.unlock_advanced_features), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
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
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menuExpanded = !menuExpanded }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (menuExpanded) "▲  More Options  ▲" else "▼  More Options  ▼",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro,
                            color = if (dark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f))
                    )
                }
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        //  MENU BODY
        // ══════════════════════════════════════════════════════════════════
        item(key = "menu_body") {
            AnimatedVisibility(
                visible = menuExpanded,
                enter   = expandVertically(tween(300)) + fadeIn(tween(250)),
                exit    = shrinkVertically(tween(300)) + fadeOut(tween(250))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // ─────────────────────────────────────────────────────
                    // 1. AirPods Controls
                    // ─────────────────────────────────────────────────────
                    MenuCategory("🎧  AirPods Controls", dark) {
                        MenuNavRow("Press & Hold Settings", dark) { navController.navigate("long_press/left") }
                    }

                    // ─────────────────────────────────────────────────────
                    // 2. AirPods Settings
                    // ─────────────────────────────────────────────────────
                    MenuCategory("⚙️  AirPods Settings", dark) {
                        MenuNavRow("Device Name", dark) { navController.navigate("rename") }
                        MenuDivider()

                        val hasHA  = state.instance?.model?.capabilities?.contains(Capability.HEARING_AID) == true
                        val hasPPE = state.instance?.model?.capabilities?.contains(Capability.PPE) == true
                        if (hasHA || hasPPE) {
                            MenuNavRow("Hearing Aid", dark)             { navController.navigate("hearing_aid") }
                            MenuDivider()
                            MenuNavRow("Hearing Aid Adjustments", dark) { navController.navigate("hearing_aid_adjustments") }
                            MenuDivider()
                        }
                        if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                            MenuNavRow("Hearing Protection", dark) { navController.navigate("hearing_protection") }
                            MenuDivider()
                        }
                        MenuNavRow("Accessibility", dark) { navController.navigate("accessibility") }
                        MenuDivider()

                        // Conversation Awareness
                        MenuSectionHeader("Conversation Awareness", dark)
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.conversational_awareness_pause_music), description = stringResource(R.string.conversational_awareness_pause_music_description), checked = appState.conversationalAwarenessPauseMusicEnabled, onCheckedChange = appSettingsViewModel::setConversationalAwarenessPauseMusicEnabled, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.relative_conversational_awareness_volume), description = stringResource(R.string.relative_conversational_awareness_volume_description), checked = appState.relativeConversationalAwarenessVolumeEnabled, onCheckedChange = appSettingsViewModel::setRelativeConversationalAwarenessVolumeEnabled, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledSlider(label = stringResource(R.string.conversational_awareness_volume), value = appState.conversationalAwarenessVolume, valueRange = 10f..85f, snapPoints = listOf(44f), startLabel = "10%", endLabel = "85%", onValueChange = { appSettingsViewModel.setConversationalAwarenessVolume(it) }, independent = true, enabled = appState.isPremium)
                        }
                        MenuDivider()

                        // ROOT REQUIRED: Disconnect
                        MenuSectionHeader("🔒  Bluetooth Control (Root Required)", dark)
                        Column(
                            modifier = Modifier.fillMaxWidth().alpha(if (hasRoot) 1f else DisabledAlpha.toFloat()).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!hasRoot) RootRequiredBanner(dark)
                            StyledButton(
                                onClick = { if (hasRoot) viewModel.disconnect() },
                                backdrop = rememberLayerBackdrop(), isInteractive = hasRoot,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.disconnect),
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal,
                                        color = if (hasRoot) { if (dark) Color(0xFF0091FF) else Color(0xFF0088FF) }
                                                else { if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f) },
                                        fontFamily = SfPro),
                                    textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // ─────────────────────────────────────────────────────
                    // 3. Smart Features
                    // ─────────────────────────────────────────────────────
                    MenuCategory("✨  AirPods Smart Features", dark) {
                        MenuNavRow("Notification Announcements", dark) { navController.navigate("notification_announcements") }
                        MenuDivider()

                        if (capabilities.contains(Capability.HEAD_GESTURES)) {
                            val headOn = sharedPrefs.getBoolean("head_gestures_enabled", true) &&
                                (sharedPrefs.getBoolean("head_gestures_answer_call", true) || sharedPrefs.getBoolean("head_gestures_mute_call", true))
                            MenuNavRow("Head Gestures — ${if (headOn) "On" else "Off"}", dark) { navController.navigate("head_tracking") }
                            MenuDivider()
                        }

                        val model = state.instance?.model ?: AirPodsPro3()
                        if (model.capabilities.contains(Capability.ADAPTIVE_VOLUME)) {
                            MenuNavRow("Adaptive Audio", dark) { navController.navigate("adaptive_strength") }
                            MenuDivider()
                        }
                        if (capabilities.contains(Capability.STEM_CONFIG) && !BuildConfig.PLAY_BUILD) {
                            MenuNavRow("Camera Control", dark) { navController.navigate("camera_control") }
                            MenuDivider()
                        }
                        MenuNavRow("ANC Profiles", dark) { navController.navigate("anc_profiles") }
                        MenuDivider()

                        if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(label = stringResource(R.string.off_listening_mode), description = stringResource(R.string.off_listening_mode_description), checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(), onCheckedChange = viewModel::setOffListeningMode)
                            }
                            MenuDivider()
                        }
                        if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(label = stringResource(R.string.sleep_detection), checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(), onCheckedChange = { viewModel.setControlCommandBoolean(id, it) }, enabled = state.isPremium)
                            }
                            MenuDivider()
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StyledToggle(label = stringResource(R.string.optimized_charging), description = stringResource(R.string.optimized_charging_description), checked = state.dynamicEndOfCharge, onCheckedChange = viewModel::setDynamicEndOfCharge)
                        }
                        MenuDivider()
                        MenuNavRow("Smart Features (Sleep Timer, Battery Alerts…)", dark) { navController.navigate("smart_features") }
                    }

                    // ─────────────────────────────────────────────────────
                    // 4. App Settings
                    // ─────────────────────────────────────────────────────
                    MenuCategory("📱  App Settings", dark) {

                        // Widget battery
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StyledToggle(
                                label           = stringResource(R.string.show_phone_battery_in_widget),
                                description     = stringResource(R.string.show_phone_battery_in_widget_description),
                                checked         = appState.showPhoneBatteryInWidget,
                                onCheckedChange = appSettingsViewModel::setShowPhoneBatteryInWidget,
                                independent     = true,
                                enabled         = appState.isPremium
                            )
                        }
                        MenuDivider()

                        // Pop-up Animations
                        MenuSectionHeader("Pop-up Animations", dark)
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.show_bottom_sheet_popup), description = stringResource(R.string.show_bottom_sheet_popup_description), checked = appState.showBottomSheetPopup, onCheckedChange = appSettingsViewModel::setShowBottomSheetPopup, independent = true)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.show_island_popup), description = stringResource(R.string.show_island_popup_description), checked = appState.showIslandPopup, onCheckedChange = appSettingsViewModel::setShowIslandPopup, independent = true)
                        }
                        MenuDivider()

                        // Xposed: act as Apple device
                        if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                            val restartMsg = stringResource(R.string.found_offset_restart_bluetooth)
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(
                                    label           = stringResource(R.string.act_as_an_apple_device) + " (${stringResource(R.string.requires_xposed)})",
                                    description     = stringResource(R.string.act_as_an_apple_device_description),
                                    checked         = appState.vendorIdHook,
                                    onCheckedChange = { enabled ->
                                        Toast.makeText(context, restartMsg, Toast.LENGTH_SHORT).show()
                                        appSettingsViewModel.setVendorIdHook(enabled)
                                    },
                                    independent     = true,
                                    enabled         = appState.isPremium
                                )
                            }
                            MenuDivider()
                        }

                        // Device & app info
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            DeviceInfoCard()
                            Spacer(Modifier.height(8.dp))
                            AppInfoCard()
                        }
                        MenuDivider()

                        MenuNavRow("Version Info", dark)         { navController.navigate("version_info") }
                        MenuDivider()
                        MenuNavRow("Open Source Licenses", dark) { navController.navigate("open_source_licenses") }
                    }

                    // ─────────────────────────────────────────────────────
                    // 5. Audio & Connection
                    // ─────────────────────────────────────────────────────
                    MenuCategory("🔊  Audio & Connection", dark) {
                        val m = state.instance?.model ?: AirPodsPro3()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            AudioSettings(
                                navController = navController,
                                adaptiveVolumeCapability               = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                                conversationalAwarenessCapability      = m.capabilities.contains(Capability.CONVERSATION_AWARENESS),
                                loudSoundReductionCapability           = m.capabilities.contains(Capability.LOUD_SOUND_REDUCTION),
                                adaptiveAudioCapability                = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                                adaptiveVolumeChecked                  = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte(),
                                onAdaptiveVolumeCheckedChange          = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG, it) },
                                conversationalAwarenessChecked         = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte() && state.isPremium,
                                onConversationalAwarenessCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it) },
                                loudSoundReductionChecked              = state.loudSoundReductionEnabled,
                                onLoudSoundReductionCheckedChange      = { viewModel.setATTCharacteristicValue(ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(if (it) 0x01.toByte() else 0x00.toByte())) },
                                vendorIdHook                           = state.vendorIdHook,
                                isPremium                              = state.isPremium
                            )
                        }
                        MenuDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val bytes   = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
                            val flipped = try { bytes[1] == 0x02.toByte() } catch (_: Exception) { false }
                            CallControlSettings(hazeState = remember { HazeState() }, flipped = flipped, onCallControlValueChanged = { viewModel.setControlCommandValue(AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG, if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03)) })
                        }
                        MenuDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ConnectionSettings(automaticEarDetectionEnabled = state.automaticEarDetectionEnabled, onAutomaticEarDetectionChanged = { viewModel.setAutomaticEarDetectionEnabled(it) }, automaticConnectionEnabled = state.automaticConnectionEnabled, onAutomaticConnectionChanged = { viewModel.setAutomaticConnectionEnabled(it) })
                        }
                        MenuDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
                            MicrophoneSettings(hazeState = remember { HazeState() }, micModeValue = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(), onMicModeValueChanged = { viewModel.setControlCommandByte(id, it) })
                        }
                        MenuDivider()

                        // Ear detection: disconnect when not wearing (BLUETOOTH_PRIVILEGED-gated)
                        if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(label = stringResource(R.string.disconnect_when_not_wearing), description = stringResource(R.string.disconnect_when_not_wearing_description), checked = appState.disconnectWhenNotWearing, onCheckedChange = appSettingsViewModel::setDisconnectWhenNotWearing, independent = true, enabled = appState.isPremium)
                            }
                            MenuDivider()
                        }

                        // Connect to AirPods — AirPods state
                        MenuSectionHeader(stringResource(R.string.takeover_airpods_state), dark)
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.takeover_disconnected), description = stringResource(R.string.takeover_disconnected_desc), checked = appState.takeoverWhenDisconnected, onCheckedChange = appSettingsViewModel::setTakeoverWhenDisconnected, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_idle), description = stringResource(R.string.takeover_idle_desc), checked = appState.takeoverWhenIdle, onCheckedChange = appSettingsViewModel::setTakeoverWhenIdle, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_music), description = stringResource(R.string.takeover_music_desc), checked = appState.takeoverWhenMusic, onCheckedChange = appSettingsViewModel::setTakeoverWhenMusic, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_call), description = stringResource(R.string.takeover_call_desc), checked = appState.takeoverWhenCall, onCheckedChange = appSettingsViewModel::setTakeoverWhenCall, independent = true, enabled = appState.isPremium)
                        }
                        MenuDivider()

                        // Connect to AirPods — Phone state
                        MenuSectionHeader(stringResource(R.string.takeover_phone_state), dark)
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.takeover_ringing_call), description = stringResource(R.string.takeover_ringing_call_desc), checked = appState.takeoverWhenRingingCall, onCheckedChange = appSettingsViewModel::setTakeoverWhenRingingCall, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_media_start), description = stringResource(R.string.takeover_media_start_desc), checked = appState.takeoverWhenMediaStart, onCheckedChange = appSettingsViewModel::setTakeoverWhenMediaStart, independent = true, enabled = appState.isPremium)
                        }
                    }

                    // ─────────────────────────────────────────────────────
                    // 6. Help & Troubleshooting  ← NEW
                    // ─────────────────────────────────────────────────────
                    MenuCategory("❓  Help & Troubleshooting", dark) {
                        if (!BuildConfig.PLAY_BUILD) {
                            MenuNavRow("Troubleshooting", dark) { navController.navigate("troubleshooting") }
                            MenuDivider()
                        }
                        MenuNavRow("Email Support", dark) { onOpenContact() }
                        MenuDivider()
                        MenuNavRow("Discord Community", dark) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri()))
                        }
                        MenuDivider()
                        MenuNavRow("GitHub Issues", dark) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/kavishdevar/librepods/issues".toUri()))
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding + 16.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  DISCONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun DisconnectedScreen(
    state:         me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState,
    viewModel:     AirPodsViewModel,
    navController: NavController,
    topPadding:    androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState:     HazeState,
    dark:          Boolean,
    onOpenContact: () -> Unit
) {
    val context   = LocalContext.current
    val backdrop  = rememberLayerBackdrop()
    val cardBg    = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black

    val tapCount    = remember { mutableIntStateOf(0) }
    val lastTapTime = remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .drawBackdrop(backdrop = rememberLayerBackdrop(), exportedBackdrop = backdrop, shape = { RoundedCornerShape(0.dp) }, highlight = { Highlight.Ambient.copy(alpha = 0f) }, effects = {})
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime.longValue > 400) tapCount.intValue = 0
                    tapCount.intValue++; lastTapTime.longValue = now
                    if (tapCount.intValue >= 5) { tapCount.intValue = 0; viewModel.activateDemoMode() }
                })
            },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "spacer_top") { Spacer(Modifier.height(topPadding + 16.dp)) }

        item(key = "status") {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.airpods_not_connected), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, color = textColor, fontFamily = SfPro), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(text = stringResource(R.string.airpods_not_connected_description), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Light, color = textColor.copy(alpha = 0.7f), fontFamily = SfPro), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        // Reconnect card
        if (state.connectionSuccessful) {
            item(key = "reconnect_card") {
                Column(modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Reconnect to Previous Device", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.6f), fontFamily = SfPro))
                    Text("Your AirPods were previously connected to this device.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.55f)))
                    StyledButton(onClick = { viewModel.reconnectFromSavedMac() }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.reconnect_to_last_device), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        // Find Nearby card
        item(key = "find_nearby_card") {
            Column(modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find My AirPods", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.6f), fontFamily = SfPro))
                Text("Locate your AirPods using Bluetooth signal proximity.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.55f)))
                StyledButton(onClick = { navController.navigate("proximity_finder") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    Text("Find Nearby", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                }
            }
        }

        // Troubleshooting card
        if (!BuildConfig.PLAY_BUILD) {
            item(key = "troubleshooting_card") {
                Column(modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Troubleshooting", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.6f), fontFamily = SfPro))
                    Text("Can't reconnect? Get help with common connection issues.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.55f)))
                    StyledButton(onClick = { navController.navigate("troubleshooting") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.troubleshooting), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        // Help card
        item(key = "help_card") {
            Column(modifier = Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Get Help", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.6f), fontFamily = SfPro))
                Text("Contact support or join the community.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(alpha = 0.55f)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StyledButton(onClick = onOpenContact, backdrop = backdrop, modifier = Modifier.weight(1f)) {
                        Text("Email", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                    StyledButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri())) }, backdrop = backdrop, modifier = Modifier.weight(1f)) {
                        Text("Discord", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding)) }
    }
}
