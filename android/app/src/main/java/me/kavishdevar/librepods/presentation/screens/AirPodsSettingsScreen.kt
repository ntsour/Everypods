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
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.data.AirPodsPro3
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.StemAction
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
import me.kavishdevar.librepods.presentation.components.SelectItem
import me.kavishdevar.librepods.presentation.components.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledIconButton
import me.kavishdevar.librepods.presentation.components.StyledInputField
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSelectList
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.services.AppListenerService
import me.kavishdevar.librepods.utils.SleepTimer
import me.kavishdevar.librepods.utils.SmartFeaturesPrefs
import me.kavishdevar.librepods.utils.XposedState
import kotlin.io.encoding.ExperimentalEncodingApi

// ─── Design tokens ───────────────────────────────────────────────────────────
private val RootOrange          = Color(0xFFFF9500)
private const val DisabledAlpha = 0.45f
private val SfPro get()         = FontFamily(Font(R.font.sf_pro))

@Composable private fun bodyStyle(dark: Boolean) = TextStyle(
    fontSize = 16.sp, fontFamily = SfPro,
    color = if (dark) Color.White else Color.Black
)
@Composable private fun captionStyle(dark: Boolean) = TextStyle(
    fontSize = 13.sp, fontFamily = SfPro,
    color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
)

// ─── Shared primitives ───────────────────────────────────────────────────────

@Composable
private fun MenuSectionHeader(label: String, dark: Boolean) {
    Box(
        Modifier.fillMaxWidth()
            .background(if (dark) Color(0xFF000000) else Color(0xFFF2F2F7))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(label.uppercase(), style = TextStyle(
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
        ))
    }
}

@Composable
private fun MenuDivider() = HorizontalDivider(
    color = Color(0x30888888), thickness = 0.5.dp,
    modifier = Modifier.padding(horizontal = 16.dp)
)

@Composable
private fun MenuNavRow(label: String, dark: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 10.dp else 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = bodyStyle(dark))
            if (subtitle != null)
                Text(subtitle, style = captionStyle(dark))
        }
        Text("  ›", style = TextStyle(fontSize = 20.sp, fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f)))
    }
}

@Composable
private fun RootRequiredBanner(dark: Boolean) = Row(
    Modifier.fillMaxWidth()
        .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFFFF3E0), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top
) {
    Text("􀎠", style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = RootOrange))
    Text("Requires device root. Bluetooth profile switching is not available without it.",
        style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = RootOrange))
}

