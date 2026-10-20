/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import io.automated.ventures.everypods.presentation.components.RelayInterruptionNotice
import io.automated.ventures.everypods.presentation.components.relaySuspensionMessage
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.SettingsGroup
import io.automated.ventures.everypods.presentation.viewmodel.PeerUiInfo
import io.automated.ventures.everypods.relay.RelayRole
import io.automated.ventures.everypods.relay.RelaySessionApi
import io.automated.ventures.everypods.relay.RelaySessionBinder
import io.automated.ventures.everypods.relay.RelayStage
import io.automated.ventures.everypods.relay.RelayUiState
import io.automated.ventures.everypods.relay.DeviceConnectionType
import io.automated.ventures.everypods.relay.DeviceConnectionStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRelayHomeSurface(
    peers: List<PeerUiInfo>,
    localAirPodsConnected: Boolean,
    navController: NavController,
    modifier: Modifier = Modifier,
    onContentVisibilityChanged: (Boolean) -> Unit = {},
) {
    if (!BuildConfig.AUDIO_RELAY_PREVIEW) return
    val context = LocalContext.current
    val connections = rememberDeviceConnections(peers)
    val wifiPeers = peers.filter { peer -> connections.any { it.mac.equals(peer.mac, true) && it.type == DeviceConnectionType.WIFI_RELAY } }
    var relay by remember { mutableStateOf<RelaySessionApi?>(null) }
    DisposableEffect(context) {
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                relay = (binder as? RelaySessionBinder)?.relay()
            }
            override fun onServiceDisconnected(name: ComponentName?) { relay = null }
        }
        val intent = Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService")
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose { if (bound) context.unbindService(connection) }
    }
    val state by (relay?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(RelayUiState()) }).collectAsState()
    var pendingCaptureMac by remember { mutableStateOf<String?>(null) }
    var capturePromptedMac by remember { mutableStateOf<String?>(null) }
    val preferences = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val captureFailedText = stringResource(R.string.audio_relay_capture_failed)
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val mac = pendingCaptureMac
        pendingCaptureMac = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && mac != null) {
            val mute = preferences.getBoolean("audio_relay_mute_local_${mac.uppercase()}", true)
            runCatching { relay?.startCapture(result.resultCode, result.data!!, mute) }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: captureFailedText, android.widget.Toast.LENGTH_LONG).show() }
        } else relay?.stopRelay()
    }
    val recordingPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingCaptureMac != null) projectionLauncher.launch(createRelayCaptureConsent(context))
        else {
            pendingCaptureMac = null
            relay?.stopRelay()
        }
    }
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mac = pendingCaptureMac
        if (granted && mac != null) {
            val startIntent = Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService")
                .setAction("io.automated.ventures.everypods.relay.START")
            ContextCompat.startForegroundService(context, startIntent)
            relay?.startSource(mac)
        } else {
            pendingCaptureMac = null
            if (!granted) relay?.stopRelay()
        }
    }
    fun startSourceFromHome(mac: String) {
        pendingCaptureMac = mac
        capturePromptedMac = null
        val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(context, localNetworkPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            localNetworkLauncher.launch(localNetworkPermission)
        } else {
            val startIntent = Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService")
                .setAction("io.automated.ventures.everypods.relay.START")
            ContextCompat.startForegroundService(context, startIntent)
            relay?.startSource(mac)
        }
    }
    androidx.compose.runtime.LaunchedEffect(state.peerMac, state.role, state.captureApprovalReady, pendingCaptureMac) {
        val mac = pendingCaptureMac
        if (mac != null && state.role == RelayRole.SOURCE && state.peerMac.equals(mac, true) &&
            state.captureApprovalReady && !capturePromptedMac.equals(mac, true)) {
            capturePromptedMac = mac
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                projectionLauncher.launch(createRelayCaptureConsent(context))
            } else recordingPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    androidx.compose.runtime.LaunchedEffect(relay, localAirPodsConnected, peers) {
        if (localAirPodsConnected) {
            val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val trustedSender = peers.firstOrNull { candidate ->
                candidate.connected && relay?.isPeerTrusted(candidate.mac) == true &&
                    preferences.getBoolean("audio_relay_allow_${candidate.mac.uppercase()}", true)
            }
            trustedSender?.let { candidate ->
                val startIntent = Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService")
                    .setAction("io.automated.ventures.everypods.relay.START")
                ContextCompat.startForegroundService(context, startIntent)
                relay?.prepareReceiver(candidate.mac)
            }
        }
    }
    val active = state.stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED)
    val receiverNeedsControls = localAirPodsConnected && (
        (state.role == RelayRole.RECEIVER && (active || state.reason != null)) || state.pendingSenderMac != null
    )
    val showCard = !localAirPodsConnected || receiverNeedsControls
    androidx.compose.runtime.SideEffect { onContentVisibilityChanged(showCard) }
    // A device holding AirPods is only the receiver. Keep receiver-side Stop and
    // switch approval visible during a session, but hide the idle transmission card.
    if (!showCard) return
    val peerName = peers.firstOrNull { it.mac.equals(state.peerMac, true) }?.name
        ?: stringResource(R.string.audio_relay_device_fallback)
    val pendingName = peers.firstOrNull { it.mac.equals(state.pendingSenderMac, true) }?.name
        ?: stringResource(R.string.audio_relay_device_fallback)
    val body = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.White else Color.Black
    var showDetails by remember { mutableStateOf(false) }
    var networkChoice by remember(state.peerMac) {
        val key = state.peerMac?.let { "audio_relay_network_${it.uppercase()}" } ?: "audio_relay_network_default"
        mutableStateOf(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(key, "auto") ?: "auto")
    }

    Column(modifier.fillMaxWidth().clickable { showDetails = true }) {
        if (!localAirPodsConnected) Text(
            stringResource(R.string.audio_relay_relay_home_title),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily(Font(R.font.sf_pro)),
            color = if (body == Color.White) body.copy(alpha = .72f) else body.copy(alpha = .65f),
        )
        SettingsGroup {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.pendingSenderMac != null && localAirPodsConnected) {
                    Text(stringResource(R.string.audio_relay_switch_request, pendingName, peerName), color = body)
                    Column {
                        Button(onClick = { relay?.approvePendingSwitch() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.audio_relay_switch_to_peer, pendingName))
                        }
                        OutlinedButton(onClick = { relay?.declinePendingSwitch() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.audio_relay_keep_current))
                        }
                    }
                }
                if (localAirPodsConnected) {
                    Text(stringResource(if (active && state.role == RelayRole.RECEIVER) R.string.audio_relay_receiving_from_peer else R.string.audio_relay_ready_receiver, peerName), color = body)
                    if (active && state.role == RelayRole.RECEIVER) {
                        RelayInterruptionNotice(state, peerName, body) { relay?.resumeRelay() }
                        if (state.stage != RelayStage.SUSPENDED) Text(stringResource(if (state.stage == RelayStage.BUFFERING) R.string.audio_relay_buffering else if (state.stage == RelayStage.STREAMING) R.string.audio_relay_streaming else R.string.audio_relay_waiting_audio), color = body.copy(alpha = .65f))
                        OutlinedButton(onClick = { relay?.stopRelay() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.audio_relay_stop)) }
                    }
                    if (!active) state.reason?.let { Text(it, color = body.copy(alpha = .7f)) }
                }
                if (!localAirPodsConnected) wifiPeers.forEach { candidate ->
                    val selected = state.role == RelayRole.SOURCE && state.peerMac.equals(candidate.mac, true)
                    val selectedActive = selected && active
                    val authorized = relay?.isPeerTrusted(candidate.mac) == true
                    val occupied = !localAirPodsConnected && state.peerMac.equals(candidate.mac, true) && state.remoteReceiverActive
                    val canStart = !localAirPodsConnected && !active && authorized && candidate.connected && candidate.hasPods
                    val label = when {
                        selectedActive && state.waitingForReceiverApproval -> stringResource(R.string.audio_relay_cancel_request)
                        selectedActive && state.stage == RelayStage.SUSPENDED -> candidate.name + " · " + stringResource(R.string.audio_relay_stop)
                        selectedActive -> stringResource(R.string.audio_relay_sending_to_peer, candidate.name) + " · " + stringResource(R.string.audio_relay_stop)
                        canStart && occupied -> candidate.name + " · " + stringResource(R.string.audio_relay_request_switch)
                        canStart -> stringResource(R.string.audio_relay_play_through_peer, candidate.name)
                        else -> candidate.name
                    }
                    OutlinedButton(
                        onClick = {
                            if (selectedActive) {
                                pendingCaptureMac = null
                                relay?.stopRelay()
                            }
                            else if (localAirPodsConnected) navController.navigate("device_playback/${android.net.Uri.encode(candidate.mac)}")
                            else startSourceFromHome(candidate.mac)
                        },
                        enabled = selectedActive || canStart || localAirPodsConnected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(label) }
                    val detail = when {
                        selectedActive && state.waitingForReceiverApproval -> stringResource(R.string.audio_relay_waiting_approval, candidate.name)
                        selectedActive && state.stage == RelayStage.SUSPENDED -> stringResource(R.string.audio_relay_suspended)
                        selectedActive -> stringResource(if (state.stage == RelayStage.BUFFERING) R.string.audio_relay_buffering else if (state.stage == RelayStage.STREAMING) R.string.audio_relay_streaming else if (state.captureApprovalReady) R.string.audio_relay_waiting_audio else R.string.audio_relay_connecting)
                        selected && state.stage in setOf(RelayStage.FAILED, RelayStage.STOPPED) && state.reason != null -> state.reason ?: stringResource(R.string.audio_relay_action_needed)
                        !authorized -> stringResource(R.string.audio_relay_peer_not_authorized)
                        !candidate.connected -> stringResource(R.string.audio_relay_peer_offline)
                        localAirPodsConnected -> stringResource(R.string.audio_relay_peer_online)
                        !candidate.hasPods -> stringResource(R.string.connection_no_airpods_there)
                        occupied -> stringResource(R.string.audio_relay_receiver_receiving_from_sender, candidate.name, state.remoteActiveSenderName.orEmpty())
                        else -> stringResource(R.string.audio_relay_start_detail, candidate.name)
                    }
                    if (selectedActive && state.stage == RelayStage.SUSPENDED) {
                        RelayInterruptionNotice(state, candidate.name, body) { relay?.resumeRelay() }
                    } else Text(detail, color = body.copy(alpha = .65f))
                }
                if (!localAirPodsConnected && wifiPeers.isEmpty()) {
                    Text(stringResource(R.string.connection_add_wifi_hint), color = body.copy(alpha = .65f))
                    TextButton(onClick = { navController.navigate("category/connection?anchor=connections") }) { Text(stringResource(R.string.connection_add)) }
                }
            }
        }
    }
    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.audio_relay_relay_home_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = body)
                Text(when {
                    state.role == RelayRole.SOURCE -> stringResource(R.string.audio_relay_sending_to_peer, peerName)
                    state.role == RelayRole.RECEIVER && state.peerMac != null -> stringResource(R.string.audio_relay_receiving_from_peer, peerName)
                    localAirPodsConnected -> stringResource(R.string.audio_relay_ready_receiver)
                    else -> stringResource(R.string.audio_relay_connect_airpods)
                }, color = body)
                RelayInterruptionNotice(state, peerName, body) { relay?.resumeRelay() }
                if (active) OutlinedButton(onClick = { relay?.stopRelay() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.audio_relay_stop))
                }
                wifiPeers.forEach { candidate ->
                    val readiness = state.peerReadiness[candidate.mac.uppercase()]
                    val online = stringResource(if (readiness?.online ?: candidate.connected) R.string.audio_relay_peer_online else R.string.audio_relay_peer_offline)
                    val authorized = stringResource(if (readiness?.authorized ?: (relay?.isPeerTrusted(candidate.mac) == true)) R.string.audio_relay_peer_authorized else R.string.audio_relay_peer_not_authorized)
                    Column {
                        Text(readiness?.name ?: candidate.name, color = body.copy(alpha = .88f))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(online, color = body.copy(alpha = .65f))
                            Text(authorized, color = body.copy(alpha = .65f))
                            if (active && state.peerMac.equals(candidate.mac, true)) Text(stringResource(R.string.audio_relay_peer_active), color = body.copy(alpha = .65f))
                        }
                    }
                }
                if (state.role == RelayRole.SOURCE || (state.role == RelayRole.NONE && state.peerMac != null)) {
                    Text(stringResource(R.string.audio_relay_network_heading), fontWeight = FontWeight.SemiBold, color = body)
                    listOf("auto" to R.string.audio_relay_network_auto, "wifi" to R.string.audio_relay_shared_wifi, "hotspot" to R.string.audio_relay_device_hotspot).forEach { (mode, label) ->
                        TextButton(onClick = {
                            if (active && networkChoice != mode) relay?.stopRelay()
                            networkChoice = mode
                            state.peerMac?.let { mac -> context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("audio_relay_network_${mac.uppercase()}", mode).apply() }
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = networkChoice == mode, onClick = null)
                                Text(stringResource(label))
                            }
                        }
                    }
                }
                if (state.role == RelayRole.RECEIVER && state.networkMode != null) {
                    Text(stringResource(R.string.audio_relay_network_heading), fontWeight = FontWeight.SemiBold, color = body)
                    Text(stringResource(when (state.networkMode) {
                        "wifi" -> R.string.audio_relay_shared_wifi
                        "hotspot" -> R.string.audio_relay_device_hotspot
                        else -> R.string.audio_relay_network_auto
                    }), color = body.copy(alpha = .72f))
                }
                TextButton(onClick = { showDetails = false; navController.navigate("category/connection?anchor=connections") }) { Text(stringResource(R.string.connections_title)) }
                val detailPeer = state.peerMac ?: peers.firstOrNull { it.connected }?.mac
                if (detailPeer != null) {
                    TextButton(onClick = { showDetails = false; navController.navigate("device_playback/${android.net.Uri.encode(detailPeer)}?handover=true") }) { Text(stringResource(R.string.audio_relay_move_airpods_here)) }
                    TextButton(onClick = { showDetails = false; navController.navigate("device_playback/${android.net.Uri.encode(detailPeer)}") }) { Text(stringResource(R.string.audio_relay_advanced)) }
                }
            }
        }
    }
}

