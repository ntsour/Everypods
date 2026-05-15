/*
    ProPods - AirPods liberated from Apple's ecosystem
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

package io.nikos.propods.presentation.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.nikos.propods.R
import io.nikos.propods.presentation.components.NavigationButton
import io.nikos.propods.presentation.components.SelectItem
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.components.StyledSelectList
import io.nikos.propods.presentation.components.StyledToggle
import io.nikos.propods.services.NotificationAnnouncementService
import io.nikos.propods.utils.AnnouncementPrefs
import io.nikos.propods.utils.ElevenLabsEngine

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
    var quietEnabled by remember { mutableStateOf(AnnouncementPrefs.quietHoursEnabled(context)) }
    var quietMode by remember { mutableStateOf(AnnouncementPrefs.quietMode(context)) }
    var quietStart by remember { mutableStateOf(AnnouncementPrefs.quietStart(context)) }
    var quietEnd by remember { mutableStateOf(AnnouncementPrefs.quietEnd(context)) }
    var contentMode by remember { mutableStateOf(prefs.getString(AnnouncementPrefs.KEY_CONTENT_MODE, AnnouncementPrefs.CONTENT_TITLE_BODY) ?: AnnouncementPrefs.CONTENT_TITLE_BODY) }
    var language by remember { mutableStateOf(prefs.getString(AnnouncementPrefs.KEY_LANGUAGE, AnnouncementPrefs.LANG_AUTO) ?: AnnouncementPrefs.LANG_AUTO) }

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

    // Load voices when ElevenLabs is selected and key is present
    LaunchedEffect(ttsEngine, elApiKey) {
        if (ttsEngine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS && elApiKey.isNotBlank()) {
            elVoicesLoading = true
            elVoices = withContext(Dispatchers.IO) { ElevenLabsEngine.fetchVoices(elApiKey) }
            elVoicesLoading = false
        }
    }

    fun isGranted(perm: String) = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    var notifAccess       by remember { mutableStateOf(NotificationAnnouncementService.isAccessGranted(context)) }
    var contactsGranted   by remember { mutableStateOf(isGranted(Manifest.permission.READ_CONTACTS)) }
    var callLogGranted    by remember { mutableStateOf(isGranted(Manifest.permission.READ_CALL_LOG)) }
    var phoneStateGranted by remember { mutableStateOf(isGranted(Manifest.permission.READ_PHONE_STATE)) }

    // Poll all permission states every second (user may grant in system settings)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            notifAccess       = NotificationAnnouncementService.isAccessGranted(context)
            contactsGranted   = isGranted(Manifest.permission.READ_CONTACTS)
            callLogGranted    = isGranted(Manifest.permission.READ_CALL_LOG)
            phoneStateGranted = isGranted(Manifest.permission.READ_PHONE_STATE)
        }
    }

    fun openAppSettings() {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    val multiPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Refresh all — the polling will also catch it
        contactsGranted   = isGranted(Manifest.permission.READ_CONTACTS)
        callLogGranted    = isGranted(Manifest.permission.READ_CALL_LOG)
        phoneStateGranted = isGranted(Manifest.permission.READ_PHONE_STATE)
    }

    // Track if we tried the launcher dialog already in THIS screen session
    val triedLauncher = remember { mutableSetOf<String>() }

    // Grant permission: try system dialog first, then app settings as fallback
    fun grantPermission(perm: String) {
        if (isGranted(perm)) return
        if (perm !in triedLauncher) {
            // First attempt in this session — always try the system dialog
            triedLauncher.add(perm)
            multiPermLauncher.launch(arrayOf(perm))
        } else {
            // Already tried the dialog and it didn't work — open app settings permissions page
            openAppSettings()
        }
    }

    StyledScaffold(title = "Notification Announcements") { topPadding, _, bottomPadding ->
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
                    text = "Permissions",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
                PermStatusRow(
                    label = "Notification access",
                    granted = notifAccess,
                    hint = if (!notifAccess)
                        "Enable \"ProPods Notification Announcements\" in the list (ProPods has two entries — pick this one, not Teams Mute Sync)."
                    else null,
                    onClick = { NotificationAnnouncementService.openAccessSettings(context) }
                )
                PermStatusRow("Contacts (caller name lookup)", contactsGranted) {
                    grantPermission(Manifest.permission.READ_CONTACTS)
                }
                PermStatusRow("Call log (incoming caller number)", callLogGranted) {
                    grantPermission(Manifest.permission.READ_CALL_LOG)
                }
                PermStatusRow("Phone state (call detection)", phoneStateGranted) {
                    grantPermission(Manifest.permission.READ_PHONE_STATE)
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
                    label = "Announce notifications",
                    description = "Read incoming notifications and callers aloud through the AirPods.",
                    checked = enabled,
                    independent = false,
                    onCheckedChange = {
                        enabled = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_ENABLED, it).apply()
                    }
                )
                HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                StyledToggle(
                    label = "Skip during a call",
                    description = "Don't announce while a call is in progress.",
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
                    label = "Skip during active media",
                    description = "Don't announce while music or other media is playing.",
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
                    label = "Only when AirPods are in ear",
                    description = "Suppress announcements unless at least one AirPod is detected in your ear.",
                    checked = onlyInEar,
                    independent = false,
                    enabled = enabled,
                    onCheckedChange = {
                        onlyInEar = it
                        prefs.edit().putBoolean(AnnouncementPrefs.KEY_ONLY_IN_EAR, it).apply()
                    }
                )
            }

            // TTS Engine picker
            Text(
                "Voice engine",
                style = TextStyle(
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            StyledSelectList(items = listOf(
                SelectItem(
                    name = "System TTS",
                    selected = ttsEngine == AnnouncementPrefs.TTS_ENGINE_SYSTEM,
                    onClick = {
                        ttsEngine = AnnouncementPrefs.TTS_ENGINE_SYSTEM
                        prefs.edit().putString(AnnouncementPrefs.KEY_TTS_ENGINE, ttsEngine).apply()
                    }
                ),
                SelectItem(
                    name = "ElevenLabs (natural voice)",
                    selected = ttsEngine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS,
                    onClick = {
                        ttsEngine = AnnouncementPrefs.TTS_ENGINE_ELEVENLABS
                        prefs.edit().putString(AnnouncementPrefs.KEY_TTS_ENGINE, ttsEngine).apply()
                    }
                ),
            ))

            if (ttsEngine == AnnouncementPrefs.TTS_ENGINE_ELEVENLABS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Get a free API key at elevenlabs.io → Log in → Profile (top-right) → API Keys → Create API Key. The free plan includes 10,000 characters/month.",
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
                        label = { Text("API key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (elKeyVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { elKeyVisible = !elKeyVisible }) {
                                Text(if (elKeyVisible) "Hide" else "Show",
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
                                    elTestStatus = "Enter an API key first"
                                    return@TextButton
                                }
                                elTestStatus = "Testing…"
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) {
                                        ElevenLabsEngine.testKey(elApiKey)
                                    }
                                    elTestStatus = if (err == null) "Connected ✓" else "Error: $err"
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
                            Text("Test connection", color = Color(0xFF0A84FF),
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
                    Text(
                        "Voice",
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
                                ?: "Rachel (default)"
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
                            "Tap \"Test connection\" to load your available voices.",
                            style = TextStyle(fontSize = 13.sp, color = textColor.copy(alpha = 0.5f),
                                fontFamily = FontFamily(Font(R.font.sf_pro)))
                        )
                    }
                }
            }

            // Content mode
            Text(
                "Notification content",
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
                    name = "Title only",
                    selected = contentMode == AnnouncementPrefs.CONTENT_TITLE_ONLY,
                    onClick = {
                        contentMode = AnnouncementPrefs.CONTENT_TITLE_ONLY
                        prefs.edit().putString(AnnouncementPrefs.KEY_CONTENT_MODE, contentMode).apply()
                    }
                ),
                SelectItem(
                    name = "Title and body",
                    selected = contentMode == AnnouncementPrefs.CONTENT_TITLE_BODY,
                    onClick = {
                        contentMode = AnnouncementPrefs.CONTENT_TITLE_BODY
                        prefs.edit().putString(AnnouncementPrefs.KEY_CONTENT_MODE, contentMode).apply()
                    }
                ),
            ))

            // Language
            Text(
                "Speech language",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            StyledSelectList(items = AnnouncementPrefs.SUPPORTED_LANGUAGES.map { (tag, name) ->
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
                name = "Choose apps",
                description = "All apps enabled by default. Disable specific apps you don't want announced.",
                navController = navController,
            )

            // Quiet hours
            Text(
                "Quiet hours",
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
                    label = "Suppress during quiet hours",
                    description = "Skip announcements during a manual schedule, or follow the system Do Not Disturb.",
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
            Text(
                "Mode",
                style = TextStyle(
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = if (pickerEnabled) 0.6f else 0.3f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            StyledSelectList(items = listOf(
                SelectItem(
                    name = "Manual schedule",
                    selected = quietMode == AnnouncementPrefs.QUIET_MODE_MANUAL,
                    onClick = {
                        if (pickerEnabled) {
                            quietMode = AnnouncementPrefs.QUIET_MODE_MANUAL
                            prefs.edit().putString(AnnouncementPrefs.KEY_QUIET_MODE, quietMode).apply()
                        }
                    }
                ),
                SelectItem(
                    name = "Follow system Do Not Disturb",
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
                        label = "Start",
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
                        label = "End",
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
            text = if (granted) "Granted" else "Tap to grant",
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
                Text("Grant", color = statusColor)
            }
        }
    }
}
