/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.SettingsGroup
import io.automated.ventures.everypods.presentation.components.SettingsExpansionHeader
import io.automated.ventures.everypods.presentation.components.SettingsHeading
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.PeerUiInfo
import io.automated.ventures.everypods.services.ServiceManager
import io.automated.ventures.everypods.presentation.components.RelayInterruptionNotice
import io.automated.ventures.everypods.presentation.components.relaySuspensionMessage
import io.automated.ventures.everypods.relay.RelaySessionApi
import io.automated.ventures.everypods.relay.RelaySessionBinder
import io.automated.ventures.everypods.relay.RelayRole
import io.automated.ventures.everypods.relay.RelayStage
import io.automated.ventures.everypods.relay.RelayUiState
import io.automated.ventures.everypods.relay.RelayAudioTuning
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val MODE_BLUETOOTH = "bluetooth"
private const val MODE_WIFI = "wifi"
private const val MODE_AUTO = "auto"

@Composable
fun DevicePlaybackScreen(
    peerName: String,
    peerMac: String,
    peer: PeerUiInfo?,
    localAirPodsConnected: Boolean,
    airPodsName: String,
    autoStart: Boolean = false,
    autoHandover: Boolean = false,
    autoSetup: Boolean = false,
    connectionType: String = "wifi",
    onSetupComplete: () -> Unit = {},
    onForgetPeer: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val mutePreferenceKey = remember(peerMac) { "audio_relay_mute_local_${peerMac.uppercase(Locale.ROOT)}" }
    val mode = if (connectionType == "bt") MODE_BLUETOOTH else MODE_WIFI
    var muteLocalPlayback by remember(peerMac) { mutableStateOf(preferences.getBoolean(mutePreferenceKey, true)) }
    var confirmRemove by remember(peerMac) { mutableStateOf(false) }
    val networkPreferenceKey = remember(peerMac) { "audio_relay_network_${peerMac.uppercase(Locale.ROOT)}" }
    val allowIncomingKey = remember(peerMac) { "audio_relay_allow_${peerMac.uppercase(Locale.ROOT)}" }
    var network by remember(peerMac) { mutableStateOf(preferences.getString(networkPreferenceKey, MODE_AUTO) ?: MODE_AUTO) }
    var allowIncomingAudio by remember(peerMac) { mutableStateOf(preferences.getBoolean(allowIncomingKey, true)) }
    var tuning by remember(peerMac) { mutableStateOf(RelayAudioTuning.load(preferences, peerMac)) }
    var showTuning by remember(peerMac) { mutableStateOf(false) }
    val tuningPresets = listOf(
        RelayAudioTuning.DEFAULT,
        RelayAudioTuning(initialJitterMs = 80, maximumJitterMs = 160, adaptiveBufferMode = 2, playbackBufferMs = 40, captureBufferMs = 40, frameDurationMs = 10, lowDelayMode = true, bitrateKbps = 160, retransmissionMs = 20),
        RelayAudioTuning(initialJitterMs = 240, maximumJitterMs = 500, adaptiveBufferMode = 1, playbackBufferMs = 120, captureBufferMs = 80, frameDurationMs = 20, lowDelayMode = false, bitrateKbps = 128, retransmissionMs = 80),
    )
    val tuningPresetNames = listOf(
        stringResource(R.string.audio_relay_preset_balanced),
        stringResource(R.string.audio_relay_preset_low_latency),
        stringResource(R.string.audio_relay_preset_unstable),
    )
    var autoStartConsumed by remember(peerMac) { mutableStateOf(false) }
    var relayService by remember { mutableStateOf<RelaySessionApi?>(null) }
    var showBluetoothConfirmation by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf(false) }
    var projectionHandled by remember { mutableStateOf(false) }
    var localNetworkDenied by remember { mutableStateOf(false) }
    val globalServiceState by (relayService?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(RelayUiState()) }).collectAsState()
    val peerVerification = globalServiceState.peerVerifications[peerMac.uppercase(Locale.ROOT)]
    val serviceState = if (globalServiceState.peerMac.equals(peerMac, true)) globalServiceState else RelayUiState(
        peerMac = peerMac,
        stage = peerVerification?.stage ?: RelayStage.STOPPED,
        verificationCode = peerVerification?.code,
        reason = peerVerification?.reason,
    )
    val peerTrusted = relayService?.isPeerTrusted(peerMac) == true
    val dark = isSystemInDarkTheme()
    val bodyColor = if (dark) Color.White else Color.Black
    val secondaryColor = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.58f)
    val isReceiver = localAirPodsConnected
    // A source device must be able to open this screen even when the receiver's
    // AirPods state has not arrived yet. Readiness is reported separately below.
    val isSource = !isReceiver && peer?.connected == true && peer.hasPods
    LaunchedEffect(autoHandover) { if (autoHandover) showBluetoothConfirmation = true }
    var hasInternet by remember { mutableStateOf(hasValidatedInternet(context)) }
    var internetCheckComplete by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        while (true) {
            hasInternet = hasValidatedInternet(context)
            internetCheckComplete = true
            delay(3_000)
        }
    }

    val captureFailedText = stringResource(R.string.audio_relay_capture_failed)
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingCapture = false
        projectionHandled = true
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            runCatching { relayService?.startCapture(result.resultCode, result.data!!, muteLocalPlayback) }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: captureFailedText, android.widget.Toast.LENGTH_LONG).show() }
        } else relayService?.stopRelay()
    }
    val recordingPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingCapture) {
            projectionLauncher.launch(createRelayCaptureConsent(context))
        } else {
            pendingCapture = false
            projectionHandled = true
            relayService?.stopRelay()
        }
    }
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkDenied = !granted
        if (granted) {
            if (localAirPodsConnected) relayService?.prepareReceiver(peerMac)
            else relayService?.startSource(peerMac)
        }
    }
    fun requestCapturePermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            projectionLauncher.launch(createRelayCaptureConsent(context))
        } else recordingPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(context, peerMac, isReceiver) {
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as? RelaySessionBinder)?.relay() ?: return
                relayService = service
                if (isReceiver) {
                    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
                    if (android.os.Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        localNetworkDenied = false
                        service.prepareReceiver(peerMac)
                    } else {
                        localNetworkDenied = true
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { relayService = null }
        }
        ContextCompat.startForegroundService(context, Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService").setAction("io.automated.ventures.everypods.relay.START"))
        bound = context.bindService(Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService").setAction("io.automated.ventures.everypods.relay.START"), connection, Context.BIND_AUTO_CREATE)
        onDispose { if (bound) context.unbindService(connection) }
    }

    LaunchedEffect(serviceState.stage, serviceState.captureApprovalReady, pendingCapture, projectionHandled) {
        if (isSource && serviceState.captureApprovalReady && serviceState.role == RelayRole.SOURCE && !pendingCapture && !projectionHandled) {
            pendingCapture = true
            projectionHandled = true
            requestCapturePermission()
        }
    }

    LaunchedEffect(isSource) {
        if (!isSource) {
            pendingCapture = false
        }
    }

    LaunchedEffect(serviceState.networkMode) {
        serviceState.networkMode?.let { selected ->
            network = selected
            preferences.edit().putString(networkPreferenceKey, selected).apply()
        }
    }

    fun beginCapture() {
        projectionHandled = false
        pendingCapture = false
        if (relayService?.isPeerTrusted(peerMac) == true) relayService?.startSource(peerMac)
        else relayService?.pairPeer(peerMac)
    }

    fun beginRelayOrPair() {
        if (relayService?.isPeerTrusted(peerMac) != true) {
            relayService?.pairPeer(peerMac)
            return
        }
        val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, localNetworkPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            localNetworkLauncher.launch(localNetworkPermission)
        } else beginCapture()
    }

    LaunchedEffect(autoStart, relayService) {
        if (autoStart && relayService != null && !autoStartConsumed && isSource) {
            autoStartConsumed = true
            beginRelayOrPair()
        }
    }

    var setupStarted by remember(peerMac) { mutableStateOf(false) }
    var wasVerifying by remember(peerMac) { mutableStateOf(false) }
    LaunchedEffect(autoSetup, relayService) {
        if (autoSetup && relayService != null && !setupStarted) {
            setupStarted = true
            if (relayService?.isPeerTrusted(peerMac) == true) onSetupComplete()
            else if (serviceState.stage != RelayStage.VERIFYING) relayService?.pairPeer(peerMac)
        }
    }
    LaunchedEffect(serviceState.stage, peerTrusted) {
        if (serviceState.stage == RelayStage.VERIFYING) wasVerifying = true
        if (((autoSetup && setupStarted) || wasVerifying) && serviceState.stage == RelayStage.TRUSTED && relayService?.isPeerTrusted(peerMac) == true) onSetupComplete()
    }

    fun bluetoothHandover() {
        showBluetoothConfirmation = false
        coroutineScope.launch {
            relayService?.stopRelayAndAwait()
            ServiceManager.getService()?.takeOver("music", manualTakeOverAfterReversed = true)
        }
    }

    fun requestBluetoothHandover() {
        if (peer?.hasPods == true || serviceState.stage in setOf(RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED, RelayStage.CONNECTING)) showBluetoothConfirmation = true
        else bluetoothHandover()
    }

    if (showBluetoothConfirmation) {
        AlertDialog(
            onDismissRequest = { showBluetoothConfirmation = false },
            title = { Text(stringResource(R.string.audio_relay_switch_title)) },
            text = { Text(stringResource(R.string.audio_relay_switch_body, peerName)) },
            confirmButton = { TextButton(onClick = ::bluetoothHandover) { Text(stringResource(R.string.audio_relay_switch_confirm)) } },
            dismissButton = { TextButton(onClick = { showBluetoothConfirmation = false }) { Text(stringResource(R.string.audio_relay_switch_cancel)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.connection_remove_title)) },
            text = { Text(stringResource(if (mode == MODE_BLUETOOTH) R.string.connection_remove_bt else R.string.connection_remove_wifi, peerName)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onForgetPeer() }) {
                    Text(stringResource(R.string.ui_remove), color = Color(0xFFFF3B30))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
    StyledScaffold(title = peerName) { top, _, bottom ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(top))
            if (mode != MODE_BLUETOOTH && !isReceiver && serviceState.stage in setOf(RelayStage.STOPPED, RelayStage.TRUSTED, RelayStage.FAILED, RelayStage.WAITING_FOR_SOURCE)) {
                Button(onClick = ::beginRelayOrPair, enabled = isSource && relayService != null) {
                    Text(stringResource(R.string.audio_relay_play_through_peer, peerName))
                }
            } else if (mode == MODE_BLUETOOTH && peer?.hasPods == true) {
                Button(onClick = ::requestBluetoothHandover, enabled = peer.connected) { Text(stringResource(R.string.audio_relay_move_airpods_here)) }
            }

            // Starting from Home uses the saved per-peer network policy
            // immediately. Only expose network setup as recovery when a relay
            // attempt has failed; normal starts should go straight to connect
            // and the Android capture-consent flow.
            if (mode != MODE_BLUETOOTH && serviceState.stage == RelayStage.FAILED) {
                SettingsHeading(stringResource(R.string.audio_relay_network_heading))
                SettingsGroup {
                    PlaybackModeRow(stringResource(R.string.audio_relay_network_auto), stringResource(R.string.audio_relay_network_auto_desc), network == MODE_AUTO, dark) { if (network != MODE_AUTO) relayService?.stopRelay(); network = MODE_AUTO; preferences.edit().putString(networkPreferenceKey, MODE_AUTO).apply() }
                    PlaybackModeRow(stringResource(R.string.audio_relay_shared_wifi), stringResource(R.string.audio_relay_shared_wifi_desc), network == MODE_WIFI, dark) { if (network != MODE_WIFI) relayService?.stopRelay(); network = MODE_WIFI; preferences.edit().putString(networkPreferenceKey, MODE_WIFI).apply() }
                    PlaybackModeRow(stringResource(R.string.audio_relay_device_hotspot), stringResource(R.string.audio_relay_hotspot_desc), network == "hotspot", dark) { if (network != "hotspot") relayService?.stopRelay(); network = "hotspot"; preferences.edit().putString(networkPreferenceKey, "hotspot").apply() }
                }
                TextButton(onClick = {
                    val action = if (network == "hotspot" && isReceiver) "android.settings.TETHER_SETTINGS" else android.provider.Settings.ACTION_WIFI_SETTINGS
                    runCatching { context.startActivity(Intent(action)) }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }) { Text(stringResource(if (network == "hotspot" && isReceiver) R.string.audio_relay_open_hotspot_settings else R.string.audio_relay_open_wifi_settings)) }
                if (network == "hotspot") Text(stringResource(R.string.audio_relay_hotspot_data_note), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily(Font(R.font.sf_pro)), color = secondaryColor))
                Text(stringResource(R.string.audio_relay_video_delay), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily(Font(R.font.sf_pro)), color = secondaryColor))
            }

            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isReceiver) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.audio_relay_allow_audio_from_device), modifier = Modifier.weight(1f).padding(end = 12.dp), color = bodyColor)
                        Switch(checked = allowIncomingAudio, onCheckedChange = { allowIncomingAudio = it; preferences.edit().putBoolean(allowIncomingKey, it).apply() })
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.audio_relay_mute_local), modifier = Modifier.weight(1f).padding(end = 12.dp), color = bodyColor)
                        Switch(checked = muteLocalPlayback, onCheckedChange = {
                            muteLocalPlayback = it
                            preferences.edit().putBoolean(mutePreferenceKey, it).apply()
                        })
                    }
                }
            }

            if (mode != MODE_BLUETOOTH) {
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsExpansionHeader(
                            label = stringResource(R.string.audio_relay_experimental_tuning),
                            expanded = showTuning,
                            onToggle = { showTuning = !showTuning },
                        )
                        if (showTuning) {
                            Text(stringResource(R.string.audio_relay_tuning_next_session), color = secondaryColor, style = TextStyle(fontSize = 13.sp))
                            val presetIndex = tuningPresets.indexOf(tuning)
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_preset), tuningPresetNames.getOrElse(presetIndex) { stringResource(R.string.audio_relay_preset_custom) }, tuningPresetNames.mapIndexed { index, name -> name to index }, bodyColor) { value ->
                                tuning = tuningPresets[value]
                                RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            Text(stringResource(when (presetIndex) {
                                0 -> R.string.audio_relay_preset_balanced_desc
                                1 -> R.string.audio_relay_preset_low_latency_desc
                                2 -> R.string.audio_relay_preset_unstable_desc
                                else -> R.string.audio_relay_preset_custom_desc
                            }), color = secondaryColor, style = TextStyle(fontSize = 13.sp))
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_initial), "${tuning.initialJitterMs} ms", RelayAudioTuning.JITTER_OPTIONS.filter { it <= tuning.maximumJitterMs }.map { "$it ms" to it }, bodyColor) { value ->
                                tuning = tuning.copy(initialJitterMs = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_maximum), "${tuning.maximumJitterMs} ms", RelayAudioTuning.JITTER_OPTIONS.filter { it >= tuning.initialJitterMs }.map { "$it ms" to it }, bodyColor) { value ->
                                tuning = tuning.copy(maximumJitterMs = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            val adaptiveLabels = listOf(stringResource(R.string.audio_relay_tuning_off), stringResource(R.string.audio_relay_tuning_conservative), stringResource(R.string.audio_relay_tuning_faster))
                            val automaticLabel = stringResource(R.string.audio_relay_tuning_automatic)
                            val musicLabel = stringResource(R.string.audio_relay_tuning_music)
                            val lowDelayLabel = stringResource(R.string.audio_relay_tuning_low_delay)
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_adaptive), adaptiveLabels[tuning.adaptiveBufferMode], adaptiveLabels.mapIndexed { index, text -> text to index }, bodyColor) { value ->
                                tuning = tuning.copy(adaptiveBufferMode = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_playback_buffer), if (tuning.playbackBufferMs == 0) automaticLabel else "${tuning.playbackBufferMs} ms", RelayAudioTuning.PLAYBACK_BUFFER_OPTIONS.map { (if (it == 0) automaticLabel else "$it ms") to it }, bodyColor) { value ->
                                tuning = tuning.copy(playbackBufferMs = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_capture_buffer), if (tuning.captureBufferMs == 0) automaticLabel else "${tuning.captureBufferMs} ms", RelayAudioTuning.CAPTURE_BUFFER_OPTIONS.map { (if (it == 0) automaticLabel else "$it ms") to it }, bodyColor) { value ->
                                tuning = tuning.copy(captureBufferMs = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_frame), "${tuning.frameDurationMs} ms", listOf("10 ms" to 10, "20 ms" to 20), bodyColor) { value ->
                                tuning = tuning.copy(frameDurationMs = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_opus_mode), if (tuning.lowDelayMode) lowDelayLabel else musicLabel, listOf(musicLabel to 0, lowDelayLabel to 1), bodyColor) { value ->
                                tuning = tuning.copy(lowDelayMode = value == 1); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_bitrate), "${tuning.bitrateKbps} kbps", RelayAudioTuning.BITRATE_OPTIONS.map { "$it kbps" to it }, bodyColor) { value ->
                                tuning = tuning.copy(bitrateKbps = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TuningChoiceRow(stringResource(R.string.audio_relay_tuning_retransmission), "${tuning.retransmissionMs} ms", RelayAudioTuning.RETRANSMISSION_OPTIONS.map { "$it ms" to it }, bodyColor) { value ->
                                tuning = tuning.copy(retransmissionMs = value); RelayAudioTuning.save(preferences, peerMac, tuning)
                            }
                            TextButton(onClick = { tuning = RelayAudioTuning.DEFAULT; RelayAudioTuning.save(preferences, peerMac, tuning) }) {
                                Text(stringResource(R.string.audio_relay_tuning_restore_defaults))
                            }
                        }
                    }
                }
            }

            if (serviceState.stage == RelayStage.VERIFYING && serviceState.verificationCode != null) {
                SettingsHeading(stringResource(R.string.audio_relay_verify_heading))
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.audio_relay_verify_instruction), color = bodyColor)
                        Text(serviceState.verificationCode!!, style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = bodyColor))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { relayService?.confirmPairing(peerMac) }) { Text(stringResource(R.string.audio_relay_verify_confirm)) }
                            OutlinedButton(onClick = { relayService?.cancelPairing(peerMac) }) { Text(stringResource(R.string.audio_relay_verify_cancel)) }
                        }
                    }
                }
            }

            SettingsHeading(stringResource(R.string.audio_relay_status_heading))
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (serviceState.stage == RelayStage.SUSPENDED) {
                        RelayInterruptionNotice(serviceState, peer?.name ?: peerName, bodyColor) { relayService?.resumeRelay() }
                    } else Text(sessionStatus(serviceState, isReceiver, isSource, peer), color = bodyColor)
                    serviceState.reason?.let { Text(it, color = secondaryColor) }
                    if (localNetworkDenied) {
                        Text(stringResource(R.string.audio_relay_local_network_denied), color = secondaryColor)
                        TextButton(onClick = { localNetworkLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK") }) {
                            Text(stringResource(R.string.audio_relay_allow_local_network))
                        }
                    }
                }
            }
            if (mode != MODE_BLUETOOTH) {
                if (isReceiver && serviceState.stage == RelayStage.FAILED) OutlinedButton(onClick = { relayService?.prepareReceiver(peerMac) }) { Text(stringResource(R.string.audio_relay_retry)) }
                if (serviceState.role != RelayRole.NONE && serviceState.stage in setOf(RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED, RelayStage.CONNECTING)) {
                    Button(onClick = { relayService?.stopRelay() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.audio_relay_stop))
                    }
                }
            }

            SettingsHeading(stringResource(R.string.audio_relay_session_heading))
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${stringResource(R.string.audio_relay_audio)}  ·  ${sessionAudioStatus(serviceState)}", color = bodyColor)
                    Text("${stringResource(R.string.audio_relay_network_heading)}  ·  ${networkStatus(serviceState)}", color = secondaryColor)
                    val internetState = when {
                        isReceiver -> R.string.audio_relay_unknown
                        !internetCheckComplete -> R.string.audio_relay_checking
                        hasInternet -> R.string.audio_relay_available
                        else -> R.string.audio_relay_unavailable
                    }
                    Text("${stringResource(R.string.audio_relay_internet)}  ·  ${stringResource(internetState)}", color = secondaryColor)
                    serviceState.relayDelayEstimateMillis?.let { estimate ->
                        Text(stringResource(R.string.audio_relay_delay_estimate, estimate), color = bodyColor)
                        val jitter = serviceState.relayJitterTargetMillis
                        val queue = serviceState.relayAudioTrackQueueMillis
                        if (jitter != null && queue != null) {
                            Text(stringResource(R.string.audio_relay_delay_components, jitter, queue), color = secondaryColor)
                        }
                        serviceState.relayClockSyncUncertaintyMillis?.let { uncertainty ->
                            Text(stringResource(R.string.audio_relay_clock_uncertainty, uncertainty), color = secondaryColor)
                        }
                        Text(stringResource(R.string.audio_relay_delay_limit), color = secondaryColor, style = TextStyle(fontSize = 12.sp))
                    }
                    if (serviceState.stage in setOf(RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)) {
                        val route = if (isReceiver) {
                            stringResource(
                                R.string.audio_relay_route,
                                peer?.name ?: peerName,
                                stringResource(R.string.audio_relay_route_this_device),
                                airPodsName.ifBlank { stringResource(R.string.audio_relay_airpods) },
                            )
                        } else {
                            stringResource(
                                R.string.audio_relay_route,
                                stringResource(R.string.audio_relay_route_this_device),
                                peerName,
                                stringResource(R.string.audio_relay_airpods),
                            )
                        }
                        Text(route, color = secondaryColor)
                    }
                }
            }
            if (peerTrusted) {
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.connection_remove_action), color = Color(0xFFFF3B30))
                }
            }

            Spacer(Modifier.height(bottom))
        }
    }
}