@Composable
fun AudioRelaySessionStrip(navController: NavController, modifier: Modifier = Modifier) {
    if (!BuildConfig.AUDIO_RELAY_PREVIEW) return
    val context = LocalContext.current
    var relay by remember { mutableStateOf<RelaySessionApi?>(null) }
    DisposableEffect(context) {
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { relay = (binder as? RelaySessionBinder)?.relay() }
            override fun onServiceDisconnected(name: ComponentName?) { relay = null }
        }
        bound = context.bindService(Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService"), connection, Context.BIND_AUTO_CREATE)
        onDispose { if (bound) context.unbindService(connection) }
    }
    val state by (relay?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(RelayUiState()) }).collectAsState()
    val visible = state.stage in setOf(RelayStage.CONNECTING, RelayStage.WAITING_FOR_AUDIO, RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED) || state.pendingSenderMac != null || state.stage == RelayStage.FAILED
    if (!visible) return
    val fallback = stringResource(R.string.audio_relay_device_fallback)
    val name = remember(state.peerMac, fallback) {
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED && state.peerMac != null) {
            try { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(state.peerMac!!)?.name?.takeIf(String::isNotBlank) ?: fallback }
            catch (_: SecurityException) { fallback }
        } else fallback
    }
    Column(
        modifier = modifier.fillMaxWidth()
            .background(if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color(0xFFFFFFFF), RoundedCornerShape(16.dp))
            .clickable {
                val peer = state.peerMac
                if (peer != null) navController.navigate("device_playback/${android.net.Uri.encode(peer)}")
                else navController.navigate("paired_devices")
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when {
                state.stage == RelayStage.SUSPENDED -> relaySuspensionMessage(state, name)
                state.stage == RelayStage.FAILED -> state.reason ?: stringResource(R.string.audio_relay_action_needed)
                state.role == RelayRole.SOURCE && state.stage in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED) -> stringResource(R.string.audio_relay_sending_to_peer, name)
                state.role == RelayRole.RECEIVER && state.stage in setOf(RelayStage.STREAMING, RelayStage.BUFFERING, RelayStage.SUSPENDED) -> stringResource(R.string.audio_relay_receiving_from_peer, name)
                state.waitingForReceiverApproval -> stringResource(R.string.audio_relay_waiting_approval, name)
                state.stage == RelayStage.BUFFERING -> stringResource(R.string.audio_relay_buffering)
                state.stage == RelayStage.STREAMING -> stringResource(R.string.audio_relay_streaming)
                else -> stringResource(R.string.audio_relay_connecting)
            },
            color = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.White else Color.Black,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.stage == RelayStage.SUSPENDED) TextButton(onClick = { relay?.resumeRelay() }) { Text(stringResource(R.string.audio_relay_resume)) }
            TextButton(onClick = { relay?.stopRelay() }) { Text(stringResource(R.string.audio_relay_stop)) }
        }
    }
}
