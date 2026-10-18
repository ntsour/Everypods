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

package io.automated.ventures.everypods.presentation.screens

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.NavigationButton
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.services.NotificationAnnouncementService
import io.automated.ventures.everypods.utils.AnnouncementPrefs
import io.automated.ventures.everypods.utils.ElevenLabsEngine
import io.automated.ventures.everypods.utils.TtsEngine

@Composable
fun NotificationAnnouncementsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { AnnouncementPrefs.prefs(context) }
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color.White else Color.Black

    var enabled by remember { mutableStateOf(prefs.getBoolean(AnnouncementPrefs.KEY_ENABLED, true)) }
    var skipDuringCall by remember { mutableStateOf(AnnouncementPrefs.skipDuringCall(context)) }
    var skipDuringMedia by remember { mutableStateOf(AnnouncementPrefs.skipDuringMedia(context)) }
    var onlyInEar by remember { mutableStateOf(prefs.getBoolean(AnnouncementPrefs.KEY_ONLY_IN_EAR, false)) }
    var alsoOtherBt by remember { mutableStateOf(AnnouncementPrefs.alsoAnnounceOnOtherBluetooth(context)) }
    var quietEnabled by remember { mutableStateOf(AnnouncementPrefs.quietHoursEnabled(context)) }
    var quietMode by remember { mutableStateOf(AnnouncementPrefs.quietMode(context)) }
    var quietStart by remember { mutableStateOf(AnnouncementPrefs.quietStart(context)) }
    var quietEnd by remember { mutableStateOf(AnnouncementPrefs.quietEnd(context)) }
    var contentMode by remember { mutableStateOf(prefs.getString(AnnouncementPrefs.KEY_CONTENT_MODE, AnnouncementPrefs.CONTENT_TITLE_BODY) ?: AnnouncementPrefs.CONTENT_TITLE_BODY) }
    var language by remember { mutableStateOf(prefs.getString(AnnouncementPrefs.KEY_LANGUAGE, AnnouncementPrefs.LANG_AUTO) ?: AnnouncementPrefs.LANG_AUTO) }
    val localeTags = remember(context) {
        context.resources.getStringArray(R.array.supported_locale_tags).toList()
    }

    // ElevenLabs engine
    val scope = rememberCoroutineScope()
    var ttsEngine by remember { mutableStateOf(AnnouncementPrefs.ttsEngine(context)) }
    var elApiKey by remember { mutableStateOf(AnnouncementPrefs.elevenLabsApiKey(context)) }
    var elVoiceId by remember { mutableStateOf(AnnouncementPrefs.elevenLabsVoiceId(context)) }
    var elKeyVisible by remember { mutableStateOf(false) }
    var elTestStatus by remember { mutableStateOf("") }   // "", "testing…", "OK ✓", "Error: …"
    var elVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var elVoicesLoading by remember { mutableStateOf(false) }
    var elVoiceDropdownOpen by remember { mutableStateOf(false) }
    var systemVoices by remember { mutableStateOf<Map<String, List<TtsEngine.AvailableVoice>>>(emptyMap()) }
    var systemVoicesLoading by remember { mutableStateOf(false) }

    LaunchedEffect(ttsEngine, localeTags) {
        if (ttsEngine == AnnouncementPrefs.TTS_ENGINE_SYSTEM) {
            systemVoicesLoading = true
            systemVoices = emptyMap()
            var remaining = localeTags.size
            if (remaining == 0) systemVoicesLoading = false
            localeTags.forEach { tag ->
                TtsEngine.loadAvailableVoices(context, tag) { voices ->
                    systemVoices = systemVoices + (tag to voices)
                    remaining -= 1
                    if (remaining == 0) systemVoicesLoading = false
                }
            }
        }
    }

    // Load voices when ElevenLabs is selected and key is present
    LaunchedEffect(ttsEngine, elApiKey) {
        if (ttsEngine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS && elApiKey.isNotBlank()) {
            elVoicesLoading = true
            elVoices = withContext(Dispatchers.IO) { ElevenLabsEngine.fetchVoices(elApiKey) }
            elVoicesLoading = false
        }
    }

    var notifAccess       by remember { mutableStateOf(NotificationAnnouncementService.isAccessGranted(context)) }
    var phoneStateGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) }

    // Poll all permission states every second (user may grant in system settings)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            notifAccess       = NotificationAnnouncementService.isAccessGranted(context)
            phoneStateGranted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun openAppSettings() {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    StyledScaffold(title = stringResource(R.string.ui_notification_announcements)) { topPadding, _, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_notification_announcements")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            // Permission status / request block.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ui_permissions),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                PermStatusRow(
                    label = stringResource(R.string.ui_notification_access),
                    granted = notifAccess,
                    hint = if (!notifAccess)
                        stringResource(R.string.notification_access_hint)
                    else null,
                    onClick = { NotificationAnnouncementService.openAccessSettings(context) }
                )
                PermStatusRow(stringResource(R.string.permission_call_detection), phoneStateGranted) {
                    openAppSettings()
                }
            }

            // Master + behavior toggles
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = stringResource(R.string.ui_announce_notifications),
                    description = stringResource(R.string.ui_read_incoming_notifications_an),
                    checked = enabled,
                    independent = false,
                    onCheckedChange = {
                        enabled = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_ENABLED, it).apply()
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                StyledToggle(
                    label = stringResource(R.string.ui_skip_during_a_call),
                    description = stringResource(R.string.ui_don_t_announce_while_a_call_is),
                    checked = skipDuringCall,
                    independent = false,
                    enabled = enabled,
                    onCheckedChange = {
                        skipDuringCall = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_SKIP_DURING_CALL, it).apply()
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                StyledToggle(
                    label = stringResource(R.string.ui_skip_during_active_media),
                    description = stringResource(R.string.ui_don_t_announce_while_music_or),
                    checked = skipDuringMedia,
                    independent = false,
                    enabled = enabled,
                    onCheckedChange = {
                        skipDuringMedia = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_SKIP_DURING_MEDIA, it).apply()
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                StyledToggle(
                    label = stringResource(R.string.ui_only_when_airpods_are_in_ear),
                    description = stringResource(R.string.ui_suppress_announcements_unless),
                    checked = onlyInEar,
                    independent = false,
                    enabled = enabled,
                    onCheckedChange = {
                        onlyInEar = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_ONLY_IN_EAR, it).apply()
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                StyledToggle(
                    label = stringResource(R.string.announce_other_bluetooth),
                    description = stringResource(R.string.announce_other_bluetooth_description),
                    checked = alsoOtherBt,
                    independent = false,
                    enabled = enabled,
                    onCheckedChange = {
                        alsoOtherBt = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_ANNOUNCE_ON_OTHER_BT, it).apply()
                    }
                )
            }

            // TTS Engine picker
            Text(stringResource(R.string.ui_voice_engine),
                style = TextStyle(
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            StyledSelectList(items = listOf(
                SelectItem(
                    name = stringResource(R.string.ui_system_tts),
                    selected = ttsEngine == AnnouncementPrefs.TTS_ENGINE_SYSTEM,
                    onClick = {
                        ttsEngine = AnnouncementPrefs.TTS_ENGINE_SYSTEM
                        prefs.edit().putString(AnnouncementPrefs.KEY_TTS_ENGINE, ttsEngine).apply()
                    }
                ),
                SelectItem(
                    name = stringResource(R.string.ui_elevenlabs_natural_voice),
                    selected = ttsEngine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS,
                    onClick = {
                        ttsEngine = AnnouncementPrefs.TTS_ENGINE_ELEVENLABS
                        prefs.edit().putString(AnnouncementPrefs.KEY_TTS_ENGINE, ttsEngine).apply()
                    }
                ),
            ))

            if (ttsEngine == AnnouncementPrefs.TTS_ENGINE_SYSTEM) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.ui_system_voices), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.6f), fontFamily = FontFamily(Font(R.font.sf_pro))))
                    Text(stringResource(R.string.ui_automatic_detection_stays_on_c), style = TextStyle(fontSize = 13.sp, color = textColor.copy(alpha = 0.6f), fontFamily = FontFamily(Font(R.font.sf_pro))))
                    if (systemVoicesLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    else {
                        localeTags.forEach { tag ->
                            val locale = Locale.forLanguageTag(tag)
                            SystemVoicePicker(
                                locale.getDisplayName(locale), tag, systemVoices[tag].orEmpty(),
                                context, textColor, isDark
                            )
                        }
                    }
                }
            }

            if (ttsEngine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.ui_get_a_free_api_key_at_elevenla),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                    OutlinedTextField(
                        value = elApiKey,
                        onValueChange = {
                            elApiKey = it
                            prefs.edit().putString(AnnouncementPrefs.KEY_ELEVENLABS_API_KEY, it).apply()
                            elTestStatus = ""
                        },
                        label = { Text(stringResource(R.string.ui_api_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (elKeyVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { elKeyVisible = !elKeyVisible }) {
                                Text(stringResource(if (elKeyVisible) R.string.hide_secret else R.string.show_secret),
                                    style = TextStyle(fontSize = 12.sp, color = Color(0xFF0A84FF)))
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (elApiKey.isBlank()) {
                                    elTestStatus = context.getString(R.string.enter_api_key)
                                    return@TextButton
                                }
                                elTestStatus = context.getString(R.string.connection_testing)
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) {
                                        ElevenLabsEngine.testKey(elApiKey)
                                    }
                                    elTestStatus = if (err == null) context.getString(R.string.island_connected_text) else context.getString(R.string.connection_test_error, err)
                                    if (err == null && elVoices.isEmpty()) {
                                        elVoicesLoading = true
                                        elVoices = withContext(Dispatchers.IO) {
                                            ElevenLabsEngine.fetchVoices(elApiKey)
                                        }
                                        elVoicesLoading = false
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.ui_test_connection), color = Color(0xFF0A84FF),
                                style = TextStyle(fontFamily = FontFamily(Font(R.font.sf_pro))))
                        }
                        if (elTestStatus.isNotEmpty()) {
                            Text(
                                elTestStatus,
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = if (elTestStatus.startsWith("Error") || elTestStatus.startsWith("Enter"))
                                        Color(0xFFFF453A) else Color(0xFF34C759),
                                    fontFamily = FontFamily(Font(R.font.sf_pro))
                                )
                            )
                        }
                    }

                    // Voice picker
                    Text(stringResource(R.string.ui_voice),
                        style = TextStyle(
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily(Font(R.font.sf_pro))
                        )
                    )
                    if (elVoicesLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else if (elVoices.isNotEmpty()) {
                        Box {
                            val currentName = elVoices.find { it.first == elVoiceId }?.second
                                ?: "Rachel (" + context.getString(R.string.default_option) + ")"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { elVoiceDropdownOpen = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(currentName,
                                    style = TextStyle(fontSize = 15.sp, color = textColor,
                                        fontFamily = FontFamily(Font(R.font.sf_pro))))
                                Text("▾", style = TextStyle(fontSize = 13.sp, color = textColor.copy(alpha = 0.5f)))
                            }
                            DropdownMenu(
                                expanded = elVoiceDropdownOpen,
                                onDismissRequest = { elVoiceDropdownOpen = false }
                            ) {
                                elVoices.forEach { (id, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            elVoiceId = id
                                            prefs.edit().putString(AnnouncementPrefs.KEY_ELEVENLABS_VOICE_ID, id).apply()
                                            elVoiceDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    } else if (elApiKey.isNotBlank()) {
                        Text(
                            stringResource(R.string.ui_tap_test_connection, stringResource(R.string.ui_test_connection)),
                            style = TextStyle(fontSize = 13.sp, color = textColor.copy(alpha = 0.5f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)))
                        )
                    }
                }
            }

            // Content mode
            Text(stringResource(R.string.ui_notification_content),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            StyledSelectList(items = listOf(
                SelectItem(
                    name = stringResource(R.string.ui_title_only),
                    selected = contentMode == AnnouncementPrefs.CONTENT_TITLE_ONLY,
                    onClick = {
                        contentMode = AnnouncementPrefs.CONTENT_TITLE_ONLY
                        prefs.edit().putString(AnnouncementPrefs.KEY_CONTENT_MODE, contentMode).apply()
                    }
                ),
                SelectItem(
                    name = stringResource(R.string.ui_title_and_body),
                    selected = contentMode == AnnouncementPrefs.CONTENT_TITLE_BODY,
                    onClick = {
                        contentMode = AnnouncementPrefs.CONTENT_TITLE_BODY
                        prefs.edit().putString(AnnouncementPrefs.KEY_CONTENT_MODE, contentMode).apply()
                    }
                ),
            ))

            // Language
            Text(stringResource(R.string.ui_speech_language),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            val automaticDetectionLabel = stringResource(R.string.automatic_detection)
            val systemDefaultLabel = stringResource(R.string.system_default)
            val languageOptions = buildList {
                add(AnnouncementPrefs.LANG_DETECT to automaticDetectionLabel)
                add(AnnouncementPrefs.LANG_SYSTEM to systemDefaultLabel)
                localeTags.forEach { tag ->
                    val locale = Locale.forLanguageTag(tag)
                    add(tag to locale.getDisplayName(locale))
                }
            }.distinctBy { it.first }
            StyledSelectList(items = languageOptions.map { (tag, name) ->
                SelectItem(
                    name = name,
                    selected = language == tag,
                    onClick = {
                        language = tag
                        prefs.edit().putString(AnnouncementPrefs.KEY_LANGUAGE, tag).apply()
                    }
                )
            })

            // Per-app whitelist navigation
            NavigationButton(
                to = "announcement_app_picker",
                name = stringResource(R.string.ui_choose_apps),
                description = stringResource(R.string.ui_all_apps_enabled_by_default_di),
                navController = navController,
            )

            // Quiet hours
            Text(stringResource(R.string.ui_quiet_hours),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = stringResource(R.string.ui_suppress_during_quiet_hours),
                    description = stringResource(R.string.ui_skip_announcements_during_a_ma),
                    checked = quietEnabled,
                    independent = false,
                    enabled = enabled,
                    onCheckedChange = {
                        quietEnabled = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_QUIET_ENABLED, it).apply()
                    }
                )
            }

            // Mode picker — always visible so users can see the DND option.
            // Selecting an option flips the state; the master toggle still gates
            // whether quiet hours actually fires.
            val pickerEnabled = enabled && quietEnabled
            Text(stringResource(R.string.ui_mode),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = if (pickerEnabled) 0.6f else 0.3f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            StyledSelectList(items = listOf(
                SelectItem(
                    name = stringResource(R.string.ui_manual_schedule),
                    selected = quietMode == AnnouncementPrefs.QUIET_MODE_MANUAL,
                    onClick = {
                        if (pickerEnabled) {
                            quietMode = AnnouncementPrefs.QUIET_MODE_MANUAL
                            prefs.edit().putString(AnnouncementPrefs.KEY_QUIET_MODE, quietMode).apply()
                        }
                    }
                ),
                SelectItem(
                    name = stringResource(R.string.ui_follow_system_do_not_disturb),
                    selected = quietMode == AnnouncementPrefs.QUIET_MODE_SYSTEM,
                    onClick = {
                        if (pickerEnabled) {
                            quietMode = AnnouncementPrefs.QUIET_MODE_SYSTEM
                            prefs.edit().putString(AnnouncementPrefs.KEY_QUIET_MODE, quietMode).apply()
                        }
                    }
                ),
            ))

            if (pickerEnabled && quietMode == AnnouncementPrefs.QUIET_MODE_MANUAL) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(20.dp))
                        .padding(vertical = 4.dp)
                ) {
                    TimeRow(
                        label = stringResource(R.string.ui_start_952f3),
                        minutes = quietStart,
                        enabled = true,
                        textColor = textColor,
                        onPick = { picked ->
                            quietStart = picked
                            AnnouncementPrefs.setQuietStart(context, picked)
                        },
                        showDialog = { current, onResult ->
                            TimePickerDialog(
                                context,
                                { _, h, m -> onResult(h * 60 + m) },
                                current / 60, current % 60, true
                            ).show()
                        }
                    )
                    HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                    TimeRow(
                        label = stringResource(R.string.ui_end_a2bb9),
                        minutes = quietEnd,
                        enabled = true,
                        textColor = textColor,
                        onPick = { picked ->
                            quietEnd = picked
                            AnnouncementPrefs.setQuietEnd(context, picked)
                        },
                        showDialog = { current, onResult ->
                            TimePickerDialog(
                                context,
                                { _, h, m -> onResult(h * 60 + m) },
                                current / 60, current % 60, true
                            ).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun TimeRow(
    label: String,
    minutes: Int,
    enabled: Boolean,
    textColor: Color,
    onPick: (Int) -> Unit,
    showDialog: (Int, (Int) -> Unit) -> Unit,
) {
    val rowAlpha = if (enabled) 1f else 0.4f
    val display = "%02d:%02d".format(minutes / 60, minutes % 60)
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                showDialog(minutes) { picked -> onPick(picked) }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 15.sp,
                color = textColor.copy(alpha = rowAlpha),
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
        Text(
            display,
            style = TextStyle(
                fontSize = 15.sp,
                color = textColor.copy(alpha = 0.6f * rowAlpha),
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
    }
}

@Composable
private fun SystemVoicePicker(
    label: String,
    languageTag: String,
    voices: List<TtsEngine.AvailableVoice>,
    context: android.content.Context,
    textColor: Color,
    isDark: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedVoiceName by remember(languageTag) {
        mutableStateOf(AnnouncementPrefs.systemTtsVoiceName(context, languageTag))
    }
    val selectedLabel = voices.firstOrNull { it.name == selectedVoiceName }?.label
        ?: context.getString(R.string.android_default)

    Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.6f), fontFamily = FontFamily(Font(R.font.sf_pro))))
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7), RoundedCornerShape(10.dp))
                .clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(selectedLabel, style = TextStyle(fontSize = 14.sp, color = textColor, fontFamily = FontFamily(Font(R.font.sf_pro))))
            Text("▾", style = TextStyle(fontSize = 13.sp, color = textColor.copy(alpha = 0.5f)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(context.getString(R.string.android_default)) }, onClick = {
                selectedVoiceName = null
                AnnouncementPrefs.setSystemTtsVoiceName(context, languageTag, null)
                expanded = false
            })
            voices.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.label) }, onClick = {
                    selectedVoiceName = voice.name
                    AnnouncementPrefs.setSystemTtsVoiceName(context, languageTag, voice.name)
                    expanded = false
                })
            }
        }
    }
    if (voices.isEmpty()) {
        Text(context.getString(R.string.speech_voice_unavailable))
    }
    TextButton(onClick = {
        val speechContext = AnnouncementPrefs.contextForLanguage(context, languageTag)
        TtsEngine.speak(context, speechContext.getString(R.string.speech_preview_sample), languageTag, bypassRouteGate = true)
    }, enabled = voices.isNotEmpty()) {
        Text(stringResource(R.string.ui_preview), color = Color(0xFF0A84FF), style = TextStyle(fontFamily = FontFamily(Font(R.font.sf_pro))))
    }
}

@Composable
private fun PermStatusRow(
    label: String,
    granted: Boolean,
    hint: String? = null,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black
    val statusColor = if (granted) Color(0xFF34C759) else Color(0xFFFF9500)
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, color = textColor, fontFamily = FontFamily(Font(R.font.sf_pro)))
        )
        Text(
            text = stringResource(if (granted) R.string.permission_granted else R.string.permission_tap_to_grant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            style = TextStyle(
                fontSize = 13.sp,
                color = statusColor,
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
        if (!granted && hint != null) {
            Text(
                text = hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                style = TextStyle(
                    fontSize = 12.sp,
                    color = textColor.copy(alpha = 0.55f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                )
            )
        }
        if (!granted) {
            // Make the row clickable by overlaying a tap target.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.dp)
                    .padding(top = 2.dp)
            )
            androidx.compose.material3.TextButton(onClick = onClick) {
                Text(stringResource(R.string.ui_grant), color = statusColor)
            }
        }
    }
}
