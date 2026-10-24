/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.presentation.viewmodel.PeerUiInfo
import io.automated.ventures.everypods.relay.*

@Composable
fun rememberDeviceConnections(peers: List<PeerUiInfo>): List<DeviceConnectionRecord> {
    val context = LocalContext.current
    val store = remember(context) { DeviceConnectionStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(peers, revision) {
        store.migrate(peers.map { it.mac })
        store.records(peers.map { it.mac })
    }
}

@Composable
fun rememberHandoverAvailable(peers: List<PeerUiInfo>): Boolean {
    val records = rememberDeviceConnections(peers)
    return records.any { it.type == DeviceConnectionType.BT_HANDOVER && !it.pendingSync }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceConnectionsList(viewModel: AirPodsViewModel, navController: NavController) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val store = remember(context) { DeviceConnectionStore(context) }
    val records = rememberDeviceConnections(state.crossDevicePeers)
    val pendingHandoverChanges = store.pendingHandoverChanges()
    var showPicker by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<DeviceConnectionRecord?>(null) }
    var bluetoothDetails by remember { mutableStateOf<PeerUiInfo?>(null) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val textColor = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black
    fun open(address: String, type: DeviceConnectionType, setup: Boolean = false) {
        if (type == DeviceConnectionType.WIFI_RELAY) {
            navController.navigate("device_playback/${android.net.Uri.encode(address)}?setup=${setup || !store.authorized(address)}")
        } else bluetoothDetails = state.crossDevicePeers.firstOrNull { it.mac.equals(address, true) }
    }
    if (showPicker) {
        val devices = context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().filter {
            it.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO
        }.sortedBy { it.name.orEmpty() }
        AlertDialog(onDismissRequest = { showPicker = false }, title = { Text(stringResource(R.string.connection_add)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                devices.forEach { device ->
                    TextButton(onClick = { selectedAddress = device.address; showPicker = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(device.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.audio_relay_device_fallback))
                    }
                }
                if (devices.isEmpty()) Text(stringResource(R.string.connection_pair_android_first))
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.ui_cancel)) } })
    }
    selectedAddress?.let { address ->
        AlertDialog(onDismissRequest = { selectedAddress = null }, title = { Text(stringResource(R.string.connection_choose_type)) }, text = {
            Column {
                DeviceConnectionType.entries.forEach { type ->
                    TextButton(onClick = {
                        val existed = store.has(address, type)
                        store.declareNew(address)
                        if (!existed || type == DeviceConnectionType.BT_HANDOVER) store.add(address, type)
                        viewModel.addCrossDevicePeer(address)
                        selectedAddress = null
                        if (type == DeviceConnectionType.WIFI_RELAY) open(address, type, setup = !existed || !store.authorized(address))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(typeLabel(type)))
                            Text(stringResource(typeDescription(type)), style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = .65f))
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { selectedAddress = null }) { Text(stringResource(R.string.ui_cancel)) } })
    }
    removing?.let { record ->
        val name = state.crossDevicePeers.firstOrNull { it.mac.equals(record.mac, true) }?.name ?: stringResource(R.string.audio_relay_device_fallback)
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.connection_remove_title)) }, text = {
            Text(stringResource(if (record.type == DeviceConnectionType.WIFI_RELAY) R.string.connection_remove_wifi else R.string.connection_remove_bt, name))
        }, confirmButton = {
            TextButton(onClick = {
                if (record.type == DeviceConnectionType.WIFI_RELAY) {
                    // Persist first so offline removal takes effect even before the service binds.
                    store.remove(record.mac, record.type)
                    ContextCompat.startForegroundService(context, Intent().setClassName(context, "io.automated.ventures.everypods.relay.RelayPreviewService")
                        .setAction("io.automated.ventures.everypods.relay.FORGET_PEER").putExtra("peer_mac", record.mac))
                } else store.remove(record.mac, record.type)
                removing = null
            }) { Text(stringResource(R.string.ui_remove)) }
        }, dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.ui_cancel)) } })
    }
    bluetoothDetails?.let { peer ->
        AlertDialog(onDismissRequest = { bluetoothDetails = null }, title = { Text(peer.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connection_type_bt))
                Text(stringResource(R.string.connection_type_bt_description))
                if (peer.connected) Text(stringResource(R.string.audio_relay_peer_online))
                else TextButton(onClick = { viewModel.reconnectCrossDevicePeer(peer.mac) }) {
                    Text(stringResource(R.string.ui_reconnect))
                }
                Text(stringResource(R.string.audio_relay_mode_bluetooth_desc))
            }
        }, confirmButton = {
            TextButton(onClick = {
                bluetoothDetails = null
                navController.navigate("device_playback/${android.net.Uri.encode(peer.mac)}?handover=true&type=bt")
            }) { Text(stringResource(R.string.audio_relay_move_airpods_here)) }
        }, dismissButton = { TextButton(onClick = { bluetoothDetails = null }) { Text(stringResource(R.string.ui_cancel)) } })
    }
    Column {
        records.forEach { record ->
            val peer = state.crossDevicePeers.firstOrNull { it.mac.equals(record.mac, true) } ?: return@forEach
            val status = stringResource(R.string.audio_relay_peer_online)
            val typeName = stringResource(typeLabel(record.type))
            val removeLabel = stringResource(R.string.connection_remove_accessibility, typeName, peer.name)
            val shortType = stringResource(
                if (record.type == DeviceConnectionType.WIFI_RELAY) R.string.connection_type_wifi_short
                else R.string.connection_type_bt_short
            )
            Column(
                Modifier.fillMaxWidth()
                    .clickable { open(peer.mac, record.type) }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.name, Modifier.weight(1f), color = textColor, fontFamily = SfPro, fontSize = 16.sp)
                    TextButton(
                        onClick = { removing = record },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = removeLabel }
                    ) { Text("−", fontSize = 24.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (record.pendingSync) {
                        Text(stringResource(R.string.connection_sync_pending), color = textColor.copy(alpha = .65f), fontFamily = SfPro, fontSize = 13.sp)
                    } else if (peer.connected) {
                        Text(status, color = androidx.compose.ui.graphics.Color(0xFF34C759), fontFamily = SfPro, fontSize = 13.sp)
                    } else {
                        TextButton(
                            onClick = { viewModel.reconnectCrossDevicePeer(peer.mac) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(stringResource(R.string.ui_reconnect), fontFamily = SfPro, fontSize = 13.sp) }
                    }
                    Text(" — $shortType", color = textColor.copy(alpha = .65f), fontFamily = SfPro, fontSize = 13.sp)
                }
                Text(stringResource(typeDescription(record.type)), color = textColor.copy(alpha = .65f), fontFamily = SfPro, fontSize = 13.sp)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = textColor.copy(alpha = .12f))
        }
        pendingHandoverChanges.filterValues { !it.enabled }.forEach { (mac, _) ->
            val name = state.crossDevicePeers.firstOrNull { it.mac.equals(mac, true) }?.name ?: stringResource(R.string.audio_relay_device_fallback)
            Text(stringResource(R.string.connection_bt_removal_pending, name), color = textColor.copy(alpha = .65f), modifier = Modifier.padding(16.dp), fontFamily = SfPro, fontSize = 13.sp)
        }
        TextButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("+ " + stringResource(R.string.connection_add), fontFamily = SfPro, fontSize = 16.sp) }
    }
}
private fun typeLabel(type: DeviceConnectionType) = when (type) {
    DeviceConnectionType.BT_HANDOVER -> R.string.connection_type_bt
    DeviceConnectionType.WIFI_RELAY -> R.string.connection_type_wifi
}

private fun typeDescription(type: DeviceConnectionType) = when (type) {
    DeviceConnectionType.BT_HANDOVER -> R.string.connection_type_bt_description
    DeviceConnectionType.WIFI_RELAY -> R.string.connection_type_wifi_description
}