@Composable
private fun MenuCategory(label: String, dark: Boolean, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(key = "cat_$label") { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().background(
        if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF), RoundedCornerShape(18.dp)
    )) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
            Text(if (expanded) "  ▲" else "  ▼", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro,
                color = if (dark) Color.White.copy(0.4f) else Color.Black.copy(0.4f)))
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(200))) {
            Column {
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
                content()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  ENTRY POINT
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

    val contactBottomSheet = remember { mutableStateOf(false) }
    val subjectState       = remember { TextFieldState() }
    val descriptionState   = remember { TextFieldState() }
    val backdrop           = rememberLayerBackdrop()

    var deviceName by remember {
        mutableStateOf(TextFieldValue(sharedPreferences.getString("name", state.deviceName).toString()))
    }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "name") deviceName = TextFieldValue(sharedPreferences.getString("name", "AirPods Pro").toString())
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(l)
        onDispose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refreshInitialData() }

    StyledScaffold(
        title = deviceName.text,
        actionButtons = emptyList(),
        snackbarHostState = snackbarHostState
    ) { topPadding, hazeState, bottomPadding ->
        var blockTouches by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            viewModel.demoActivated.collect { blockTouches = true; delay(1000); blockTouches = false }
        }

        if (state.isLocallyConnected) {
            ConnectedScreen(
                state = state, appState = appState,
                viewModel = viewModel, appSettingsViewModel = appSettingsViewModel,
                navController = navController, sharedPrefs = sharedPreferences,
                topPadding = topPadding, bottomPadding = bottomPadding,
                hazeState = hazeState, dark = dark, blockTouches = blockTouches,
                onOpenContact = { contactBottomSheet.value = true }
            )
        } else {
            DisconnectedScreen(
                state = state, viewModel = viewModel, navController = navController,
                topPadding = topPadding, bottomPadding = bottomPadding,
                hazeState = hazeState, dark = dark,
                onOpenContact = { contactBottomSheet.value = true }
            )
        }
    }

    // Contact bottom sheet — shared between connected + disconnected modes
    StyledBottomSheet(visible = contactBottomSheet.value, onDismiss = { contactBottomSheet.value = false }, backdrop = backdrop) { innerBackdrop, progress ->
        val animPad = lerp(16.dp, 2.dp, progress)
        Column(Modifier.fillMaxWidth().padding(horizontal = animPad).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StyledIconButton(icon = "\uDBC0\uDD84", backdrop = innerBackdrop, onClick = { contactBottomSheet.value = false })
                Text(stringResource(R.string.describe_your_issue), style = TextStyle(fontSize = 18.sp, fontFamily = SfPro, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (dark) Color.White else Color.Black))
                StyledIconButton(
                    icon = "\uDBC0\uDE1F", backdrop = innerBackdrop,
                    surfaceColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF),
                    iconTint = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray,
                    enabled = subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty(),
                    onClick = {
                        contactBottomSheet.value = false
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = "mailto:".toUri()
                            putExtra(Intent.EXTRA_EMAIL, arrayOf("contact@kavish.xyz"))
                            putExtra(Intent.EXTRA_SUBJECT, "LibrePods: ${subjectState.text}")
                            putExtra(Intent.EXTRA_TEXT,
                                "${descriptionState.text}\n\n----------" +
                                "\nMANUFACTURER: ${Build.MANUFACTURER}" +
                                "\nMODEL: ${Build.MODEL} (${Build.PRODUCT})" +
                                "\nDISPLAY: ${Build.DISPLAY}" +
                                "\nSDK: ${Build.VERSION.SDK_INT_FULL}" +
                                "\nXposed: ${XposedState.isAvailable}/${XposedState.bluetoothScopeEnabled}" +
                                "\nVERSION: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                                "\nFLAVOR: ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}")
                        }
                        context.startActivity(intent)
                        subjectState.clearText(); descriptionState.clearText()
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            StyledInputField(inputState = subjectState, focusRequester = remember { FocusRequester() }, placeholder = stringResource(R.string.subject))
            Spacer(Modifier.height(12.dp))
            StyledInputField(inputState = descriptionState, focusRequester = remember { FocusRequester() }, placeholder = stringResource(R.string.describe_your_issue), singleLine = false)
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
    appState: me.kavishdevar.librepods.presentation.viewmodel.AppSettingsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    sharedPrefs: SharedPreferences,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    blockTouches: Boolean,
    onOpenContact: () -> Unit
) {
    val context      = LocalContext.current
    val capabilities = state.capabilities
    val hasRoot      = state.hasRootPermissions
    var menuExpanded by rememberSaveable { mutableStateOf(true) }
    val scope        = rememberCoroutineScope()
    val listState    = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .then(if (blockTouches) Modifier.pointerInput(Unit) {
                awaitPointerEventScope { while (true) { val e = awaitPointerEvent(PointerEventPass.Initial); e.changes.forEach { it.consume() } } }
            } else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "spacer_top") { Spacer(Modifier.height(topPadding)) }

        // ── Battery ──────────────────────────────────────────────────────────
        item(key = "battery") {
            BatteryView(
                batteryList = state.battery,
                budsRes = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_case,
                caseRes = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case
            )
        }

        // ── Listening Mode (graphical, always available) ──────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            item(key = "listening_mode") {
                NoiseControlSettings(
                    showOffListeningMode = state.offListeningMode,
                    noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                    onNoiseControlModeChanged = { viewModel.setControlCommandInt(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE, it) }
                )
            }
        }

        // ── Transparency (Xposed-only) ────────────────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE) && state.vendorIdHook) {
            item(key = "transparency_nav") {
                NavigationButton(to = "transparency_customization", name = stringResource(R.string.customize_transparency_mode), navController = navController)
            }
        }

        // ── Upgrade banner ────────────────────────────────────────────────
        if (!state.isPremium) {
            item(key = "upgrade") {
                StyledButton(onClick = { navController.navigate("purchase_screen") }, backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(), maxScale = 0.05f,
                    surfaceColor = if (dark) Color(0xFF916100) else Color(0xFFE59900)) {
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
                    Modifier.fillMaxWidth()
                        .clickable(remember { MutableInteractionSource() }, null) { menuExpanded = !menuExpanded }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (menuExpanded) "▲  More Options  ▲" else "▼  More Options  ▼",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro,
                            color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
                }
                HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        //  MENU BODY
        // ══════════════════════════════════════════════════════════════════
        item(key = "menu_body") {
            AnimatedVisibility(menuExpanded, enter = expandVertically(tween(300)) + fadeIn(tween(250)), exit = shrinkVertically(tween(300)) + fadeOut(tween(250))) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // ─── 1. AirPods Controls ──────────────────────────────
                    MenuCategory("🎧  AirPods Controls", dark) {
                        if (capabilities.contains(Capability.STEM_CONFIG)) {

                            // ── Helper: read single/double/triple action from prefs ──
                            fun readAction(key: String, default: StemAction): StemAction =
                                runCatching { StemAction.valueOf(sharedPrefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

                            // ── Build SelectItems for a bud's press type ────────────
                            @Composable
                            fun actionItems(side: String, pressType: AACPManager.Companion.StemPressType): List<SelectItem> {
                                val prefKey = "${side}_${pressType.name.lowercase()}_action"
                                val defaultAction = StemAction.defaultActions[pressType] ?: StemAction.PLAY_PAUSE
                                // For long press we also track in UiState so read from there
                                val currentAction = if (pressType == AACPManager.Companion.StemPressType.LONG_PRESS) {
                                    if (side == "left") state.leftAction else state.rightAction
                                } else {
                                    readAction(prefKey, defaultAction)
                                }
                                return listOf(
                                    SelectItem("Play / Pause",
                                        selected = currentAction == StemAction.PLAY_PAUSE,
                                        onClick = { viewModel.setPressAction(side, pressType, StemAction.PLAY_PAUSE) }),
                                    SelectItem("Next Track",
                                        selected = currentAction == StemAction.NEXT_TRACK,
                                        onClick = { viewModel.setPressAction(side, pressType, StemAction.NEXT_TRACK) }),
                                    SelectItem("Previous Track",
                                        selected = currentAction == StemAction.PREVIOUS_TRACK,
                                        onClick = { viewModel.setPressAction(side, pressType, StemAction.PREVIOUS_TRACK) }),
                                    SelectItem(stringResource(R.string.digital_assistant),
                                        selected = currentAction == StemAction.DIGITAL_ASSISTANT,
                                        enabled = state.isPremium,
                                        onClick = { viewModel.setPressAction(side, pressType, StemAction.DIGITAL_ASSISTANT) }),
                                    SelectItem("Mute / Unmute Call",
                                        selected = currentAction == StemAction.MUTE_CALL,
                                        onClick = { viewModel.setPressAction(side, pressType, StemAction.MUTE_CALL) }),
                                    SelectItem(stringResource(R.string.noise_control),
                                        selected = currentAction == StemAction.CYCLE_NOISE_CONTROL_MODES,
                                        onClick = { viewModel.setPressAction(side, pressType, StemAction.CYCLE_NOISE_CONTROL_MODES) })
                                )
                            }

                            // ── Listening mode cycle sub-options ────────────────────
                            // Shown when a bud's long press is set to CYCLE_NOISE_CONTROL_MODES
                            @Composable
                            fun ListeningModeCycleOptions() {
                                val currentByte = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
                                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                                    Text("Modes to cycle through:", style = captionStyle(dark))
                                    Spacer(Modifier.height(6.dp))
                                    StyledSelectList(items = buildList {
                                        if (state.offListeningMode) add(
                                            SelectItem(stringResource(R.string.off),
                                                description = stringResource(R.string.listening_mode_off_description),
                                                selected = (currentByte and 0x01) != 0,
                                                onClick = { viewModel.toggleListeningMode(0x01) })
                                        )
                                        add(SelectItem(stringResource(R.string.transparency),
                                            description = stringResource(R.string.listening_mode_transparency_description),
                                            selected = (currentByte and 0x04) != 0,
                                            onClick = { viewModel.toggleListeningMode(0x04) }))
                                        add(SelectItem(stringResource(R.string.adaptive),
                                            description = stringResource(R.string.listening_mode_adaptive_description),
                                            selected = (currentByte and 0x08) != 0,
                                            onClick = { viewModel.toggleListeningMode(0x08) }))
                                        add(SelectItem(stringResource(R.string.noise_cancellation),
                                            description = stringResource(R.string.listening_mode_noise_cancellation_description),
                                            selected = (currentByte and 0x02) != 0,
                                            onClick = { viewModel.toggleListeningMode(0x02) }))
                                    })
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource(R.string.press_and_hold_noise_control_description), style = captionStyle(dark))
                                }
                            }

                            // ── LEFT BUD ────────────────────────────────────────────
                            MenuSectionHeader("Left Bud — Single Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("left", AACPManager.Companion.StemPressType.SINGLE_PRESS))
                            }
                            MenuDivider()
                            MenuSectionHeader("Left Bud — Double Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("left", AACPManager.Companion.StemPressType.DOUBLE_PRESS))
                            }
                            MenuDivider()
                            MenuSectionHeader("Left Bud — Triple Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("left", AACPManager.Companion.StemPressType.TRIPLE_PRESS))
                            }
                            MenuDivider()
                            MenuSectionHeader("Left Bud — Long Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("left", AACPManager.Companion.StemPressType.LONG_PRESS))
                            }
                            if (state.leftAction == StemAction.CYCLE_NOISE_CONTROL_MODES) {
                                ListeningModeCycleOptions()
                            }
                            MenuDivider()

                            // ── RIGHT BUD ───────────────────────────────────────────
                            MenuSectionHeader("Right Bud — Single Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("right", AACPManager.Companion.StemPressType.SINGLE_PRESS))
                            }
                            MenuDivider()
                            MenuSectionHeader("Right Bud — Double Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("right", AACPManager.Companion.StemPressType.DOUBLE_PRESS))
                            }
                            MenuDivider()
                            MenuSectionHeader("Right Bud — Triple Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("right", AACPManager.Companion.StemPressType.TRIPLE_PRESS))
                            }
                            MenuDivider()
                            MenuSectionHeader("Right Bud — Long Press", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSelectList(items = actionItems("right", AACPManager.Companion.StemPressType.LONG_PRESS))
                            }
                            if (state.rightAction == StemAction.CYCLE_NOISE_CONTROL_MODES) {
                                ListeningModeCycleOptions()
                            }
                            MenuDivider()

                            // ── CALL CONTROLS ───────────────────────────────────────
                            // Kept here: conceptually this is stem behaviour during calls
                            val bytes = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
                            val flipped = try { bytes[1] == 0x02.toByte() } catch (_: Exception) { false }
                            CallControlSettings(
                                hazeState = remember { HazeState() },
                                flipped = flipped,
                                onCallControlValueChanged = {
                                    viewModel.setControlCommandValue(
                                        AACPManager.Companion.ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
                                        if (it) byteArrayOf(0x00, 0x02) else byteArrayOf(0x00, 0x03)
                                    )
                                }
                            )

                        } else {
                            Column(Modifier.padding(16.dp)) {
                                Text("Stem controls not available on this model.", style = captionStyle(dark))
                            }
                        }
                    }

                    // ─── 2. AirPods Settings ──────────────────────────────
                    MenuCategory("⚙️  AirPods Settings", dark) {

                        MenuNavRow("Device Name", dark, subtitle = state.deviceName) { navController.navigate("rename") }
                        MenuDivider()

                        val hasHA  = state.instance?.model?.capabilities?.contains(Capability.HEARING_AID) == true
                        val hasPPE = state.instance?.model?.capabilities?.contains(Capability.PPE) == true
                        if (hasHA || hasPPE) {
                            MenuNavRow("Hearing Aid", dark) { navController.navigate("hearing_aid") }
                            MenuDivider()
                            MenuNavRow("Hearing Aid Adjustments", dark) { navController.navigate("hearing_aid_adjustments") }
                            MenuDivider()
                        }

                        // Hearing Protection — inlined (was a separate screen)
                        if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION) || hasPPE) {
                            MenuSectionHeader("Hearing Protection", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                if (state.vendorIdHook) {
                                    StyledToggle(
                                        label = stringResource(R.string.loud_sound_reduction),
                                        description = stringResource(R.string.loud_sound_reduction_description),
                                        checked = state.loudSoundReductionEnabled,
                                        onCheckedChange = { viewModel.setATTCharacteristicValue(ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(if (it) 1 else 0)) },
                                        independent = true, enabled = state.isPremium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                StyledToggle(
                                    label = stringResource(R.string.ppe),
                                    description = stringResource(R.string.workspace_use_description),
                                    checked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG]?.getOrNull(0)?.toInt() == 1,
                                    onCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.PPE_TOGGLE_CONFIG, it) },
                                    independent = true, enabled = state.isPremium
                                )
                            }
                            MenuDivider()
                        }

                        MenuNavRow("Accessibility", dark) { navController.navigate("accessibility") }
                        MenuDivider()

                        // Conversation Awareness
                        MenuSectionHeader("Conversation Awareness", dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.conversational_awareness_pause_music), description = stringResource(R.string.conversational_awareness_pause_music_description), checked = appState.conversationalAwarenessPauseMusicEnabled, onCheckedChange = appSettingsViewModel::setConversationalAwarenessPauseMusicEnabled, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.relative_conversational_awareness_volume), description = stringResource(R.string.relative_conversational_awareness_volume_description), checked = appState.relativeConversationalAwarenessVolumeEnabled, onCheckedChange = appSettingsViewModel::setRelativeConversationalAwarenessVolumeEnabled, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledSlider(label = stringResource(R.string.conversational_awareness_volume), value = appState.conversationalAwarenessVolume, valueRange = 10f..85f, snapPoints = listOf(44f), startLabel = "10%", endLabel = "85%", onValueChange = { appSettingsViewModel.setConversationalAwarenessVolume(it) }, independent = true, enabled = appState.isPremium)
                        }
                        MenuDivider()

                        // Root-required: Disconnect
                        MenuSectionHeader("🔒  Bluetooth Control (Root Required)", dark)
                        Column(Modifier.fillMaxWidth().alpha(if (hasRoot) 1f else DisabledAlpha.toFloat()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!hasRoot) RootRequiredBanner(dark)
                            StyledButton(
                                onClick = { if (hasRoot) viewModel.disconnect() },
                                backdrop = rememberLayerBackdrop(), isInteractive = hasRoot,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                            ) {
                                Text(stringResource(R.string.disconnect), style = TextStyle(
                                    fontSize = 16.sp, fontWeight = FontWeight.Normal, fontFamily = SfPro,
                                    color = if (hasRoot) { if (dark) Color(0xFF0091FF) else Color(0xFF0088FF) } else { if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f) }),
                                    textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    // ─── 3. Smart Features ────────────────────────────────
                    MenuCategory("✨  AirPods Smart Features", dark) {

                        MenuNavRow("Notification Announcements", dark) { navController.navigate("notification_announcements") }
                        MenuDivider()

                        if (capabilities.contains(Capability.HEAD_GESTURES)) {
                            val headOn = sharedPrefs.getBoolean("head_gestures_enabled", true) &&
                                (sharedPrefs.getBoolean("head_gestures_answer_call", true) || sharedPrefs.getBoolean("head_gestures_mute_call", true))
                            MenuNavRow("Head Gestures — ${if (headOn) "On" else "Off"}", dark) { navController.navigate("head_tracking") }
                            MenuDivider()
                        }

                        // Adaptive Audio — inlined (was AdaptiveStrengthScreen)
                        val model = state.instance?.model ?: AirPodsPro3()
                        if (model.capabilities.contains(Capability.ADAPTIVE_VOLUME)) {
                            MenuSectionHeader("Adaptive Audio", dark)
                            val adaptiveVal = remember {
                                mutableFloatStateOf(100f - (state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toFloat() ?: 50f))
                            }
                            var adaptiveJob by remember { mutableStateOf<Job?>(null) }
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                StyledSlider(
                                    label = stringResource(R.string.customize_adaptive_audio),
                                    value = adaptiveVal.floatValue,
                                    onValueChange = {
                                        adaptiveVal.floatValue = it
                                        adaptiveJob?.cancel()
                                        adaptiveJob = scope.launch {
                                            delay(150)
                                            viewModel.setControlCommandValue(AACPManager.Companion.ControlCommandIdentifiers.AUTO_ANC_STRENGTH, byteArrayOf((100 - it).toInt().toByte()))
                                        }
                                    },
                                    valueRange = 0f..100f,
                                    snapPoints = listOf(0f, 50f, 100f),
                                    startIcon = "􀊥", endIcon = "􀊩",
                                    independent = true,
                                    description = stringResource(R.string.adaptive_audio_description),
                                    enabled = state.isPremium
                                )
                            }
                            MenuDivider()
                        }

                        // Camera Control — inlined (was CameraControlScreen)
                        if (capabilities.contains(Capability.STEM_CONFIG) && !BuildConfig.PLAY_BUILD) {
                            MenuSectionHeader("Camera Control", dark)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                val currentCameraAction by viewModel.cameraAction.collectAsState()
                                fun isAppListenerEnabled(): Boolean {
                                    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                                    val sc = ComponentName(context, AppListenerService::class.java)
                                    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                                        .any { it.resolveInfo.serviceInfo.packageName == sc.packageName && it.resolveInfo.serviceInfo.name == sc.className }
                                }
                                fun handleCam(action: me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType?) {
                                    if (action != null && !isAppListenerEnabled()) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    else viewModel.setCameraAction(action)
                                }
                                StyledSelectList(items = listOf(
                                    SelectItem("Off", selected = currentCameraAction == null, onClick = { handleCam(null) }),
                                    SelectItem("Press once", selected = currentCameraAction == me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType.SINGLE_PRESS, onClick = { handleCam(me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType.SINGLE_PRESS) }),
                                    SelectItem("Press and hold", selected = currentCameraAction == me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType.LONG_PRESS, onClick = { handleCam(me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType.LONG_PRESS) })
                                ))
                            }
                            MenuDivider()
                        }

                        MenuNavRow("ANC Profiles", dark) { navController.navigate("anc_profiles") }
                        MenuDivider()

                        // Off Listening Mode
                        if (capabilities.contains(Capability.LOUD_SOUND_REDUCTION)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(label = stringResource(R.string.off_listening_mode), description = stringResource(R.string.off_listening_mode_description), checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(), onCheckedChange = viewModel::setOffListeningMode)
                            }
                            MenuDivider()
                        }

                        // Sleep Detection + Optimized Charging
                        MenuSectionHeader("Automation", dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            if (capabilities.contains(Capability.SLEEP_DETECTION)) {
                                val id = AACPManager.Companion.ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG
                                StyledToggle(label = stringResource(R.string.sleep_detection), checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(), onCheckedChange = { viewModel.setControlCommandBoolean(id, it) }, independent = true, enabled = state.isPremium)
                                Spacer(Modifier.height(4.dp))
                            }
                            StyledToggle(label = stringResource(R.string.optimized_charging), description = stringResource(R.string.optimized_charging_description), checked = state.dynamicEndOfCharge, onCheckedChange = viewModel::setDynamicEndOfCharge, independent = true)
                        }
                        MenuDivider()

                        // Smart Features — inlined (Resume, Battery Alerts, Sleep Timer)
                        MenuSectionHeader("Smart Features", dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            var autoResume by remember { mutableStateOf(SmartFeaturesPrefs.autoResumeAfterCall(context)) }
                            StyledToggle(label = "Resume media after call", checked = autoResume, independent = true, onCheckedChange = {
                                autoResume = it
                                SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_AUTO_RESUME_AFTER_CALL, it).apply()
                            })
                            Spacer(Modifier.height(4.dp))

                            var batteryAlerts by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertsEnabled(context)) }
                            var batteryThreshold by remember { mutableStateOf(SmartFeaturesPrefs.batteryAlertThreshold(context)) }
                            StyledToggle(label = "Speak when battery is low", checked = batteryAlerts, independent = true, onCheckedChange = {
                                batteryAlerts = it
                                SmartFeaturesPrefs.prefs(context).edit().putBoolean(SmartFeaturesPrefs.KEY_BATTERY_ALERTS_ENABLED, it).apply()
                            })
                            if (batteryAlerts) {
                                Spacer(Modifier.height(4.dp))
                                StyledSlider(
                                    label = "Alert threshold",
                                    value = batteryThreshold.toFloat(),
                                    valueRange = 5f..80f,
                                    snapPoints = listOf(10f, 20f, 30f, 40f),
                                    startLabel = "5%", endLabel = "80%",
                                    onValueChange = {
                                        batteryThreshold = it.toInt()
                                        SmartFeaturesPrefs.prefs(context).edit().putInt(SmartFeaturesPrefs.KEY_BATTERY_ALERT_THRESHOLD, it.toInt()).apply()
                                    },
                                    independent = true
                                )
                            }
                        }
                        MenuDivider()

                        // Sleep Timer
                        MenuSectionHeader("Sleep Timer", dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            var sleepRemainingMs by remember { mutableLongStateOf(SleepTimer.remainingMs(context)) }
                            DisposableEffect(Unit) {
                                val l: () -> Unit = { sleepRemainingMs = SleepTimer.remainingMs(context) }
                                SleepTimer.addListener(l)
                                onDispose { SleepTimer.removeListener(l) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 30, 45, 60, 90).forEach { mins ->
                                    StyledButton(
                                        onClick = { SleepTimer.start(context, mins * 60_000L); sleepRemainingMs = SleepTimer.remainingMs(context) },
                                        backdrop = rememberLayerBackdrop(),
                                        modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                                    ) {
                                        Text("${mins}m", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                                    }
                                }
                            }
                            if (sleepRemainingMs > 0L) {
                                val mins = (sleepRemainingMs / 60_000L).toInt()
                                val secs = ((sleepRemainingMs % 60_000L) / 1000L).toInt()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("⏱ ${mins}m ${secs}s remaining", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.7f) else Color.Black.copy(0.7f)))
                                    StyledButton(onClick = { SleepTimer.cancel(context); sleepRemainingMs = 0L }, backdrop = rememberLayerBackdrop(), modifier = Modifier.heightIn(min = 36.dp)) {
                                        Text("Cancel", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                                    }
                                }
                            } else {
                                Text("No timer running", style = captionStyle(dark))
                            }
                        }
                    }

                    // ─── 4. App Settings ──────────────────────────────────
                    MenuCategory("📱  App Settings", dark) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            StyledToggle(label = stringResource(R.string.show_phone_battery_in_widget), description = stringResource(R.string.show_phone_battery_in_widget_description), checked = appState.showPhoneBatteryInWidget, onCheckedChange = appSettingsViewModel::setShowPhoneBatteryInWidget, independent = true, enabled = appState.isPremium)
                        }
                        MenuDivider()
                        MenuSectionHeader("Pop-up Animations", dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.show_bottom_sheet_popup), description = stringResource(R.string.show_bottom_sheet_popup_description), checked = appState.showBottomSheetPopup, onCheckedChange = appSettingsViewModel::setShowBottomSheetPopup, independent = true)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.show_island_popup), description = stringResource(R.string.show_island_popup_description), checked = appState.showIslandPopup, onCheckedChange = appSettingsViewModel::setShowIslandPopup, independent = true)
                        }
                        if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                            MenuDivider()
                            val restartMsg = stringResource(R.string.found_offset_restart_bluetooth)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(label = stringResource(R.string.act_as_an_apple_device) + " (${stringResource(R.string.requires_xposed)})", description = stringResource(R.string.act_as_an_apple_device_description), checked = appState.vendorIdHook, onCheckedChange = { Toast.makeText(context, restartMsg, Toast.LENGTH_SHORT).show(); appSettingsViewModel.setVendorIdHook(it) }, independent = true, enabled = appState.isPremium)
                            }
                        }
                        MenuDivider()
                        MenuNavRow("Permissions", dark) { navController.navigate("permissions") }
                        MenuDivider()
                        MenuNavRow("Open Source Licenses", dark) { navController.navigate("open_source_licenses") }
                    }

                    // ─── 5. Audio & Connection ────────────────────────────
                    MenuCategory("🔊  Audio & Connection", dark) {
                        val m = state.instance?.model ?: AirPodsPro3()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            AudioSettings(
                                navController = navController,
                                adaptiveVolumeCapability = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                                conversationalAwarenessCapability = m.capabilities.contains(Capability.CONVERSATION_AWARENESS),
                                loudSoundReductionCapability = m.capabilities.contains(Capability.LOUD_SOUND_REDUCTION),
                                adaptiveAudioCapability = m.capabilities.contains(Capability.ADAPTIVE_VOLUME),
                                adaptiveVolumeChecked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte(),
                                onAdaptiveVolumeCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG, it) },
                                conversationalAwarenessChecked = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte() && state.isPremium,
                                onConversationalAwarenessCheckedChange = { viewModel.setControlCommandBoolean(AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG, it) },
                                loudSoundReductionChecked = state.loudSoundReductionEnabled,
                                onLoudSoundReductionCheckedChange = { viewModel.setATTCharacteristicValue(ATTHandles.LOUD_SOUND_REDUCTION, byteArrayOf(if (it) 0x01.toByte() else 0x00.toByte())) },
                                vendorIdHook = state.vendorIdHook, isPremium = state.isPremium
                            )
                        }
                        MenuDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ConnectionSettings(automaticEarDetectionEnabled = state.automaticEarDetectionEnabled, onAutomaticEarDetectionChanged = { viewModel.setAutomaticEarDetectionEnabled(it) }, automaticConnectionEnabled = state.automaticConnectionEnabled, onAutomaticConnectionChanged = { viewModel.setAutomaticConnectionEnabled(it) })
                        }
                        MenuDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            val id = AACPManager.Companion.ControlCommandIdentifiers.MIC_MODE
                            MicrophoneSettings(hazeState = remember { HazeState() }, micModeValue = state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte(), onMicModeValueChanged = { viewModel.setControlCommandByte(id, it) })
                        }
                        if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                            MenuDivider()
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                StyledToggle(label = stringResource(R.string.disconnect_when_not_wearing), description = stringResource(R.string.disconnect_when_not_wearing_description), checked = appState.disconnectWhenNotWearing, onCheckedChange = appSettingsViewModel::setDisconnectWhenNotWearing, independent = true, enabled = appState.isPremium)
                            }
                        }
                        MenuDivider()
                        MenuSectionHeader(stringResource(R.string.takeover_airpods_state), dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.takeover_disconnected), description = stringResource(R.string.takeover_disconnected_desc), checked = appState.takeoverWhenDisconnected, onCheckedChange = appSettingsViewModel::setTakeoverWhenDisconnected, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_idle), description = stringResource(R.string.takeover_idle_desc), checked = appState.takeoverWhenIdle, onCheckedChange = appSettingsViewModel::setTakeoverWhenIdle, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_music), description = stringResource(R.string.takeover_music_desc), checked = appState.takeoverWhenMusic, onCheckedChange = appSettingsViewModel::setTakeoverWhenMusic, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_call), description = stringResource(R.string.takeover_call_desc), checked = appState.takeoverWhenCall, onCheckedChange = appSettingsViewModel::setTakeoverWhenCall, independent = true, enabled = appState.isPremium)
                        }
                        MenuDivider()
                        MenuSectionHeader(stringResource(R.string.takeover_phone_state), dark)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            StyledToggle(label = stringResource(R.string.takeover_ringing_call), description = stringResource(R.string.takeover_ringing_call_desc), checked = appState.takeoverWhenRingingCall, onCheckedChange = appSettingsViewModel::setTakeoverWhenRingingCall, independent = true, enabled = appState.isPremium)
                            Spacer(Modifier.height(4.dp))
                            StyledToggle(label = stringResource(R.string.takeover_media_start), description = stringResource(R.string.takeover_media_start_desc), checked = appState.takeoverWhenMediaStart, onCheckedChange = appSettingsViewModel::setTakeoverWhenMediaStart, independent = true, enabled = appState.isPremium)
                        }
                    }

                    // ─── 6. Help & Troubleshooting ────────────────────────
                    MenuCategory("❓  Help & Troubleshooting", dark) {
                        // AirPods info (moved from main page)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            AboutCard(navController = navController, modelName = state.modelName, actualModel = state.actualModel, serialNumbers = state.serialNumbers, version = state.version3)
                        }
                        MenuDivider()
                        // App + device info (moved from App Settings)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            DeviceInfoCard()
                            Spacer(Modifier.height(8.dp))
                            AppInfoCard()
                        }
                        MenuDivider()
                        MenuNavRow("Version Info", dark) { navController.navigate("version_info") }
                        MenuDivider()
                        if (!BuildConfig.PLAY_BUILD) {
                            MenuNavRow("Troubleshooting", dark) { navController.navigate("troubleshooting") }
                            MenuDivider()
                        }
                        MenuNavRow("Email Support", dark) { onOpenContact() }
                        MenuDivider()
                        MenuNavRow("Discord Community", dark) { context.startActivity(Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri())) }
                        MenuDivider()
                        MenuNavRow("GitHub Issues", dark) { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/kavishdevar/librepods/issues".toUri())) }
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
    state: me.kavishdevar.librepods.presentation.viewmodel.AirPodsUiState,
    viewModel: AirPodsViewModel,
    navController: NavController,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    onOpenContact: () -> Unit
) {
    val context   = LocalContext.current
    val backdrop  = rememberLayerBackdrop()
    val cardBg    = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black
    val tapCount  = remember { mutableIntStateOf(0) }
    val lastTap   = remember { mutableLongStateOf(0L) }

    LazyColumn(
        Modifier.fillMaxSize()
            .drawBackdrop(rememberLayerBackdrop(), exportedBackdrop = backdrop, shape = { RoundedCornerShape(0.dp) }, highlight = { Highlight.Ambient.copy(alpha = 0f) }, effects = {})
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    val now = System.currentTimeMillis()
                    if (now - lastTap.longValue > 400) tapCount.intValue = 0
                    tapCount.intValue++; lastTap.longValue = now
                    if (tapCount.intValue >= 5) { tapCount.intValue = 0; viewModel.activateDemoMode() }
                })
            },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "spacer_top") { Spacer(Modifier.height(topPadding + 16.dp)) }

        item(key = "status") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.airpods_not_connected), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, color = textColor, fontFamily = SfPro), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.airpods_not_connected_description), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Light, color = textColor.copy(0.7f), fontFamily = SfPro), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.connectionSuccessful) {
            item(key = "reconnect") {
                Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Reconnect to Previous Device", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    Text("Your AirPods were previously connected to this device.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    StyledButton(onClick = { viewModel.reconnectFromSavedMac() }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.reconnect_to_last_device), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        item(key = "find_nearby") {
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find My AirPods", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                Text("Locate your AirPods using Bluetooth signal proximity.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                StyledButton(onClick = { navController.navigate("proximity_finder") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    Text("Find Nearby", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                }
            }
        }

        if (!BuildConfig.PLAY_BUILD) {
            item(key = "troubleshooting") {
                Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Troubleshooting", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                    Text("Can't reconnect? Get help with common connection issues.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    StyledButton(onClick = { navController.navigate("troubleshooting") }, backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.troubleshooting), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        item(key = "help") {
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Get Help", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(0.6f), fontFamily = SfPro))
                Text("Contact support or join the community.", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StyledButton(onClick = onOpenContact, backdrop = backdrop, modifier = Modifier.weight(1f)) { Text("Email", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor)) }
                    StyledButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://discord.gg/Ts4wupXcmc".toUri())) }, backdrop = backdrop, modifier = Modifier.weight(1f)) { Text("Discord", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor)) }
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding)) }
    }
}