@Composable
private fun TuningChoiceRow(label: String, selected: String, choices: List<Pair<String, Int>>, textColor: Color, onSelect: (Int) -> Unit) {
    var open by remember(label) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = textColor)
        Text(selected, color = if (textColor == Color.White) Color(0xFF90CAF9) else Color(0xFF1565C0))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label, color = textColor) },
        text = { Column { choices.forEach { (name, value) ->
            TextButton(onClick = { open = false; onSelect(value) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (name == selected) "✓  $name" else name, color = textColor)
            }
        } } },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

@Composable
private fun PlaybackModeRow(title: String, description: String, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    val secondaryColor = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.58f)
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily(Font(R.font.sf_pro)), color = textColor))
            Text(description, style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily(Font(R.font.sf_pro)), color = secondaryColor))
        }
    }
}

@Composable
private fun sessionStatus(state: RelayUiState, receiver: Boolean, source: Boolean, peer: PeerUiInfo?): String = when {
    state.waitingForReceiverApproval -> stringResource(R.string.audio_relay_waiting_approval, peer?.name.orEmpty())
    state.stage == RelayStage.FAILED -> stringResource(R.string.audio_relay_action_needed)
    state.stage == RelayStage.SUSPENDED -> relaySuspensionMessage(state, peer?.name.orEmpty())
    state.stage == RelayStage.BUFFERING -> stringResource(R.string.audio_relay_buffering)
    state.stage == RelayStage.STREAMING -> stringResource(if (receiver) R.string.audio_relay_playing_from_peer else R.string.audio_relay_playing_through_peer, peer?.name.orEmpty())
    state.stage == RelayStage.WAITING_FOR_AUDIO -> stringResource(R.string.audio_relay_waiting_audio)
    state.stage == RelayStage.CONNECTING -> stringResource(R.string.audio_relay_connecting)
    state.stage == RelayStage.VERIFYING -> stringResource(R.string.audio_relay_verify_heading)
    receiver -> stringResource(R.string.audio_relay_ready_receiver)
    source && peer?.connected == true -> stringResource(R.string.audio_relay_ready_to_play, peer.name)
    peer?.connected == true -> stringResource(R.string.audio_relay_peer_connected)
    else -> stringResource(R.string.audio_relay_peer_unavailable)
}

