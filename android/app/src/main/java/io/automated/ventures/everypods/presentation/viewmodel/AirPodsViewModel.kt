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

package io.automated.ventures.everypods.presentation.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.automated.ventures.everypods.billing.BillingManager
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import io.automated.ventures.everypods.data.AirPodsInstance
import io.automated.ventures.everypods.data.AirPodsModels
import io.automated.ventures.everypods.data.AirPodsNotifications
import io.automated.ventures.everypods.data.Battery
import io.automated.ventures.everypods.data.CustomEq
import io.automated.ventures.everypods.data.BatteryComponent
import io.automated.ventures.everypods.data.BatteryStatus
import io.automated.ventures.everypods.data.Capability
import io.automated.ventures.everypods.data.ControlCommandRepository
import io.automated.ventures.everypods.data.StemAction
import io.automated.ventures.everypods.services.AirPodsService
import io.automated.ventures.everypods.utils.CrossDevice
import io.automated.ventures.everypods.utils.CrossDeviceClient

/** Per-peer status exposed to the UI. Built each poll tick from CrossDevice state. */
data class PeerUiInfo(
    val mac: String,
    val name: String,
    val connected: Boolean,
    val hasPods: Boolean,
)

@Suppress("ArrayInDataClass")
data class AirPodsUiState(
    val deviceName: String,

    val isLocallyConnected: Boolean = false,

    val instance: AirPodsInstance? = null,
    val capabilities: Set<Capability> = emptySet(),

    val controlStates: Map<ControlCommandIdentifiers, ByteArray> = emptyMap(),
    val offListeningMode: Boolean = true,

    val battery: List<Battery> = emptyList(),
    val ancMode: Int = 3,

    val modelName: String = "",
    val actualModel: String = "",
    val serialNumbers: List<String> = emptyList(),
    val version1: String = "",
    val version2: String = "",
    val version3: String = "",

    val headTrackingActive: Boolean = false,
    val headGesturesEnabled: Boolean = false,
    val headGesturesAnswerCall: Boolean = true,
    val headGesturesMuteCall: Boolean = true,

    val eqData: FloatArray = floatArrayOf(),

    val customEq: CustomEq = CustomEq(state = 1, low = 50, mid = 50, high = 50),

    val automaticEarDetectionEnabled: Boolean = true,
    val automaticConnectionEnabled: Boolean = true,
    val crossDeviceEnabled: Boolean = false,
    val crossDevicePeers: List<PeerUiInfo> = emptyList(),

    val leftAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,
    val rightAction: StemAction = StemAction.CYCLE_NOISE_CONTROL_MODES,

    val isPremium: Boolean = true,

    val dynamicEndOfCharge: Boolean = true,

    val connectionSuccessful: Boolean = false,

    // True when the AirPods are connected to this device over standard
    // Bluetooth A2DP, independent of the AACP socket. Lets the UI show
    // "connected via standard Bluetooth" on AACP-less devices (e.g. Xiaomi).
    val isA2dpConnected: Boolean = false,

    // True when a previously-used AirPods MAC is saved — gates the manual
    // "reconnect to last device" button so it is available even when the
    // AACP connection has never succeeded.
    val hasSavedDevice: Boolean = false,

    // True when the device can in principle open the AACP L2CAP socket
    // (privileged Pixel build or a supported OEM AACP-capable build). UI uses
    // this to grey out AACP-only controls on limited-mode devices while
    // keeping the same navigation surface.
    val aacpAvailable: Boolean = false
)

