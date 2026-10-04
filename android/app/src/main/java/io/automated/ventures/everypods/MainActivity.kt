/*
    EveryPods - AirPods liberated from Apple’s ecosystem
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

@file:OptIn(ExperimentalEncodingApi::class)

package io.automated.ventures.everypods

// import io.automated.ventures.everypods.screens.Onboarding
// import io.automated.ventures.everypods.utils.RadareOffsetFinder
//import dagger.hilt.android.AndroidEntryPoint
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.Manifest
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.rememberHazeState
import io.automated.ventures.everypods.data.AirPodsNotifications
import io.automated.ventures.everypods.data.ControlCommandRepository
import io.automated.ventures.everypods.presentation.components.AppInfoCard
import io.automated.ventures.everypods.presentation.components.ConfirmationDialog
import io.automated.ventures.everypods.presentation.components.DeviceInfoCard
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledBottomSheet
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledIconButton
import io.automated.ventures.everypods.presentation.components.StyledInputField
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.screens.AccessibilitySettingsScreen
import io.automated.ventures.everypods.presentation.screens.AdaptiveStrengthScreen
import io.automated.ventures.everypods.presentation.screens.AirPodsSettingsScreen
import io.automated.ventures.everypods.presentation.screens.EqualizerScreen
import io.automated.ventures.everypods.presentation.screens.AppSettingsScreen
import io.automated.ventures.everypods.presentation.screens.DebugScreen
import io.automated.ventures.everypods.presentation.screens.HeadTrackingScreen
import io.automated.ventures.everypods.presentation.screens.HearingProtectionScreen
import io.automated.ventures.everypods.presentation.screens.LongPress
import io.automated.ventures.everypods.presentation.screens.OpenSourceLicensesScreen
import io.automated.ventures.everypods.presentation.screens.PurchaseScreen
import io.automated.ventures.everypods.presentation.screens.RenameScreen

import io.automated.ventures.everypods.presentation.screens.AnnouncementAppPickerScreen
import io.automated.ventures.everypods.presentation.screens.AppPermissionsScreen
import io.automated.ventures.everypods.presentation.screens.NotificationAnnouncementsScreen
import io.automated.ventures.everypods.presentation.screens.ProximityFinderScreen
import io.automated.ventures.everypods.presentation.screens.VersionScreen
import io.automated.ventures.everypods.presentation.screens.CategoryScreen
import io.automated.ventures.everypods.presentation.screens.PressActionsScreen
import io.automated.ventures.everypods.presentation.screens.VolumeControlScreen
import io.automated.ventures.everypods.presentation.screens.CallControlsScreen
import io.automated.ventures.everypods.presentation.screens.ConversationAwarenessScreen
import io.automated.ventures.everypods.presentation.screens.BluetoothControlScreen
import io.automated.ventures.everypods.presentation.screens.AudioSettingsScreen
import io.automated.ventures.everypods.presentation.screens.ConnectionSettingsScreen
import io.automated.ventures.everypods.presentation.screens.PairedDevicesScreen
import io.automated.ventures.everypods.presentation.screens.MicrophoneSettingsScreen
import io.automated.ventures.everypods.presentation.screens.ListeningModeConfigScreen
import io.automated.ventures.everypods.presentation.screens.SmartAutomationScreen
import io.automated.ventures.everypods.presentation.screens.SleepTimerScreen
import io.automated.ventures.everypods.presentation.screens.PhoneBatteryScreen
import io.automated.ventures.everypods.presentation.screens.PopupAnimationsScreen
import io.automated.ventures.everypods.presentation.screens.EmailSupportScreen
import io.automated.ventures.everypods.presentation.screens.GitHubIssuesScreen
import io.automated.ventures.everypods.presentation.screens.GymPressActionsScreen
import io.automated.ventures.everypods.presentation.screens.GymTimerScreen
import io.automated.ventures.everypods.presentation.theme.EveryPodsTheme
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.presentation.viewmodel.AppSettingsViewModel
import io.automated.ventures.everypods.presentation.viewmodel.PurchaseViewModel
import io.automated.ventures.everypods.services.AirPodsService
import io.automated.ventures.everypods.services.CallNotifListener
import io.automated.ventures.everypods.startup.StartupGate
import io.automated.ventures.everypods.utils.isAacpCapable
import kotlin.io.encoding.ExperimentalEncodingApi

// Bound from Compose via DisposableEffect; Activity lifecycle only tears down safely.
var serviceConnection: ServiceConnection? = null

//@AndroidEntryPoint
@ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    private var gymTimerNavigationRequest by mutableIntStateOf(0)

    @ExperimentalHazeMaterialsApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            StartupGate.TAG,
            "activity onCreate restored=${savedInstanceState != null} pid=${android.os.Process.myPid()} " +
                "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) play=${BuildConfig.PLAY_BUILD} " +
                "debug=${BuildConfig.DEBUG}"
        )
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_OPEN_GYM_TIMER, false) == true) {
            gymTimerNavigationRequest++
        }

        setContent {
            EveryPodsTheme {
                Main(gymTimerNavigationRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_GYM_TIMER, false)) {
            gymTimerNavigationRequest++
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(StartupGate.TAG, "activity onResume")
    }

    override fun onPause() {
        Log.i(StartupGate.TAG, "activity onPause")
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(StartupGate.TAG, "activity onDestroy finishing=$isFinishing")
        // Service bind/unbind is owned by Main()'s DisposableEffect so onStop no longer
        // drops the connection (that left the UI stuck on "Starting…" after any stop).
        sendBroadcast(Intent(AirPodsNotifications.DISCONNECT_RECEIVERS))
        super.onDestroy()
    }
}

const val EXTRA_OPEN_GYM_TIMER = "io.automated.ventures.everypods.OPEN_GYM_TIMER"

@ExperimentalHazeMaterialsApi
@SuppressLint("MissingPermission", "InlinedApi", "UnspecifiedRegisterReceiverFlag")
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Main(gymTimerNavigationRequest: Int = 0) {
    val context = LocalContext.current

    val isConnected = remember { mutableStateOf(false) }

    val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)

    fun hasCriticalBtPerms(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    // The critical runtime permission for the foreground service to bind: without
    // BLUETOOTH_CONNECT the platform silently refuses start/bind of AirPodsService
    // (android:permission=BLUETOOTH_CONNECT → app-op denial, no exception), the
    // service never binds, airPodsViewModel stays null and home shows "Starting…".
    // The persisted `permissions_completed` flag is NOT sufficient on its own (Auto
    // Backup restore / auto-revoke), so gate onboarding on the ACTUAL grant state.
    val initialBtPerms = remember { hasCriticalBtPerms() }
    val initialFlag = remember { prefs.getBoolean("permissions_completed", false) }
    // Start destination is fixed for this composition, but onboarding *state* must be
    // able to flip when the user taps Continue — previously it was remember{}'d, which
    // kept the battery prompt (and anything else gated on it) off for the whole first
    // session; it only appeared after the activity was recreated.
    val startNeedsPermissions = remember { StartupGate.needsOnboarding(initialFlag, initialBtPerms) }
    var onboardingPending by remember { mutableStateOf(startNeedsPermissions) }
    val needsPermissions = startNeedsPermissions
    LaunchedEffect(Unit) {
        Log.i(
            StartupGate.TAG,
            "compose Main: permissions_completed=$initialFlag btPerms=$initialBtPerms " +
                "onboarding=${if (startNeedsPermissions) "required" else "done"} " +
                "start=${if (startNeedsPermissions) "permissions" else "settings"}"
        )
    }

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

    // Service bind state — cold install can start/bind before BT grants (silently
    // refused, see above). Retry on resume, after perms, and on a timer that only
    // spends attempts once perms exist; never leave home on infinite "Starting…".
    var lastBindError by remember { mutableStateOf<String?>(null) }
    var btPermsNow by remember { mutableStateOf(initialBtPerms) }
    val bindRegistered = remember { AtomicBoolean(false) }
    val serviceConnectionImpl = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as AirPodsService.LocalBinder
                airPodsService.value = binder.getService()
                lastBindError = null
                if (airPodsService.value?.isConnected() == true) {
                    isConnected.value = true
                }
                Log.i(StartupGate.TAG, "bind connected name=$name airpodsConnected=${airPodsService.value?.isConnected()}")
                Log.i(
                    "MainActivity",
                    "<LogCollector:LidLease> service_bound name=$name connected=${airPodsService.value?.isConnected()}"
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(StartupGate.TAG, "bind disconnected name=$name")
                Log.w("MainActivity", "<LogCollector:LidLease> service_disconnected name=$name")
                airPodsService.value = null
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(StartupGate.TAG, "bind died name=$name")
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.w(StartupGate.TAG, "bind null binding name=$name")
            }
        }
    }

    fun ensureAirPodsBound(reason: String) {
        val perms = hasCriticalBtPerms()
        btPermsNow = perms
        when (StartupGate.decideBind(airPodsService.value != null, perms)) {
            StartupGate.BindDecision.SKIP_ALREADY_BOUND -> {
                Log.d(StartupGate.TAG, "bind skip already bound reason=$reason")
                return
            }
            StartupGate.BindDecision.SKIP_MISSING_BT_PERMS -> {
                Log.i(StartupGate.TAG, "bind skip missing BT perms reason=$reason")
                return
            }
            StartupGate.BindDecision.START_AND_BIND -> Unit
        }
        Log.i(StartupGate.TAG, "bind requested reason=$reason")
        Log.i("MainActivity", "<LogCollector:LidLease> ensure_bind reason=$reason")
        var startErr: String?
        try {
            val cn = context.startForegroundService(Intent(context, AirPodsService::class.java))
            startErr = StartupGate.startServiceError(cn != null, null)
            if (startErr == null) {
                Log.i(StartupGate.TAG, "startForegroundService ok cn=$cn reason=$reason")
            } else {
                Log.w(StartupGate.TAG, "startForegroundService refused reason=$reason: $startErr")
            }
        } catch (e: Exception) {
            startErr = StartupGate.startServiceError(false, e)
            Log.e(StartupGate.TAG, "startForegroundService threw reason=$reason: $e")
        }
        if (bindRegistered.get()) {
            try {
                context.unbindService(serviceConnectionImpl)
            } catch (e: Exception) {
                Log.w(StartupGate.TAG, "unbind before rebind: ${e.message}")
            }
            bindRegistered.set(false)
        }
        var bindErr: String?
        try {
            val bound = context.bindService(
                Intent(context, AirPodsService::class.java),
                serviceConnectionImpl,
                Context.BIND_AUTO_CREATE
            )
            bindRegistered.set(bound)
            serviceConnection = serviceConnectionImpl
            bindErr = StartupGate.bindServiceError(bound, null)
            Log.i(StartupGate.TAG, "bindService bound=$bound reason=$reason")
        } catch (e: Exception) {
            bindErr = StartupGate.bindServiceError(false, e)
            Log.e(StartupGate.TAG, "bindService threw reason=$reason: $e")
            bindRegistered.set(false)
        }
        // Only surface an error when the bind itself failed; a refused start with a live
        // bind still gets onServiceConnected (and onStartCommand re-asserts FGS on retry).
        if (bindErr != null) {
            lastBindError = bindErr
        } else if (startErr != null) {
            Log.w(StartupGate.TAG, "bind ok but start refused reason=$reason; will retry start on next gate")
        }
    }

    // AACP capability may become available asynchronously after the ViewModel was
    // created (e.g. the OEM companion app finishes its own setup). Re-evaluate every
    // time the activity resumes so AACP controls un-grey once it's actually available.
    // Also rebind if the cold-start FGS/bind never connected (force-stop was the only
    // recovery before this retry path).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, airPodsViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                airPodsViewModel?.refreshAacpAvailable()
                val perms = hasCriticalBtPerms()
                btPermsNow = perms
                Log.i(
                    StartupGate.TAG,
                    "compose ON_RESUME bound=${airPodsService.value != null} btPerms=$perms " +
                        "onboarding=${if (onboardingPending) "pending" else "done"}"
                )
                if (airPodsService.value == null) {
                    ensureAirPodsBound("on_resume")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While home is stuck on Starting…, periodically retry start+bind. Attempts are
    // only consumed once BT perms are granted, so granting late (after a long stay on
    // the permission screen) still gets retries, and a bind that never connects ends
    // in the error + Retry UI instead of an endless "Starting…".
    LaunchedEffect(Unit) {
        var attemptsUsed = 0
        var lastLoggedPerms: Boolean? = null
        while (true) {
            delay(StartupGate.BIND_RETRY_INTERVAL_MS)
            if (airPodsService.value != null) {
                Log.i(StartupGate.TAG, "retry loop done: bound after attempts=$attemptsUsed")
                return@LaunchedEffect
            }
            val perms = hasCriticalBtPerms()
            btPermsNow = perms
            if (perms != lastLoggedPerms) {
                Log.i(StartupGate.TAG, "permission state BLUETOOTH_CONNECT+SCAN granted=$perms")
                lastLoggedPerms = perms
            }
            if (StartupGate.shouldAttemptRetry(false, perms, attemptsUsed)) {
                attemptsUsed++
                Log.i(StartupGate.TAG, "bind retry attempt=$attemptsUsed/${StartupGate.MAX_BIND_ATTEMPTS}")
                ensureAirPodsBound("timeout_retry_$attemptsUsed")
            } else if (StartupGate.retriesExhausted(false, perms, attemptsUsed)) {
                if (lastBindError == null) lastBindError = "Service did not connect"
                Log.w(StartupGate.TAG, "bind timeout: not connected after $attemptsUsed attempts; showing Retry")
                return@LaunchedEffect
            }
        }
    }

    val startDestination = if (needsPermissions) "permissions" else "settings"
    val navController = rememberNavController()

    LaunchedEffect(gymTimerNavigationRequest, needsPermissions) {
        if (gymTimerNavigationRequest > 0 && !needsPermissions) {
            navController.navigate("gym_timer") { launchSingleTop = true }
        }
    }

    // W5 battery exemption: activity-owned, after bind clears "Starting…" and after
    // onboarding completes (in the SAME session — keyed on onboardingPending state).
    // Never launch from AirPodsService.onCreate — that NEW_TASK Settings dialog
    // raced first bind and left home stuck until force-stop. Do not gate
    // connection / Waiting UI on the exemption result; Settings tip in App
    // Settings remains available if the user dismisses this prompt.
    LaunchedEffect(airPodsViewModel, onboardingPending) {
        if (airPodsViewModel == null || onboardingPending) {
            Log.i(
                StartupGate.TAG,
                "battery prompt skipped: " + StartupGate.batteryPrompt(airPodsViewModel != null, onboardingPending, false, false)
            )
            return@LaunchedEffect
        }
        // Let home (Waiting / disconnected) paint before any Settings intent.
        delay(1_500)
        try {
            val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return@LaunchedEffect
            val promptedKey = "battery_exemption_auto_prompted"
            val decision = StartupGate.batteryPrompt(
                serviceReady = true,
                onboardingPending = false,
                alreadyExempt = pm.isIgnoringBatteryOptimizations(context.packageName),
                alreadyPrompted = prefs.getBoolean(promptedKey, false),
            )
            if (decision != StartupGate.BatteryPrompt.SHOW) {
                Log.i(StartupGate.TAG, "battery prompt skipped: $decision")
                return@LaunchedEffect
            }
            prefs.edit().putBoolean(promptedKey, true).apply()
            Log.i(StartupGate.TAG, "battery prompt shown (post-bind, activity-owned)")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(StartupGate.TAG, "battery prompt failed: $e")
        }
    }

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
                        if (airPodsViewModel != null) {
                            AirPodsSettingsScreen(airPodsViewModel, appSettingsViewModel, navController)
                        } else {
                            // The service hasn't bound yet (or can't — e.g. permissions
                            // missing). Render a placeholder instead of nothing so the
                            // user never sees a pure-black screen while we wait/recover.
                            // After retries fail, surface the error + Retry (never infinite
                            // Starting… with no way out short of force-stop).
                            val placeholder = StartupGate.placeholder(btPermsNow, lastBindError)
                            LaunchedEffect(placeholder) {
                                Log.i(StartupGate.TAG, "home placeholder=$placeholder error=$lastBindError")
                            }
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = when (placeholder) {
                                            StartupGate.Placeholder.NEEDS_BT_PERMISSION -> "Bluetooth permission needed"
                                            StartupGate.Placeholder.ERROR_WITH_RETRY -> "Couldn't start service"
                                            StartupGate.Placeholder.STARTING -> "Starting…"
                                        },
                                        color = if (isSystemInDarkTheme()) Color.White else Color.Black
                                    )
                                    if (placeholder == StartupGate.Placeholder.NEEDS_BT_PERMISSION) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = {
                                            Log.i(StartupGate.TAG, "user opened permissions from home placeholder")
                                            navController.navigate("permissions") { launchSingleTop = true }
                                        }) {
                                            Text("Grant permissions")
                                        }
                                    } else if (placeholder == StartupGate.Placeholder.ERROR_WITH_RETRY) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = lastBindError ?: "",
                                            color = if (isSystemInDarkTheme()) Color.LightGray else Color.DarkGray
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(onClick = {
                                            lastBindError = null
                                            Log.i(StartupGate.TAG, "bind retry requested by user")
                                            ensureAirPodsBound("user_retry")
                                        }) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    composable("debug") {
                        DebugScreen(navController = navController)
                    }
                    // Alias: Help → Troubleshooting used to navigate here by name.
                    composable("troubleshooting") {
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
                    composable("head_tracking") {
                        if (airPodsViewModel != null) HeadTrackingScreen(airPodsViewModel, navController)
                    }
                    composable("accessibility") {
                        if (airPodsViewModel != null) AccessibilitySettingsScreen(airPodsViewModel, navController)
                    }
                     composable("adaptive_strength") {
                         if (airPodsViewModel != null) AdaptiveStrengthScreen(airPodsViewModel, navController)
                     }
                     composable("equalizer_screen") {
                         if (airPodsViewModel != null) EqualizerScreen(airPodsViewModel)
                     }
                    composable("open_source_licenses") {
                        OpenSourceLicensesScreen(navController)
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
                        // Navigate onward whenever this screen was the start destination
                        // because permissions were needed — covers both true first launch
                        // and the "restored flag but missing grant" re-onboarding case.
                        val onGranted: (() -> Unit)? = if (needsPermissions) ({
                            val perms = hasCriticalBtPerms()
                            btPermsNow = perms
                            Log.i(StartupGate.TAG, "onboarding continue tapped btPerms=$perms -> onboarding done")
                            prefs.edit().putBoolean("permissions_completed", true).apply()
                            onboardingPending = false
                            // BT grants just landed — start+bind now (composition bind
                            // may have run earlier and been denied / skipped).
                            ensureAirPodsBound("perms_granted")
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
                    composable(
                        route = "category/{key}?anchor={anchor}",
                        arguments = listOf(
                            navArgument("key") { type = NavType.StringType },
                            navArgument("anchor") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        val key = entry.arguments?.getString("key") ?: "controls"
                        val anchor = entry.arguments?.getString("anchor")
                        if (airPodsViewModel != null) CategoryScreen(
                            viewModel = airPodsViewModel,
                            appSettingsViewModel = viewModel(),
                            navController = navController,
                            categoryKey = key,
                            anchor = anchor,
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
                    composable("audio_settings") {
                        if (airPodsViewModel != null) AudioSettingsScreen(airPodsViewModel, viewModel(), navController)
                    }
                    composable("connection_settings") {
                        if (airPodsViewModel != null) ConnectionSettingsScreen(airPodsViewModel, navController)
                    }
                    composable("paired_devices") {
                        if (airPodsViewModel != null) PairedDevicesScreen(airPodsViewModel, navController)
                    }
                    composable("microphone_settings") {
                        if (airPodsViewModel != null) MicrophoneSettingsScreen(airPodsViewModel)
                    }
                    composable("email_support") {
                        EmailSupportScreen()
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

        // Own the ServiceConnection for this composition. Actual start+bind is in
        // ensureAirPodsBound (composition / on_resume / perms_granted / timeout / Retry)
        // so a cold-install FGS denial does not stick until force-stop.
        DisposableEffect(Unit) {
            serviceConnection = serviceConnectionImpl
            ensureAirPodsBound("composition")
            onDispose {
                try {
                    if (bindRegistered.get()) {
                        context.unbindService(serviceConnectionImpl)
                        Log.i(StartupGate.TAG, "unbound service (composition disposed)")
                    }
                } catch (e: Exception) {
                    Log.e(StartupGate.TAG, "Error while unbinding service: $e")
                }
                bindRegistered.set(false)
                if (serviceConnection === serviceConnectionImpl) {
                    serviceConnection = null
                }
                airPodsService.value = null
            }
        }
}