@Composable
private fun sessionAudioStatus(state: RelayUiState): String = when (state.stage) {
    RelayStage.SUSPENDED -> stringResource(R.string.audio_relay_suspended)
    RelayStage.STREAMING -> stringResource(R.string.audio_relay_streaming)
    RelayStage.BUFFERING -> stringResource(R.string.audio_relay_buffering)
    RelayStage.CONNECTING -> stringResource(R.string.audio_relay_connecting)
    RelayStage.WAITING_FOR_AUDIO -> stringResource(R.string.audio_relay_waiting_audio)
    RelayStage.FAILED -> stringResource(R.string.audio_relay_action_needed)
    else -> stringResource(R.string.audio_relay_stopped)
}

@Composable
private fun networkStatus(state: RelayUiState): String = when (state.stage) {
    RelayStage.CONNECTING -> stringResource(R.string.audio_relay_checking)
    RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED -> stringResource(R.string.audio_relay_connected)
    RelayStage.FAILED -> stringResource(R.string.audio_relay_unavailable)
    else -> stringResource(R.string.audio_relay_unknown)
}

/** Capture eligible media across apps without Android's per-app screen picker. */
internal fun createRelayCaptureConsent(context: Context): Intent {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        manager.createScreenCaptureIntent()
    }
}

private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