class AirPodsViewModel(
    private val service: AirPodsService,
    private val sharedPreferences: SharedPreferences,
    private val controlRepo: ControlCommandRepository,
    private val appContext: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AirPodsUiState(
            deviceName = preferredDeviceName(),
            aacpAvailable = computeAacpAvailable()
        )
    )
    val uiState: StateFlow<AirPodsUiState> = _uiState

    private fun computeAacpAvailable(): Boolean =
        io.automated.ventures.everypods.utils.isAacpCapable()

    /**
     * Re-evaluate the AACP capability flag. AACP capability may become
     * available asynchronously after the ViewModel constructor ran (e.g. the
     * OEM companion app finishes its own setup). Callers (MainActivity
     * lifecycle observer, service connect, etc.) should call this whenever
     * there's a reasonable chance the value changed so the UI un-greys AACP
     * controls.
     */
    fun refreshAacpAvailable() {
        val now = computeAacpAvailable()
        if (now != _uiState.value.aacpAvailable) {
            _uiState.update { it.copy(aacpAvailable = now) }
        }
    }

    private var isDemoMode = false
    val demoActivated = MutableSharedFlow<Unit>()

    private val listeners =
        mutableMapOf<ControlCommandIdentifiers, AACPManager.ControlCommandListener>()

    private lateinit var broadcastReceiver: BroadcastReceiver

    private val _cameraAction = MutableStateFlow(
        sharedPreferences.getString("camera_action", null)
            ?.let { value -> AACPManager.Companion.StemPressType.entries.find { it.name == value } })

    val cameraAction: StateFlow<AACPManager.Companion.StemPressType?> = _cameraAction

    fun setCameraAction(action: AACPManager.Companion.StemPressType?) {
        sharedPreferences.edit {
            if (action == null) remove("camera_action")
            else putString("camera_action", action.name)
        }
        _cameraAction.value = action
    }

    init {
        observeBroadcasts()
        loadName()
        loadInstance()
        loadSharedPreferences()
        setupControlObservers()
        observeBilling()
        loadControlList()
        if (isDemoMode) activateDemoMode()
        refreshInitialData()
        pollCrossDeviceStatus()
    }

    override fun onCleared() {
        listeners.forEach { (id, listener) ->
            controlRepo.remove(id, listener)
        }

        appContext.unregisterReceiver(broadcastReceiver)

        super.onCleared()
    }

    private fun loadName() {
        _uiState.update { it.copy(deviceName = preferredDeviceName()) }
    }

    private fun preferredDeviceName(): String {
        val savedName = sharedPreferences.getString("name", null)
        val bluetoothName = service.device?.name?.takeIf { it.isNotBlank() }
        return if (savedName.isNullOrBlank() || savedName == "AirPods" || savedName == "AirPods Pro") {
            bluetoothName ?: savedName ?: "AirPods"
        } else {
            savedName
        }
    }

    private fun observeBilling() {
        if (isDemoMode) return
        viewModelScope.launch {
//            if (!BuildConfig.PLAY_BUILD) billingFirstCollectDone = true // FOSS doesn't send multiple events
            BillingManager.provider.isPremium.collect { premium ->
//                if (!billingFirstCollectDone) {
//                    billingFirstCollectDone = true
//                    return@collect
//                }
                if (!premium) {
                    setControlCommandBoolean(
                        ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
                        false
                    )
                    setHeadGesturesEnabled(false)
                    setHeadGesturesAnswerCall(false)
                    setHeadGesturesMuteCall(false)
                }
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
    }

    private fun observeBroadcasts() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (!isDemoMode) when (action) {
                    AirPodsNotifications.AIRPODS_L2CAP_CONNECTED -> {
                        loadName()
                        _uiState.update {
                            it.copy(isLocallyConnected = true)
                        }
                        // Also refresh the standard-Bluetooth / saved-device flags.
                        refreshInitialData()
                    }

                    AirPodsNotifications.AIRPODS_CONNECTED -> {
                        // Standard-Bluetooth (A2DP) connection event — may fire on
                        // AACP-less devices where AIRPODS_L2CAP_CONNECTED never does.
                        refreshInitialData()
                    }

                    AirPodsNotifications.AIRPODS_DISCONNECTED -> {
                        _uiState.update {
                            it.copy(isLocallyConnected = false)
                        }
                        refreshInitialData()
                    }

                    AirPodsNotifications.BATTERY_DATA -> {
                        _uiState.update {
                            it.copy(battery = service.getBattery())
                        }
                    }

                    AirPodsNotifications.EQ_DATA -> {
                        val data = intent.getFloatArrayExtra("eqData") ?: floatArrayOf()

                        _uiState.update {
                            it.copy(eqData = data)
                        }
                    }

                    AirPodsNotifications.CUSTOM_EQ_DATA -> {
                        val state = intent.getIntExtra("state", 1)
                        val low   = intent.getIntExtra("low",   50)
                        val mid   = intent.getIntExtra("mid",   50)
                        val high  = intent.getIntExtra("high",  50)
                        _uiState.update {
                            it.copy(customEq = CustomEq(state, low, mid, high))
                        }
                    }

                    AirPodsNotifications.AIRPODS_INFORMATION_UPDATED -> {
                        loadName()
                        loadInstance()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
            addAction(AirPodsNotifications.BATTERY_DATA)
            addAction(AirPodsNotifications.EQ_DATA)
            addAction(AirPodsNotifications.CUSTOM_EQ_DATA)
            addAction(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED)
        }

        appContext.registerReceiver(
            broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun setControlCommandValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ) {
        if (!isDemoMode) controlRepo.setValue(identifier, value)
        _uiState.update {
            it.copy(
                controlStates = it.controlStates + (identifier to value)
            )
        }
    }

    fun setControlCommandBoolean(
        identifier: ControlCommandIdentifiers, enabled: Boolean
    ) {
        setControlCommandValue(
            identifier, if (enabled) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
    }

    fun setControlCommandInt(
        identifier: ControlCommandIdentifiers, value: Int
    ) {
        setControlCommandValue(identifier, byteArrayOf(value.toByte()))
    }

    fun setControlCommandByte(
        identifier: ControlCommandIdentifiers, value: Byte
    ) {
        setControlCommandValue(identifier, byteArrayOf(value))
    }

    fun observeControl(identifier: ControlCommandIdentifiers) {
        val listener = controlRepo.observe(identifier) { value ->
            _uiState.update { state ->
                val current = state.controlStates[identifier]
                if (current?.contentEquals(value) == true) return@update state

                if (identifier == ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE) {
                    state.copy(
                        dynamicEndOfCharge = value[0] == 0x01.toByte(),
                        controlStates = state.controlStates + (identifier to value)
                    )
                } else {
                    state.copy(
                        controlStates = state.controlStates + (identifier to value)
                    )
                }
            }
        }

        listeners[identifier] = listener
    }

    // I'm lazy, sorry.
    fun setupControlObservers() {
        val identifiersList = listOf(
            ControlCommandIdentifiers.MIC_MODE,
            ControlCommandIdentifiers.DOUBLE_CLICK_INTERVAL,
            ControlCommandIdentifiers.CLICK_HOLD_INTERVAL,
            ControlCommandIdentifiers.LISTENING_MODE_CONFIGS,
            ControlCommandIdentifiers.ONE_BUD_ANC_MODE,
            ControlCommandIdentifiers.LISTENING_MODE,
            ControlCommandIdentifiers.AUTO_ANSWER_MODE,
            ControlCommandIdentifiers.CHIME_VOLUME,
            ControlCommandIdentifiers.VOLUME_SWIPE_INTERVAL,
            ControlCommandIdentifiers.CALL_MANAGEMENT_CONFIG,
            ControlCommandIdentifiers.VOLUME_SWIPE_MODE,
            ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG,
            ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG,
            ControlCommandIdentifiers.HEARING_AID,
            ControlCommandIdentifiers.AUTO_ANC_STRENGTH,
            ControlCommandIdentifiers.HPS_GAIN_SWIPE,
            ControlCommandIdentifiers.HEARING_ASSIST_CONFIG,
            ControlCommandIdentifiers.ALLOW_OFF_OPTION,
            ControlCommandIdentifiers.STEM_CONFIG,
            ControlCommandIdentifiers.SLEEP_DETECTION_CONFIG,
            ControlCommandIdentifiers.ALLOW_AUTO_CONNECT,
            ControlCommandIdentifiers.EAR_DETECTION_CONFIG,
            ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG,
            ControlCommandIdentifiers.OWNS_CONNECTION,
            ControlCommandIdentifiers.PPE_TOGGLE_CONFIG,
            ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE
        )
        for (identifier in identifiersList) {
            observeControl(identifier)
        }
    }

    fun refreshInitialData() {
        if (isDemoMode) return
        service.let { service ->
            val savedMac = sharedPreferences.getString("mac_address", "") ?: ""
            _uiState.update {
                it.copy(
                    isLocallyConnected = service.isConnected(),
                    battery = service.getBattery(),
                    isA2dpConnected = service.isA2dpConnected(),
                    hasSavedDevice = savedMac.isNotEmpty()
                )
            }
        }
    }

    private fun readFirstConfiguredPeer(prefs: android.content.SharedPreferences): String? {
        val json = prefs.getString("cross_device_peers", null)
        if (json != null) {
            val arr = org.json.JSONArray(json)
            if (arr.length() > 0) return arr.getString(0)
        }
        return prefs.getString("cross_device_peer_mac", null)
    }

    private fun loadSharedPreferences() {
        val offListeningModeEnabled = sharedPreferences.getBoolean("off_listening_mode", true)
        val automaticEarDetectionEnabled =
            sharedPreferences.getBoolean("automatic_ear_detection", true)
        val automaticConnectionEnabled =
            sharedPreferences.getBoolean("automatic_connection_ctrl_cmd", true)
        val crossDeviceEnabled =
            sharedPreferences.getBoolean("cross_device_enabled", CrossDevice.configuredPeers.isNotEmpty())
        val headGesturesEnabled = sharedPreferences.getBoolean("head_gestures_enabled", false)
        val headGesturesAnswerCall = sharedPreferences.getBoolean("head_gestures_answer_call", true)
        val headGesturesMuteCall = sharedPreferences.getBoolean("head_gestures_mute_call", true)
        val leftAction = StemAction.valueOf(
            sharedPreferences.getString(
                "left_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val rightAction = StemAction.valueOf(
            sharedPreferences.getString(
                "right_long_press_action",
                "CYCLE_NOISE_CONTROL_MODES"
            ) ?: "CYCLE_NOISE_CONTROL_MODES"
        )
        val dynamicEndOfCharge = sharedPreferences.getBoolean("dynamic_end_of_charge", true)

        val connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false)

        _uiState.update {
            it.copy(
                offListeningMode = offListeningModeEnabled,
                automaticEarDetectionEnabled = automaticEarDetectionEnabled,
                automaticConnectionEnabled = automaticConnectionEnabled,
                crossDeviceEnabled = crossDeviceEnabled,
                headGesturesEnabled = headGesturesEnabled,
                headGesturesAnswerCall = headGesturesAnswerCall,
                headGesturesMuteCall = headGesturesMuteCall,
                leftAction = leftAction,
                rightAction = rightAction,
                dynamicEndOfCharge = dynamicEndOfCharge,
                connectionSuccessful = connectionSuccessful
            )
        }
    }

    fun setOffListeningMode(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("off_listening_mode", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.ALLOW_OFF_OPTION, enabled)
        _uiState.update {
            it.copy(offListeningMode = enabled)
        }
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures_enabled", enabled) }
        _uiState.update {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setHeadGesturesAnswerCall(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures_answer_call", enabled) }
        _uiState.update {
            it.copy(headGesturesAnswerCall = enabled)
        }
    }

    fun setHeadGesturesMuteCall(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("head_gestures_mute_call", enabled) }
        _uiState.update {
            it.copy(headGesturesMuteCall = enabled)
        }
    }

    fun setDynamicEndOfCharge(enabled: Boolean) {
        service.aacpManager.sendControlCommand(ControlCommandIdentifiers.DYNAMIC_END_OF_CHARGE.value, enabled)
        sharedPreferences.edit { putBoolean("dynamic_end_of_charge", enabled) }
        _uiState.update {
            it.copy(dynamicEndOfCharge = enabled)
        }
    }

    private fun loadControlList() {
        val map = controlRepo.getMap().toMutableMap()
        if (!map.containsKey(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS)) {
            val saved = sharedPreferences.getInt("long_press_byte", 0b0111)
            map[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS] = byteArrayOf(saved.toByte())
        }
        if (!map.containsKey(ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG)) {
            val saved = sharedPreferences.getBoolean("conversation_detect_config", true)
            map[ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG] = byteArrayOf(if (saved) 0x01 else 0x02)
        }
        _uiState.update {
            it.copy(controlStates = map)
        }
    }

    private fun loadInstance() {
        val instance = service.airpodsInstance ?: AirPodsInstance(
            name = "AirPods",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = null,
            leftSerialNumber = null,
            rightSerialNumber = null,
            version1 = null,
            version2 = null,
            version3 = null,
        )

        _uiState.update {
            it.copy(
                capabilities = instance.model.capabilities,
                instance = instance,
                modelName = instance.model.displayName,
                actualModel = instance.actualModelNumber,
                serialNumbers = listOf(
                    instance.serialNumber ?: "",
                    instance.leftSerialNumber ?: "",
                    instance.rightSerialNumber ?: ""
                ),
                version1 = instance.version1 ?: "",
                version2 = instance.version2 ?: "",
                version3 = instance.version3 ?: ""
            )
        }
    }

    fun reconnectFromSavedMac() {
        service.reconnectFromSavedMac()
    }

    fun setName(name: String) {
        service.setName(name)
    }

    fun startHeadTracking() {
        service.startHeadTracking()
        _uiState.update { it.copy(headTrackingActive = true) }
    }

    fun stopHeadTracking() {
        service.stopHeadTracking()
        _uiState.update { it.copy(headTrackingActive = false) }
    }

    fun setAutomaticEarDetectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.EAR_DETECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticEarDetectionEnabled = enabled
            )
        }
    }

    fun setAutomaticConnectionEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("automatic_connection_ctrl_cmd", enabled) }
        setControlCommandBoolean(ControlCommandIdentifiers.AUTOMATIC_CONNECTION_CONFIG, enabled)
        _uiState.update {
            it.copy(
                automaticConnectionEnabled = enabled
            )
        }
    }

    fun setCrossDeviceEnabled(enabled: Boolean) {
        CrossDevice.setEnabled(appContext, enabled)
        _uiState.update { it.copy(crossDeviceEnabled = enabled) }
    }

    /** Add [mac] to the configured peer set and reconcile live links. */
    @android.annotation.SuppressLint("MissingPermission")
    fun addCrossDevicePeer(mac: String) {
        val current = CrossDevice.configuredPeers.toMutableSet()
        if (current.add(mac)) {
            sharedPreferences.edit {
                putString("cross_device_peers", org.json.JSONArray(current.toList()).toString())
                remove("cross_device_peer_mac")
            }
            CrossDevice.init(appContext)
        }
    }

    /** Remove [mac] from the configured peer set and tear down its RFCOMM link. */
    fun removeCrossDevicePeer(mac: String) {
        val current = CrossDevice.configuredPeers.toMutableSet()
        if (current.remove(mac)) {
            sharedPreferences.edit {
                putString("cross_device_peers", org.json.JSONArray(current.toList()).toString())
            }
            CrossDeviceClient.stop(mac)
            CrossDevice.configuredPeers = current
        }
    }

    /** Cancel the backoff delay for [mac] and retry its RFCOMM connect immediately. */
    fun reconnectCrossDevicePeer(mac: String) {
        CrossDeviceClient.retryNow(mac)
    }

    /** Legacy single-peer setter — kept for callers that haven't been updated yet. */
    @android.annotation.SuppressLint("MissingPermission")
    fun setCrossDevicePeerMac(mac: String) {
        sharedPreferences.edit {
            putString("cross_device_peers", org.json.JSONArray(listOf(mac)).toString())
            remove("cross_device_peer_mac")
        }
        CrossDeviceClient.stop()
        CrossDevice.init(appContext)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun reconnectCrossDevice() {
        android.util.Log.d("AirPodsViewModel", "reconnectCrossDevice — cycling server + client")
        CrossDeviceClient.stop()
        CrossDevice.restartServer(appContext)
        CrossDevice.init(appContext)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun buildPeerList(): List<PeerUiInfo> {
        val adapter = runCatching {
            val bt = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)
            bt?.adapter
        }.getOrNull() ?: return emptyList()
        return CrossDevice.configuredPeers.map { mac ->
            val name = runCatching { adapter.getRemoteDevice(mac).name }.getOrNull() ?: mac
            PeerUiInfo(
                mac = mac,
                name = name,
                connected = CrossDevice.isConnectedTo(mac),
                hasPods = CrossDevice.holders.contains(mac),
            )
        }
    }

    private fun pollCrossDeviceStatus() {
        android.util.Log.d("AirPodsViewModel", "pollCrossDeviceStatus started (scope=$viewModelScope)")
        viewModelScope.launch {
            var tick = 0
            while (true) {
                val peers = buildPeerList()
                // Also refresh the standard-Bluetooth (A2DP) connection flag here so
                // the UI reflects it on AACP-less devices without needing a broadcast.
                val a2dp = if (isDemoMode) _uiState.value.isA2dpConnected
                           else runCatching { service.isA2dpConnected() }.getOrDefault(false)
                if (tick % 10 == 0) {
                    android.util.Log.d("AirPodsViewModel",
                        "pollCrossDeviceStatus tick=$tick peers=${peers.map { "${it.name}:connected=${it.connected}:hasPods=${it.hasPods}" }}")
                }
                _uiState.update { it.copy(crossDevicePeers = peers, isA2dpConnected = a2dp) }
                tick++
                delay(2000)
            }
        }
    }

    fun activateDemoMode() {
        isDemoMode = true
        viewModelScope.launch {
            demoActivated.emit(Unit)
        }
        val fakeInstance = AirPodsInstance(
            name = "AirPods Pro (Demo)",
            model = AirPodsModels.getModelByModelNumber("A3049")!!,
            actualModelNumber = "A3049",
            serialNumber = "DEMO123",
            leftSerialNumber = "L-DEMO",
            rightSerialNumber = "R-DEMO",
            version1 = "1.0",
            version2 = "1.0",
            version3 = "1.0",
        )

        _uiState.update {
            it.copy(
                isLocallyConnected = true,
                instance = fakeInstance,
                capabilities = fakeInstance.model.capabilities,

                battery = listOf(
                    Battery(BatteryComponent.LEFT, 85, BatteryStatus.CHARGING),
                    Battery(BatteryComponent.RIGHT, 25, BatteryStatus.NOT_CHARGING),
                    Battery(BatteryComponent.CASE, 85, BatteryStatus.CHARGING),
                ),

                modelName = fakeInstance.model.displayName,
                actualModel = fakeInstance.actualModelNumber,
                serialNumbers = listOf("DEMO", "DEMO", "DEMO"),
                version3 = "Demo Firmware",
                isPremium = true
            )
        }
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phoneByte: Byte, mediaByte: Byte) {
        service.aacpManager.sendPhoneMediaEQ(eq, phoneByte, mediaByte)
    }

    fun setCustomEqEnabled(enabled: Boolean) {
        val current = _uiState.value.customEq
        val updated = current.copy(state = if (enabled) 2 else 1)
        _uiState.update { it.copy(customEq = updated) }
        service.aacpManager.sendCustomEqPacket(updated)
    }

    fun setCustomEq(low: Int, mid: Int, high: Int) {
        val current = _uiState.value.customEq
        val updated = current.copy(low = low, mid = mid, high = high)
        _uiState.update { it.copy(customEq = updated) }
        service.aacpManager.sendCustomEqPacket(updated)
    }

    fun setLongPressAction(side: String, action: StemAction) {
        val prefKey = if (side.lowercase() == "left") "left_long_press_action" else "right_long_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
        _uiState.update {
            if (side.lowercase() == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
        }
    }

    /** Set any press type action for a given bud. Writes to the same prefs the service reads. */
    fun setPressAction(side: String, pressType: io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType, action: StemAction) {
        val sideLower = side.lowercase()
        val prefKey = when (pressType) {
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.SINGLE_PRESS ->
                if (sideLower == "left") "left_single_press_action" else "right_single_press_action"
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.DOUBLE_PRESS ->
                if (sideLower == "left") "left_double_press_action" else "right_double_press_action"
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.TRIPLE_PRESS ->
                if (sideLower == "left") "left_triple_press_action" else "right_triple_press_action"
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.LONG_PRESS ->
                if (sideLower == "left") "left_long_press_action" else "right_long_press_action"
        }
        sharedPreferences.edit { putString(prefKey, action.name) }
        // Also update UiState for long press (only field we currently expose there)
        if (pressType == io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.LONG_PRESS) {
            _uiState.update {
                if (sideLower == "left") it.copy(leftAction = action) else it.copy(rightAction = action)
            }
        }
    }

    fun setGymPressAction(side: String, pressType: io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType, action: StemAction) {
        val sideLower = side.lowercase()
        val pressShort = when (pressType) {
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.DOUBLE_PRESS -> "double"
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.TRIPLE_PRESS -> "triple"
            io.automated.ventures.everypods.bluetooth.AACPManager.Companion.StemPressType.LONG_PRESS -> "long"
            else -> return
        }
        val prefKey = "gym_${sideLower}_${pressShort}_press_action"
        sharedPreferences.edit { putString(prefKey, action.name) }
    }

    fun setGymModeEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("gym_mode_enabled", enabled) }
    }

    private fun countEnabledModes(byteValue: Int): Int {
        var count = 0
        if ((byteValue and 0x01) != 0) count++
        if ((byteValue and 0x02) != 0) count++
        if ((byteValue and 0x04) != 0) count++
        if ((byteValue and 0x08) != 0) count++
        return count
    }

    fun toggleListeningMode(modeBit: Int) {
        val currentByte = uiState.value.controlStates[ControlCommandIdentifiers.LISTENING_MODE_CONFIGS]?.get(0)?.toInt() ?: 0
        val isDeselecting = (currentByte and modeBit) != 0
        val newValue = if (isDeselecting) {
            val temp = currentByte and modeBit.inv()
            if (countEnabledModes(temp) >= 2) {
                temp
            } else {
                // Can't deselect — would leave fewer than 2 modes. Inform the user.
                Toast.makeText(
                    appContext,
                    "At least 2 modes must be selected",
                    Toast.LENGTH_SHORT
                ).show()
                return   // exit without changing anything
            }
        } else {
            currentByte or modeBit
        }
        setControlCommandByte(ControlCommandIdentifiers.LISTENING_MODE_CONFIGS, newValue.toByte())
        sharedPreferences.edit { putInt("long_press_byte", newValue) }
    }

    fun disconnect() {
        service.disconnectAirPods()
        if (appContext.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(appContext, "App has disconnected, disconnect from Android Settings.",
                Toast.LENGTH_LONG).show()
        }
    }
}
