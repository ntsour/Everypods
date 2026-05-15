/*
    ProPods - AirPods liberated from Apple’s ecosystem
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

@file:OptIn(ExperimentalEncodingApi::class)

package io.nikos.propods

// import io.nikos.propods.screens.Onboarding
// import io.nikos.propods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import io.nikos.propods.data.AirPodsNotifications
import io.nikos.propods.data.ControlCommandRepository
import io.nikos.propods.presentation.components.AppInfoCard
import io.nikos.propods.presentation.components.ConfirmationDialog
import io.nikos.propods.presentation.components.DeviceInfoCard
import io.nikos.propods.presentation.components.SelectItem
import io.nikos.propods.presentation.components.StyledBottomSheet
import io.nikos.propods.presentation.components.StyledButton
import io.nikos.propods.presentation.components.StyledIconButton
import io.nikos.propods.presentation.components.StyledInputField
import io.nikos.propods.presentation.components.StyledSelectList
import io.nikos.propods.presentation.screens.AccessibilitySettingsScreen
import io.nikos.propods.presentation.screens.AdaptiveStrengthScreen
import io.nikos.propods.presentation.screens.AirPodsSettingsScreen
import io.nikos.propods.presentation.screens.AppSettingsScreen
import io.nikos.propods.presentation.screens.CameraControlScreen
import io.nikos.propods.presentation.screens.DebugScreen
import io.nikos.propods.presentation.screens.HeadTrackingScreen
import io.nikos.propods.presentation.screens.HearingAidAdjustmentsScreen
import io.nikos.propods.presentation.screens.HearingAidScreen
import io.nikos.propods.presentation.screens.HearingProtectionScreen
import io.nikos.propods.presentation.screens.LongPress
import io.nikos.propods.presentation.screens.OpenSourceLicensesScreen
import io.nikos.propods.presentation.screens.PurchaseScreen
import io.nikos.propods.presentation.screens.RenameScreen
import io.nikos.propods.presentation.screens.TransparencySettingsScreen
import io.nikos.propods.presentation.screens.TroubleshootingScreen
import io.nikos.propods.presentation.screens.UpdateHearingTestScreen

import io.nikos.propods.presentation.screens.AnnouncementAppPickerScreen
import io.nikos.propods.presentation.screens.AppPermissionsScreen
import io.nikos.propods.presentation.screens.NotificationAnnouncementsScreen
import io.nikos.propods.presentation.screens.ProximityFinderScreen
import io.nikos.propods.presentation.screens.VersionScreen
import io.nikos.propods.presentation.screens.CategoryScreen
import io.nikos.propods.presentation.screens.PressActionsScreen
import io.nikos.propods.presentation.screens.VolumeControlScreen
import io.nikos.propods.presentation.screens.CallControlsScreen
import io.nikos.propods.presentation.screens.ConversationAwarenessScreen
import io.nikos.propods.presentation.screens.BluetoothControlScreen
import io.nikos.propods.presentation.screens.AudioSettingsScreen
import io.nikos.propods.presentation.screens.ConnectionSettingsScreen
import io.nikos.propods.presentation.screens.MicrophoneSettingsScreen
import io.nikos.propods.presentation.screens.ListeningModeConfigScreen
import io.nikos.propods.presentation.screens.SmartAutomationScreen
import io.nikos.propods.presentation.screens.SleepTimerScreen
import io.nikos.propods.presentation.screens.PhoneBatteryScreen
import io.nikos.propods.presentation.screens.PopupAnimationsScreen
import io.nikos.propods.presentation.screens.XposedSettingsScreen
import io.nikos.propods.presentation.screens.EmailSupportScreen
import io.nikos.propods.presentation.screens.DiscordCommunityScreen
import io.nikos.propods.presentation.screens.GitHubIssuesScreen
import io.nikos.propods.presentation.screens.GymPressActionsScreen
import io.nikos.propods.presentation.screens.GymTimerScreen
import io.nikos.propods.presentation.theme.ProPodsTheme
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel
import io.nikos.propods.presentation.viewmodel.AppSettingsViewModel
import io.nikos.propods.presentation.viewmodel.PurchaseViewModel
import io.nikos.propods.services.AirPodsService
import io.nikos.propods.services.CallNotifListener
import io.nikos.propods.utils.XposedState
import io.nikos.propods.utils.isSupported
import kotlin.io.encoding.ExperimentalEncodingApi

lateinit var serviceConnection: ServiceConnection
lateinit var connectionStatusReceiver: BroadcastReceiver

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    companion object {
        init {
            if (XposedState.isAvailable && XposedState.bluetoothScopeEnabled) {
                System.loadLibrary("l2c_fcr_hook")
            }
        }
    }

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ProPodsTheme {
                Main()
            }
        }
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
            Log.d("MainActivity", "Unbound service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unbinding service: $e")
        }
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        sendBroadcast(Intent(AirPodsNotifications.DISCONNECT_RECEIVERS))
        super.onDestroy()
    }

    override fun onStop() {
        try {
            unbindService(serviceConnection)
            Log.d("MainActivity", "Unbound service")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unbinding service: $e")
        }
        try {
            unregisterReceiver(connectionStatusReceiver)
            Log.d("MainActivity", "Unregistered receiver")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while unregistering receiver: $e")
        }
        super.onStop()
    }
}

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main() {
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)
    if (!isSupported(sharedPreferences) && !XposedState.bluetoothScopeEnabled) {
        val showDialog = remember { mutableStateOf(false) }
        val showPlayBypassVisible = remember { mutableStateOf(false) }
        val hazeState = rememberHazeState()
        val backdrop = rememberLayerBackdrop()
        val isDarkTheme = isSystemInDarkTheme()
        val textColor = if (isDarkTheme) Color.White else Color.Black
        val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .layerBackdrop(backdrop)
                .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7)),
            contentAlignment = Alignment.Center
        ) {
            Column (
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement
                    .spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Column(
                    modifier = Modifier,
                    verticalArrangement = Arrangement
                        .spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.not_supported),
                        style = TextStyle(
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(backgroundColor, RoundedCornerShape(28.dp))
                            .clip(RoundedCornerShape(28.dp))
                    ) {
                        Text(
                            text = stringResource(R.string.check_the_repository_for_more_info),
                            style = TextStyle(
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) Color.White else Color.Black,
                                fontSize = 16.sp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 16.dp)
                        )
                    }
                    StyledButton(
                        onClick = { showDialog.value = true },
                        backdrop = rememberLayerBackdrop(),
                        modifier = Modifier
                            .fillMaxWidth(),
                        isInteractive = false,
                        surfaceColor = if (isDarkTheme) Color(0xFF862424) else Color(0xFFC94646)
                    ) {
                        Text(
                            text = stringResource(R.string.bypass_compatibility_check),
                            style = TextStyle(
                                fontFamily = FontFamily(Font(R.font.sf_pro)),
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                fontSize = 16.sp
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    DeviceInfoCard()
                    AppInfoCard()
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }

        ConfirmationDialog(
            showDialog = showDialog,
            title = stringResource(R.string.bypass_compatibility_check),
            message = stringResource(R.string.bypass_compatiblity_check_confirmation),
            confirmText = stringResource(R.string.yes),
            dismissText = stringResource(R.string.no),
            onConfirm = {
                showDialog.value = false
                if (BuildConfig.PLAY_BUILD) {
                    showPlayBypassVisible.value = true
                } else {
                    sharedPreferences.edit {
                        putBoolean("bypass_device_check.v2", true)
                    }
                    val intent = Intent(context, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                }
            },
            onDismiss = {
                showDialog.value = false
            },
            backdrop = backdrop
//            hazeState = hazeState
        )

        if (BuildConfig.PLAY_BUILD) {
            StyledBottomSheet(
                visible = showPlayBypassVisible.value,
                onDismiss = {
                    showPlayBypassVisible.value = false
                    showDialog.value = true
                },
                backdrop = backdrop
            ) { innerBackdrop, _ ->
                val contentColor = if (isDarkTheme) Color.White else Color.Black

                var acknowledged by remember { mutableStateOf(false) }
                val inputState = rememberTextFieldState("")

                val isValid = acknowledged && inputState.text.trim() == "OK"

                val sfPro = FontFamily(Font(R.font.sf_pro))

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.bypass_compatibility_check),
                        style = TextStyle(
                            fontFamily = sfPro,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = contentColor
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Text(
                        text = stringResource(R.string.compatibility_play_dialog_confirmation),
                        style = TextStyle(
                            fontFamily = sfPro,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = contentColor
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    StyledSelectList(
                        items = listOf(
                            SelectItem(
                                name = stringResource(R.string.read_compatibility_requirements),
                                selected = acknowledged,
                                onClick = { acknowledged = !acknowledged }
                            )
                        )
                    )

                    val focusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }

                    StyledInputField(
                        inputState = inputState,
                        focusRequester = focusRequester,
                        placeholder = stringResource(R.string.type_ok_to_continue, "OK")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StyledButton(
                            onClick = { showPlayBypassVisible.value = false },
                            backdrop = innerBackdrop,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.no),
                                style = TextStyle(
                                    fontFamily = sfPro,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = contentColor
                                )
                            )
                        }
                        StyledButton(
                            onClick =  {
                                showPlayBypassVisible.value = false
                                sharedPreferences.edit {
                                    putBoolean("bypass_device_check.v2", true)
                                    val intent = Intent(context, MainActivity::class.java)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    context.startActivity(intent)
                                }
                            },
                            backdrop = innerBackdrop,
                            isInteractive = isValid,
                            modifier = Modifier.weight(1f),
                            enabled = isValid,
                            surfaceColor = if (isDarkTheme) Color(0xFF0091FF) else Color(0xFF0088FF)
                        ) {
                            Text(
                                text = stringResource(R.string.proceed),
                                style = TextStyle(
                                    fontFamily = sfPro,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = if (isValid) contentColor else contentColor.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }
                }
            }
        }

        return
    }

    val isConnected = remember { mutableStateOf(false) }

    val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
    val isFirstLaunch = remember { !prefs.getBoolean("permissions_completed", false) }

    val airPodsService = remember { mutableStateOf<AirPodsService?>(null) }

    val airPodsViewModel = remember(airPodsService.value) {
        airPodsService.value?.let { service ->
            AirPodsViewModel(
                service = service,
                sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE),
                controlRepo = ControlCommandRepository(service.aacpManager),
                appContext = context.applicationContext
            )
        }
    }

    val startDestination = if (isFirstLaunch) "permissions" else "settings"
    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
            val backButtonBackdrop = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7))
                    .layerBackdrop(backButtonBackdrop)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it }, animationSpec = tween(durationMillis = 300)
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 }, animationSpec = tween(durationMillis = 300)
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(durationMillis = 300)
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it }, animationSpec = tween(durationMillis = 300)
                        )
                    }) {
                    composable("settings") {
                        val appSettingsViewModel: AppSettingsViewModel = viewModel()
                        if (airPodsViewModel != null) AirPodsSettingsScreen(airPodsViewModel, appSettingsViewModel, navController)
                    }
                    composable("debug") {
                        DebugScreen(navController = navController)
                    }
                    composable("long_press/{bud}") { navBackStackEntry ->
                        if (airPodsViewModel != null) LongPress(
                            viewModel = airPodsViewModel,
                            name = navBackStackEntry.arguments?.getString("bud")!!,
                            navController = navController
                        )
                    }
                    composable("rename") {
                        if (airPodsViewModel != null) RenameScreen(airPodsViewModel)
                    }
                    composable("app_settings") {
                        // AppSettingsScreen content has been moved into the main screen menu.
                        // Route kept for backward-compatibility (deep links, etc.) but renders nothing.
                    }
                    composable("troubleshooting") {
                        TroubleshootingScreen(navController)
                    }
                    composable("head_tracking") {
                        if (airPodsViewModel != null) HeadTrackingScreen(airPodsViewModel, navController)
                    }
                    composable("accessibility") {
                        if (airPodsViewModel != null) AccessibilitySettingsScreen(airPodsViewModel, navController)
                    }
                    composable("transparency_customization") {
                        if (airPodsViewModel != null) TransparencySettingsScreen(airPodsViewModel)
                    }
                    composable("hearing_aid") {
                        if (airPodsViewModel != null) HearingAidScreen(airPodsViewModel, navController)
                    }
                    composable("hearing_aid_adjustments") {
                        if (airPodsViewModel != null) HearingAidAdjustmentsScreen(airPodsViewModel)
                    }
                    composable("adaptive_strength") {
                        if (airPodsViewModel != null) AdaptiveStrengthScreen(airPodsViewModel, navController)
                    }
                    composable("camera_control") {
                        if (airPodsViewModel != null) CameraControlScreen(airPodsViewModel)
                    }
                    composable("open_source_licenses") {
                        OpenSourceLicensesScreen(navController)
                    }
                    composable("update_hearing_test") {
                        if (airPodsViewModel != null) UpdateHearingTestScreen()
                    }
                    composable("version_info") {
                        if (airPodsViewModel != null) VersionScreen(airPodsViewModel)
                    }
                    composable("hearing_protection") {
                        if (airPodsViewModel != null) HearingProtectionScreen(airPodsViewModel, navController)
                    }
                    composable("purchase_screen") {
                        val purchaseViewModel: PurchaseViewModel = viewModel()
                        PurchaseScreen(purchaseViewModel, navController)
                    }
                    composable("permissions") {
                        val onGranted: (() -> Unit)? = if (isFirstLaunch) ({
                            prefs.edit().putBoolean("permissions_completed", true).apply()
                            navController.navigate("settings") {
                                popUpTo("permissions") { inclusive = true }
                            }
                        }) else null
                        AppPermissionsScreen(onPermissionsGranted = onGranted)
                    }
                    composable("notification_announcements") {
                        NotificationAnnouncementsScreen(navController)
                    }
                    composable("announcement_app_picker") {
                        AnnouncementAppPickerScreen(navController)
                    }
                    composable("proximity_finder") {
                        ProximityFinderScreen(navController = navController)
                    }
                    composable("smart_features") {
                        // Smart features are now inlined in the main menu.
                        // Route kept so any existing deep-link or back-stack reference doesn't crash.
                    }
                    composable("category/{key}") { entry ->
                        val key = entry.arguments?.getString("key") ?: "controls"
                        if (airPodsViewModel != null) CategoryScreen(
                            viewModel = airPodsViewModel,
                            appSettingsViewModel = viewModel(),
                            navController = navController,
                            categoryKey = key,
                        )
                    }
                    composable("press_actions") {
                        if (airPodsViewModel != null) PressActionsScreen(airPodsViewModel)
                    }
                    composable("call_controls") {
                        if (airPodsViewModel != null) CallControlsScreen(airPodsViewModel)
                    }
                    composable("controls_configuration") {
                        if (airPodsViewModel != null) VolumeControlScreen(airPodsViewModel)
                    }
                    composable("conversation_awareness") {
                        if (airPodsViewModel != null) ConversationAwarenessScreen(airPodsViewModel, viewModel())
                    }
                    composable("bluetooth_control") {
                        if (airPodsViewModel != null) BluetoothControlScreen(airPodsViewModel)
                    }
                    composable("listening_mode_config") {
                        if (airPodsViewModel != null) ListeningModeConfigScreen(airPodsViewModel)
                    }
                    composable("adaptive_audio") {
                        if (airPodsViewModel != null) AdaptiveStrengthScreen(airPodsViewModel, navController)
                    }
                    composable("smart_automation") {
                        if (airPodsViewModel != null) SmartAutomationScreen(airPodsViewModel)
                    }
                    composable("sleep_timer") {
                        SleepTimerScreen()
                    }
                    composable("phone_battery") {
                        PhoneBatteryScreen(viewModel())
                    }
                    composable("popup_animations") {
                        PopupAnimationsScreen(viewModel())
                    }
                    composable("xposed_settings") {
                        XposedSettingsScreen(viewModel())
                    }
                    composable("audio_settings") {
                        if (airPodsViewModel != null) AudioSettingsScreen(airPodsViewModel, viewModel(), navController)
                    }
                    composable("connection_settings") {
                        if (airPodsViewModel != null) ConnectionSettingsScreen(airPodsViewModel)
                    }
                    composable("microphone_settings") {
                        if (airPodsViewModel != null) MicrophoneSettingsScreen(airPodsViewModel)
                    }
                    composable("email_support") {
                        EmailSupportScreen()
                    }
                    composable("discord_community") {
                        DiscordCommunityScreen()
                    }
                    composable("github_issues") {
                        GitHubIssuesScreen()
                    }
                    composable("gym_press_actions") {
                        if (airPodsViewModel != null) GymPressActionsScreen(airPodsViewModel)
                    }
                    composable("gym_timer") {
                        GymTimerScreen()
                    }
                }
            }

            val showBackButton = remember { mutableStateOf(false) }

            LaunchedEffect(navController) {
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    showBackButton.value =
                        destination.route != "settings" // && destination.route != "onboarding"
                }
            }

            AnimatedVisibility(
                visible = showBackButton.value,
                enter = fadeIn(animationSpec = tween()) + scaleIn(
                    initialScale = 0f,
                    animationSpec = tween()
                ),
                exit = fadeOut(animationSpec = tween()) + scaleOut(
                    targetScale = 0.5f,
                    animationSpec = tween(100)
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = 8.dp, top = (LocalWindowInfo.current.containerSize.width * 0.05f).dp
                    )
            ) {
                StyledIconButton(
                    onClick = { navController.popBackStack() },
                    icon = "􀯶",
                    backdrop = backButtonBackdrop
                )
            }
        }

        context.startForegroundService(Intent(context, AirPodsService::class.java))

        serviceConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as AirPodsService.LocalBinder
                    airPodsService.value = binder.getService()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    airPodsService.value = null
                }
            }
        }

        context.bindService(
            Intent(context, AirPodsService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        if (airPodsService.value?.isConnected() == true) {
            isConnected.value = true
        }
}

