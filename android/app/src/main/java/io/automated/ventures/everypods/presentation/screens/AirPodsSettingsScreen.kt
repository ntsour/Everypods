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

@file:OptIn(ExperimentalEncodingApi::class)

package io.automated.ventures.everypods.presentation.screens

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
import android.util.Log

import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.AACPManager
import io.automated.ventures.everypods.data.AirPodsPro3
import io.automated.ventures.everypods.data.Capability
import io.automated.ventures.everypods.data.StemAction
import io.automated.ventures.everypods.presentation.components.AboutCard
import io.automated.ventures.everypods.presentation.components.AppInfoCard
import io.automated.ventures.everypods.presentation.components.AudioSettings
import io.automated.ventures.everypods.presentation.components.BatteryView
import io.automated.ventures.everypods.presentation.components.CallControlSettings
import io.automated.ventures.everypods.presentation.components.CrossDevicePeersInline

import io.automated.ventures.everypods.presentation.components.DeviceInfoCard
import io.automated.ventures.everypods.presentation.components.MicrophoneSettings
import io.automated.ventures.everypods.presentation.components.NavigationButton
import io.automated.ventures.everypods.presentation.components.NoiseControlSettings
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledBottomSheet
import io.automated.ventures.everypods.presentation.components.StyledButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import io.automated.ventures.everypods.presentation.components.StyledIconButton
import io.automated.ventures.everypods.presentation.components.StyledInputField
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.components.StyledSlider
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.presentation.viewmodel.AppSettingsViewModel
import io.automated.ventures.everypods.utils.SleepTimer
import io.automated.ventures.everypods.utils.SmartFeaturesPrefs
import io.automated.ventures.everypods.utils.openEveryPodsIssues
import io.automated.ventures.everypods.utils.openEveryPodsSupportEmail
import kotlin.io.encoding.ExperimentalEncodingApi
import java.text.Normalizer
import java.util.Locale

// ─── Design tokens ───────────────────────────────────────────────────────────
internal const val DisabledAlpha = 0.45f
internal val SfPro get()         = FontFamily(Font(R.font.sf_pro))

// ─── Searchable menu item ─────────────────────────────────────────────────────
data class SearchableItem(
    val label: String,
    val category: String,
    val categoryKey: String,
    val directRoute: String,
    val anchor: String? = null,
    val keywords: List<String> = emptyList(),
)

internal fun searchIndex(context: Context): List<SearchableItem> {
    val categoryLabels = mapOf(
        "controls" to context.getString(R.string.search_category_controls),
        "connection" to context.getString(R.string.design_connection),
        "smart" to context.getString(R.string.search_category_smart),
        "appsettings" to context.getString(R.string.search_category_app),
        "audio" to context.getString(R.string.search_category_audio),
        "help" to context.getString(R.string.search_category_help),
    )
    val categoryAliases = mapOf(
        "controls" to listOf("AirPods Controls", "Controles de AirPods"),
        "settings" to listOf("AirPods Settings", "Ajustes de AirPods"),
        "smart" to listOf("Smart Features", "Funciones inteligentes"),
        "appsettings" to listOf("App Settings", "Ajustes de la aplicación"),
        "audio" to listOf("Audio & Connection", "Audio y conexión"),
        "help" to listOf("Help & Troubleshooting", "Ayuda", "Soporte"),
    )
    val englishConfiguration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    val englishItems = buildSearchIndex(context.createConfigurationContext(englishConfiguration))
    val indexed = buildSearchIndex(context).mapIndexed { index, item ->
        val route = canonicalSettingsRoute(item.directRoute)
        val key = if (route.startsWith("category/")) route.substringAfter("category/").substringBefore('?')
            else when (route.substringBefore('?')) {
                "rename", "paired_devices" -> "connection"
                else -> if (item.categoryKey == "settings") "audio" else item.categoryKey
            }
        item.copy(directRoute = route, categoryKey = key,
            category = categoryLabels[key] ?: item.category,
            keywords = (item.keywords + categoryAliases[item.categoryKey].orEmpty() + englishItems[index].label +
                englishItems[index].keywords).distinct())
    }
    return indexed + SearchableItem(
            context.getString(R.string.audio_relay_playback),
            context.getString(R.string.design_connection),
            "connection",
            "category/connection?anchor=connections",
            keywords = listOf("wifi audio", "play through", "relay", "handover"),
        )
}

/** Comma-separated, translated search keywords from a string resource (locale system). */
internal fun gymKeywords(context: Context, res: Int): List<String> =
    context.getString(res).split(',', '，', '、').map(String::trim).filter(String::isNotEmpty)

/**
 * Gym automation sub-settings (locations list, add/edit/remove, radius, map). Labels and
 * keywords are string resources in every locale; the index also merges the English build.
 */
internal fun gymAutomationSearchItems(context: Context): List<SearchableItem> {
    val base = gymKeywords(context, R.string.gym_auto_search_keywords)
    fun item(label: Int, vararg keywords: Int) = SearchableItem(
        context.getString(label), "Smart Features", "smart", "gym_automation",
        keywords = base + keywords.flatMap { gymKeywords(context, it) } + context.getString(R.string.gym_auto_title),
    )
    return listOf(
        item(R.string.gym_zones_title, R.string.gym_zones_search_keywords),
        item(R.string.gym_zone_add, R.string.gym_zone_edit_search_keywords),
        item(R.string.gym_zone_edit, R.string.gym_zone_edit_search_keywords, R.string.gym_map_search_keywords),
        item(R.string.gym_zone_remove, R.string.gym_zone_edit_search_keywords),
        item(R.string.gym_auto_radius_title, R.string.gym_auto_radius_search_keywords),
        item(R.string.gym_map_search_title, R.string.gym_map_search_keywords),
    )
}

private fun buildSearchIndex(context: Context): List<SearchableItem> = listOf(
    // AirPods Controls
    SearchableItem("${context.getString(R.string.ui_left_bud)} — ${context.getString(R.string.ui_single_press)}",  "AirPods Controls", "controls", "press_actions?bud=left&gesture=SINGLE_PRESS", keywords = listOf("stem", "tap", "click", "izquierdo", "pulsación sencilla")),
    SearchableItem("${context.getString(R.string.ui_left_bud)} — ${context.getString(R.string.ui_double_press)}",  "AirPods Controls", "controls", "press_actions?bud=left&gesture=DOUBLE_PRESS", keywords = listOf("stem", "tap", "click", "izquierdo", "doble pulsación")),
    SearchableItem("${context.getString(R.string.ui_left_bud)} — ${context.getString(R.string.ui_triple_press)}",  "AirPods Controls", "controls", "press_actions?bud=left&gesture=TRIPLE_PRESS", keywords = listOf("stem", "tap", "click", "izquierdo", "triple pulsación")),
    SearchableItem("${context.getString(R.string.ui_left_bud)} — ${context.getString(R.string.ui_long_press)}",    "AirPods Controls", "controls", "press_actions?bud=left&gesture=LONG_PRESS", keywords = listOf("stem", "hold", "press and hold", "izquierdo", "mantener pulsado")),
    SearchableItem("${context.getString(R.string.ui_right_bud)} — ${context.getString(R.string.ui_single_press)}", "AirPods Controls", "controls", "press_actions?bud=right&gesture=SINGLE_PRESS", keywords = listOf("stem", "tap", "click", "derecho", "pulsación sencilla")),
    SearchableItem("${context.getString(R.string.ui_right_bud)} — ${context.getString(R.string.ui_double_press)}", "AirPods Controls", "controls", "press_actions?bud=right&gesture=DOUBLE_PRESS", keywords = listOf("stem", "tap", "click", "derecho", "doble pulsación")),
    SearchableItem("${context.getString(R.string.ui_right_bud)} — ${context.getString(R.string.ui_triple_press)}", "AirPods Controls", "controls", "press_actions?bud=right&gesture=TRIPLE_PRESS", keywords = listOf("stem", "tap", "click", "derecho", "triple pulsación")),
    SearchableItem("${context.getString(R.string.ui_right_bud)} — ${context.getString(R.string.ui_long_press)}",   "AirPods Controls", "controls", "press_actions?bud=right&gesture=LONG_PRESS", keywords = listOf("stem", "hold", "press and hold", "derecho", "mantener pulsado")),
    SearchableItem(context.getString(R.string.ui_listening_mode_configuration), "AirPods Controls", "controls", "category/controls?anchor=listening_modes", keywords = listOf("anc", "noise cancellation", "transparency", "adaptive", "off", "modo de escucha")),
    SearchableItem(context.getString(R.string.hg_call_actions),            "AirPods Controls", "controls", "call_controls", keywords = listOf("answer", "end call", "mute", "llamadas")),
    SearchableItem(context.getString(R.string.ui_volume_control),           "AirPods Controls", "controls", "category/controls?anchor=controls_configuration", keywords = listOf("swipe", "touch", "control de volumen")),
    SearchableItem("Controls Configuration",   "AirPods Controls", "controls", "category/controls?anchor=controls_configuration", keywords = listOf("press speed", "hold duration", "tone", "configuración de controles")),
    SearchableItem(context.getString(R.string.quick_settings_gym_mode),                 "Smart Features", "smart", "category/smart", anchor = "gym_mode", keywords = listOf("workout", "fitness", "stem", "timer")),
    SearchableItem(context.getString(R.string.hg_gym_controls),        "AirPods Controls", "controls", "gym_press_actions", keywords = listOf("workout", "fitness", "stem")),
    // AirPods Settings
    SearchableItem(context.getString(R.string.ui_device_name), "AirPods Settings", "settings", "rename", keywords = listOf("rename", "bluetooth name", "nombre del dispositivo")),
    SearchableItem(context.getString(R.string.hearing_protection),       "AirPods Settings", "settings", "hearing_protection", keywords = listOf("ppe", "loud sound")),
    SearchableItem(context.getString(R.string.conversational_awareness),   "AirPods Settings", "settings", "conversation_awareness", keywords = listOf("speak", "talk", "auto pause", "auto volume", "detección de conversación", "volumen de conversación")),
    SearchableItem(context.getString(R.string.ui_disconnect),               "AirPods Settings", "settings", "bluetooth_control", keywords = listOf("bluetooth", "turn off")),
    // Smart Features
    SearchableItem(context.getString(R.string.ui_notification_announcements), "Smart Features", "smart", "notification_announcements", keywords = listOf("tts", "read", "speak", "siri")),
    SearchableItem(context.getString(R.string.ui_find_my_case), "Smart Features", "smart", "proximity_finder", keywords = listOf("Find My Case", "Find my Case", "locate", "nearby", "radar", "buscar mi estuche", "encontrar")),
    SearchableItem(context.getString(R.string.ui_head_gestures),            "AirPods Controls", "controls", "category/controls", anchor = "head_gestures", keywords = listOf("nod", "shake", "answer call", "head gestures", "head gesture actions", "gestos de cabeza", "gestos con la cabeza")),
    SearchableItem(context.getString(R.string.hg_stop_announcements), "AirPods Controls", "controls", "category/controls", anchor = "head_gestures", keywords = listOf("shake stop speech", "stop spoken notification", "cancel announcement", "detener anuncios")),
    SearchableItem(context.getString(R.string.hg_configuration), "AirPods Controls", "controls", "head_tracking", keywords = listOf("head gesture configuration", "sensitivity", "accidental gestures")),
    SearchableItem(context.getString(R.string.hg_amplitude), "AirPods Controls", "controls", "head_tracking?anchor=nod_amplitude", keywords = listOf("nod amplitude", "shake amplitude", "sensitivity", "amplitud")),
    SearchableItem(context.getString(R.string.hg_repetitions), "AirPods Controls", "controls", "head_tracking?anchor=nod_repetitions", keywords = listOf("nod repetitions", "shake repetitions", "repeticiones")),
    SearchableItem(context.getString(R.string.hg_cooldown), "AirPods Controls", "controls", "head_tracking?anchor=nod_cooldown", keywords = listOf("gesture cooldown", "delay", "espera")),
    SearchableItem(context.getString(R.string.hg_gym_tuning), "AirPods Controls", "controls", "head_tracking?gym=true&anchor=gym_gesture_tuning", keywords = listOf("gym gesture tuning", "gym override", "exercise sensitivity")),
    SearchableItem(context.getString(R.string.hrs_title), "Smart Features", "smart", "category/smart?anchor=heart_rate", keywords = listOf("heart rate", "heart rate monitor", "bpm", "wahoo", "tickr", "DIRCON", "Wi-Fi heart rate", "Incyclist", "Zwift", "receiver", "sensor cardíaco", "frecuencia cardíaca", "ritmo cardíaco")),
    SearchableItem(context.getString(R.string.adaptive_audio),           "Smart Features", "smart", "adaptive_audio", keywords = listOf("anc strength", "noise control")),
    SearchableItem(context.getString(R.string.ui_sleep_detection),          "Smart Features", "smart", "category/smart", anchor = "automation", keywords = listOf("auto sleep", "bedtime", "detección de sueño")),
    SearchableItem(context.getString(R.string.ui_optimized_charging),       "Smart Features", "smart", "category/smart", anchor = "automation", keywords = listOf("battery health", "slow charge", "carga optimizada")),
    SearchableItem(context.getString(R.string.ui_resume_media_after_call),  "Smart Features", "smart", "category/smart", anchor = "automation", keywords = listOf("auto resume", "playback", "reanudar audio tras llamada")),
    SearchableItem("Battery Alerts",           "Smart Features", "smart", "category/smart?anchor=battery_alert_threshold", keywords = listOf("low battery", "speak", "announce", "alerta de batería")),
    SearchableItem(context.getString(R.string.ui_sleep_timer),              "Smart Features", "smart", "category/smart", anchor = "sleep_timer", keywords = listOf("auto stop", "timer")),
    SearchableItem(context.getString(R.string.ui_gym_timer),                "Smart Features", "smart", "gym_timer", keywords = listOf("stopwatch", "workout", "fitness")),
    SearchableItem(context.getString(R.string.gym_auto_title),              "Smart Features", "smart", "gym_automation", keywords = listOf("location", "geofence", "arrive", "gym", "ubicación", "gimnasio", "τοποθεσία", "γυμναστήριο") + gymKeywords(context, R.string.gym_auto_search_keywords)),
    *gymAutomationSearchItems(context).toTypedArray(),
    SearchableItem("Battery Alert Threshold",  "Smart Features", "smart", "category/smart?anchor=battery_alert_threshold", keywords = listOf("battery threshold", "low battery percent", "umbral de batería", "umbral de batería baja")),
    SearchableItem("Sleep Timer Duration",     "Smart Features", "smart", "category/smart", anchor = "sleep_timer", keywords = listOf("sleep timer duration", "sleep countdown", "temporizador de sueño")),
    // App Settings
    SearchableItem(context.getString(R.string.ui_battery_in_widget),        "App Settings", "appsettings", "phone_battery", keywords = listOf("widget")),
    SearchableItem(context.getString(R.string.ui_pop_up_animations),        "App Settings", "appsettings", "popup_animations", keywords = listOf("animation", "connect")),
    SearchableItem(context.getString(R.string.show_bottom_sheet_popup),       "App Settings", "appsettings", "popup_animations", keywords = listOf("animation", "connect")),
    SearchableItem(context.getString(R.string.show_island_popup),     "App Settings", "appsettings", "popup_animations", keywords = listOf("animation", "island", "connect")),
    SearchableItem(context.getString(R.string.ui_permissions_d06d5),              "App Settings", "appsettings", "permissions", keywords = listOf("access", "grant")),
    SearchableItem(context.getString(R.string.app_language), "App Settings", "appsettings", "category/appsettings?anchor=app_language", keywords = listOf("App language", "language", "idioma", "idioma de la aplicación", "locale", "system language")),
    SearchableItem("Background Activity",      "App Settings", "appsettings", "category/appsettings?anchor=background_activity", keywords = listOf("battery", "optimization", "unrestricted", "background", "actividad en segundo plano")),
    SearchableItem("Connect When Receiving a Call","Audio & Connection","audio", "category/audio?anchor=takeover_ringing", keywords = listOf("takeover", "auto connect", "ringing", "incoming call", "al recibir una llamada")),
    SearchableItem("Connect When Starting Media","Audio & Connection","audio", "category/audio?anchor=takeover_media", keywords = listOf("takeover", "auto connect", "media starts", "playback", "al iniciar multimedia")),
    SearchableItem(context.getString(R.string.lid_open_last_holder_autoconnect), "Audio & Connection", "audio", "category/connection?anchor=lid_open_autoconnect", keywords = listOf("last device", "last phone", "last holder", "open case", "case lid", "reconnect", "último dispositivo", "último teléfono", "abrir estuche")),
    // Audio & Connection
    SearchableItem(context.getString(R.string.ui_audio_settings), "Audio & Connection", "audio", "category/audio?anchor=audio_controls", keywords = listOf("Audio Settings", "eq", "equalizer", "adaptive volume", "ajustes de audio", "configuración de audio")),
    SearchableItem(context.getString(R.string.personalized_volume),      "Audio & Connection", "audio", "category/audio?anchor=personalized_volume", keywords = listOf("adaptive volume", "volume adjust", "environment", "volumen personalizado")),
    SearchableItem(context.getString(R.string.equalizer), "Audio & Connection", "audio", "equalizer_screen", keywords = listOf("Equalizer", "eq", "ecualizador")),
    SearchableItem(context.getString(R.string.cross_device_handover),"Audio & Connection", "audio", "category/audio?anchor=handover", keywords = listOf("cross device", "auto switch", "handover", "other device", "traspaso")),
    SearchableItem(context.getString(R.string.ui_peer_android_device),      "Audio & Connection", "audio", "category/connection?anchor=connections", keywords = listOf("peer", "other device", "android", "handover", "cross device", "dispositivo android")),
    SearchableItem("Ear Detection",            "Audio & Connection", "audio", "category/audio?anchor=ear_detection", keywords = listOf("auto pause", "in ear", "wear detection", "detección de oído")),
    SearchableItem("Disconnect When Not Wearing","Audio & Connection","audio", "category/audio?anchor=disconnect_when_not_wearing", keywords = listOf("auto disconnect", "wear detection", "desconectar al quitar")),
    SearchableItem(context.getString(R.string.ui_microphone_settings),      "Audio & Connection", "audio", "microphone_settings", keywords = listOf("mic", "microphone mode")),
    SearchableItem(context.getString(R.string.ui_connection_settings),      "Audio & Connection", "audio", "connection_settings", keywords = listOf("auto connect", "bluetooth", "conexión")),
    // Help
    SearchableItem(context.getString(R.string.open_source_licenses),     "Help & Troubleshooting", "help", "open_source_licenses", keywords = listOf("license", "credits")),
    SearchableItem(context.getString(R.string.ui_device_protocol_versions), "Help & Troubleshooting", "help", "version_info", keywords = listOf("protocol version", "firmware version", "about airpods")),
    SearchableItem(context.getString(R.string.ui_app_version), "Help & Troubleshooting", "help", "category/help?anchor=app_version", keywords = listOf("about app", "versión de la aplicación")),
    SearchableItem(context.getString(R.string.buy_price),       "Help & Troubleshooting", "help", "purchase_screen", keywords = listOf("tip", "donate", "support", "coffee")),
    SearchableItem(context.getString(R.string.ui_email_support),            "Help & Troubleshooting", "help", "email_support", keywords = listOf("contact")),
    SearchableItem(context.getString(R.string.ui_github_issues),            "Help & Troubleshooting", "help", "github_issues", keywords = listOf("bug", "report")),
    SearchableItem(context.getString(R.string.ui_speech_language), "Smart Features", "smart", "notification_announcements?target=speech_language", keywords = listOf("Speech language", "voice language", "idioma de voz", "idioma hablado")),
    SearchableItem(context.getString(R.string.ui_quiet_hours), "Smart Features", "smart", "notification_announcements?target=quiet_hours", keywords = listOf("Quiet hours", "do not disturb", "horas tranquilas", "no molestar")),
    SearchableItem(context.getString(R.string.ui_battery_in_widget), "App Settings", "appsettings", "phone_battery", keywords = listOf("phone battery", "widget battery", "batería del teléfono")),
    SearchableItem(context.getString(R.string.show_bottom_sheet_popup), "App Settings", "appsettings", "popup_animations", keywords = listOf("bottom sheet popup", "bottom popup", "ventana emergente inferior")),
    SearchableItem(context.getString(R.string.show_island_popup), "App Settings", "appsettings", "popup_animations", keywords = listOf("dynamic island popup", "island popup", "animación isla dinámica")),
    SearchableItem(context.getString(R.string.ui_permissions), "App Settings", "appsettings", "permissions", keywords = listOf("permissions", "special access", "permisos")),
    SearchableItem(context.getString(R.string.press_speed), "AirPods Controls", "controls", "category/controls?anchor=press_speed", keywords = listOf("double click interval", "press speed", "velocidad de pulsación")),
    SearchableItem(context.getString(R.string.press_and_hold_duration), "AirPods Controls", "controls", "category/controls?anchor=hold_duration", keywords = listOf("hold duration", "long press speed", "duración de mantener pulsado")),
    SearchableItem(context.getString(R.string.noise_cancellation_single_airpod), "AirPods Controls", "controls", "category/controls?anchor=listening_controls", keywords = listOf("one bud anc", "noise cancellation one airpod", "cancelación con un auricular")),
    SearchableItem(context.getString(R.string.tone_volume), "AirPods Controls", "controls", "category/controls?anchor=listening_controls", keywords = listOf("chime volume", "tone", "volume tone", "volumen de tono")),
    SearchableItem(context.getString(R.string.ui_volume_swipe_speed), "AirPods Controls", "controls", "category/controls?anchor=volume_swipe_speed", keywords = listOf("volume swipe", "swipe speed", "velocidad de deslizamiento")),
    SearchableItem("Answer Call Gesture", "AirPods Controls", "controls", "call_controls", keywords = listOf("answer call", "accept call", "responder llamada")),
    SearchableItem("End Call Gesture", "AirPods Controls", "controls", "call_controls", keywords = listOf("hang up", "end call", "colgar llamada")),
    SearchableItem("Mute Call Gesture", "AirPods Controls", "controls", "call_controls", keywords = listOf("mute", "unmute", "silence call", "silenciar llamada")),
    SearchableItem("Conversation Pause Music", "AirPods Settings", "settings", "conversation_awareness", keywords = listOf("pause music when speaking", "conversation pause", "pausar música al hablar")),
    SearchableItem("Conversation Target Volume", "AirPods Settings", "settings", "conversation_awareness", keywords = listOf("conversation relative volume", "volume while speaking", "volumen durante conversación")),
    SearchableItem("Hearing Protection Options", "AirPods Settings", "settings", "hearing_protection", keywords = listOf("hearing protection", "loud sound reduction", "protección auditiva")),
    SearchableItem("Bluetooth Disconnect", "AirPods Settings", "settings", "bluetooth_control", keywords = listOf("disconnect bluetooth", "bluetooth control", "desconectar bluetooth")),
    SearchableItem(context.getString(R.string.ui_notification_access_4dca8), "Smart Features", "smart", "notification_announcements", keywords = listOf("notification permission", "notification access", "acceso a notificaciones")),
    SearchableItem(context.getString(R.string.ui_announce_notifications), "Smart Features", "smart", "notification_announcements", keywords = listOf("read notifications", "announce messages", "anunciar notificaciones")),
    SearchableItem("Skip Announcements During Calls", "Smart Features", "smart", "notification_announcements", keywords = listOf("skip during call", "phone call announcements", "omitir durante llamada")),
    SearchableItem("Skip Announcements During Media", "Smart Features", "smart", "notification_announcements", keywords = listOf("skip during music", "active media", "omitir durante música")),
    SearchableItem("Announce Only In Ear", "Smart Features", "smart", "notification_announcements", keywords = listOf("in ear announcements", "wearing airpods", "solo con auriculares puestos")),
    SearchableItem("Announcement Voice Engine", "Smart Features", "smart", "notification_announcements", keywords = listOf("text to speech", "tts engine", "elevenlabs", "motor de voz")),
    SearchableItem("Announcement Voice Selection", "Smart Features", "smart", "notification_announcements", keywords = listOf("system voices", "select voice", "voice preview", "voz de anuncios")),
    SearchableItem("Announcement API Key", "Smart Features", "smart", "notification_announcements", keywords = listOf("elevenlabs api key", "voice api key", "clave api voz")),
    SearchableItem("Announcement Content", "Smart Features", "smart", "notification_announcements", keywords = listOf("notification title body", "notification content", "contenido de notificación")),
    SearchableItem("Choose Announced Apps", "Smart Features", "smart", "announcement_app_picker", keywords = listOf("per app announcements", "app whitelist", "choose apps", "elegir aplicaciones")),
    SearchableItem("Quiet Hours Schedule", "Smart Features", "smart", "notification_announcements?target=quiet_hours", keywords = listOf("quiet schedule", "quiet start", "quiet end", "horario silencioso")),
    SearchableItem("Finder", "Smart Features", "smart", "proximity_finder", keywords = listOf("find case", "locate airpods", "finder", "localizador")),
    SearchableItem("Adaptive Audio Strength", "Smart Features", "smart", "adaptive_audio", keywords = listOf("adaptive noise strength", "anc strength", "intensidad de audio adaptativo")),
    SearchableItem("Gym Timer Configuration", "Smart Features", "smart", "gym_timer", keywords = listOf("gym timer duration", "countdown timer", "hiit", "temporizador gym")),
    SearchableItem("Gym Timer Announcements", "Smart Features", "smart", "gym_timer", keywords = listOf("timer voice", "gym speech", "anuncios del temporizador")),
    SearchableItem("${context.getString(R.string.hg_gym_controls)} — ${context.getString(R.string.ui_head_gestures)}", "AirPods Controls", "controls", "gym_press_actions", keywords = listOf("gym head gestures", "gym nod", "gym shake", "acciones de gimnasio")),
    SearchableItem("Add or Remove Paired Device", "Audio & Connection", "audio", "category/connection?anchor=connections", keywords = listOf("add peer", "remove peer", "connected devices", "añadir dispositivo")),
    SearchableItem("Case Open Auto Connect", "Audio & Connection", "audio", "category/audio?anchor=lid_open_autoconnect", keywords = listOf("open case connect", "last device", "case open", "conectar al abrir estuche")),
    SearchableItem("Selected Handover Peer", "Audio & Connection", "audio", "category/connection?anchor=connections", keywords = listOf("selected device", "peer device", "dispositivo seleccionado")),
)

private fun normalizeSearchText(value: String): String = Normalizer
    .normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")
    .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
    .trim()

internal fun rankSearchResults(items: List<SearchableItem>, query: String): List<SearchableItem> {
    val normalizedQuery = normalizeSearchText(query)
    if (normalizedQuery.isBlank()) return emptyList()
    val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank)
    return items.mapNotNull { item ->
        val title = normalizeSearchText(item.label)
        val titleTokens = title.split(' ')
        val searchableText = listOf(title, normalizeSearchText(item.category)) + item.keywords.map(::normalizeSearchText)
        val score = when {
            title == normalizedQuery -> 1000
            title.startsWith(normalizedQuery) -> 800
            queryTokens.all { token -> titleTokens.any { it.startsWith(token) } } -> 650
            searchableText.any { it.contains(normalizedQuery) } -> 450
            queryTokens.all { token -> searchableText.any { token in it.split(' ') } } -> 300
            else -> return@mapNotNull null
        }
        item to score
    }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.label to it.directRoute }
}

@Composable internal fun bodyStyle(dark: Boolean) = TextStyle(
    fontSize = 16.sp, fontFamily = SfPro,
    color = if (dark) Color.White else Color.Black
)
@Composable internal fun captionStyle(dark: Boolean) = TextStyle(
    fontSize = 13.sp, fontFamily = SfPro,
    color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)
)

// ─── Shared primitives ───────────────────────────────────────────────────────

@Composable
internal fun MenuSectionHeader(label: String, dark: Boolean, requiresAacp: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        io.automated.ventures.everypods.presentation.components.SettingsHeading(label, Modifier.weight(1f))
        if (requiresAacp) io.automated.ventures.everypods.presentation.components.RequiresAacpIcon()
    }
}

@Composable
internal fun MenuDivider() = HorizontalDivider(
    color = Color(0x30888888), thickness = 0.5.dp,
    modifier = Modifier.padding(horizontal = 16.dp)
)

@Composable
internal fun MenuNavRow(
    label: String,
    dark: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val rowAlpha = if (enabled) 1f else DisabledAlpha
    val rowModifier = Modifier.fillMaxWidth()
        .then(
            if (enabled) Modifier.clickable(
                remember { MutableInteractionSource() }, null, onClick = onClick
            ) else Modifier
        )
        .padding(horizontal = 16.dp, vertical = if (subtitle != null) 10.dp else 14.dp)
        .alpha(rowAlpha)
    Row(
        rowModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = bodyStyle(dark))
            if (subtitle != null)
                Text(subtitle, style = captionStyle(dark))
        }
        if (!enabled) {
            io.automated.ventures.everypods.presentation.components.RequiresAacpIcon()
            Spacer(Modifier.width(8.dp))
        }
        Text("  ›", style = TextStyle(fontSize = 20.sp, fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f)))
    }
}

@Composable
private fun MenuCategory(
    label: String,
    dark: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    val borderColor = if (highlighted) Color(0xFF0A84FF) else Color.Transparent
    Column(Modifier.fillMaxWidth()
        .background(if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF), RoundedCornerShape(18.dp))
        .then(if (highlighted) Modifier.border(2.dp, borderColor, RoundedCornerShape(18.dp)) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) { onToggle() }
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
    // Peer UI can outlive one ViewModel's cached poll snapshot while the
    // process-level client/server link changes. Read the shared transport state
    // for this visible screen so an offline-peer warning cannot disappear with
    // a stale UI snapshot.
    var liveCrossDevicePeers by remember(viewModel) {
        mutableStateOf(viewModel.currentCrossDevicePeers())
    }
    LaunchedEffect(viewModel) {
        while (true) {
            liveCrossDevicePeers = viewModel.currentCrossDevicePeers()
            delay(1_000)
        }
    }
    val screenState = remember(state, liveCrossDevicePeers) {
        state.copy(crossDevicePeers = liveCrossDevicePeers)
    }
    val dark     = isSystemInDarkTheme()
    val context  = LocalContext.current
    val closeSearchDescription = stringResource(R.string.ui_close)
    val searchSettingsDescription = stringResource(R.string.ui_search_settings)
    val sharedPreferences = context.getSharedPreferences("settings", MODE_PRIVATE)

    val contactBottomSheet = remember { mutableStateOf(false) }
    val subjectState       = remember { TextFieldState() }
    val descriptionState   = remember { TextFieldState() }
    val backdrop           = rememberLayerBackdrop()

    var deviceName by remember {
        mutableStateOf(TextFieldValue(state.deviceName))
    }
    LaunchedEffect(state.deviceName) {
        if (deviceName.text.isBlank() || deviceName.text == "AirPods" || deviceName.text == "AirPods Pro") {
            deviceName = TextFieldValue(state.deviceName)
        }
    }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "name") deviceName = TextFieldValue(prefs.getString("name", state.deviceName) ?: state.deviceName)
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(l)
        onDispose { sharedPreferences.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.refreshInitialData() }

    // Search state
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery  by rememberSaveable { mutableStateOf("") }
    val searchIndex = remember(context) { searchIndex(context) }
    val searchResults = remember(searchQuery, searchIndex) { rankSearchResults(searchIndex, searchQuery) }
    val searchListState = rememberLazyListState()
    var restoreSearchOnReturn by rememberSaveable { mutableStateOf(false) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry?.destination?.route) {
        if (backStackEntry?.destination?.route == "settings" && restoreSearchOnReturn) {
            searchActive = true
            restoreSearchOnReturn = false
        }
    }

    val searchFocusRequester = remember { FocusRequester() }

    // Auto-focus the search field when search becomes active
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // Small delay to let the composition settle before requesting focus
            delay(100)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    StyledScaffold(
        title = if (searchActive) "" else deviceName.text,
        titleAlign = androidx.compose.ui.text.style.TextAlign.Start,
        actionButtons = buildList {
            val showSearch = true
            if (searchActive && showSearch) {
                add { scaffoldBackdrop ->
                    Row(
                        Modifier.fillMaxWidth().padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 16.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            modifier = Modifier.weight(1f)
                                .testTag("search_input")
                                .focusRequester(searchFocusRequester)
                                .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) Text(stringResource(R.string.ui_search_settings), style = TextStyle(fontSize = 16.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.4f) else Color.Black.copy(0.35f)))
                                innerTextField()
                            }
                        )
                        Spacer(Modifier.padding(start = 8.dp))
                        StyledIconButton(onClick = { searchActive = false; searchQuery = ""; restoreSearchOnReturn = false }, icon = "􀆄", backdrop = scaffoldBackdrop, modifier = Modifier.testTag("close_search").semantics { contentDescription = closeSearchDescription })
                    }
                }
            } else {
                // Tip jar immediately next to Search (tip left, search rightmost).
                add { scaffoldBackdrop ->
                    StyledIconButton(
                        imageVector = Icons.Filled.VolunteerActivism,
                        contentDescription = stringResource(R.string.support_content_description),
                        backdrop = scaffoldBackdrop,
                        onClick = { navController.navigate("purchase_screen") },
                        modifier = Modifier.testTag("nav_support_tip_button")
                    )
                }
                if (showSearch) {
                    add { scaffoldBackdrop ->
                        StyledIconButton(
                            onClick = { searchActive = true },
                            icon = "􀊫",
                            backdrop = scaffoldBackdrop,
                            modifier = Modifier.testTag("nav_settings_search_button").semantics { contentDescription = searchSettingsDescription }
                        )
                    }
                }
            }
        },
        snackbarHostState = snackbarHostState
    ) { topPadding, hazeState, bottomPadding ->
        var blockTouches by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            viewModel.demoActivated.collect { blockTouches = true; delay(1000); blockTouches = false }
        }

        // Search results overlay
        if (searchActive && searchQuery.isNotBlank() && searchResults.isNotEmpty()) {
            val cardBg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
            LazyColumn(
                state = searchListState,
                modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    .padding(top = topPadding + 4.dp)
                    .padding(horizontal = 16.dp)
            ) {
                item {
                    Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)) {
                        searchResults.forEachIndexed { idx, item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .testTag("search_result_${item.label}_${item.directRoute}_${item.anchor.orEmpty()}")
                                    .clickable(remember { MutableInteractionSource() }, null) {
                                        searchActive = false
                                        restoreSearchOnReturn = true
                                        val target = if (item.anchor != null)
                                            "${item.directRoute}?anchor=${item.anchor}"
                                        else item.directRoute
                                        navController.navigate(target)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(item.label, style = TextStyle(fontSize = 15.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                                    Text(item.category, style = TextStyle(fontSize = 12.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.45f)))
                                    if (item.categoryKey in setOf("controls", "connection", "smart", "audio") &&
                                        !state.isLocallyConnected && !state.isA2dpConnected
                                    ) {
                                        Text(stringResource(R.string.ui_connect_airpods_to_configure), style = captionStyle(dark))
                                    }
                                }
                                Text("›", style = TextStyle(fontSize = 18.sp, fontFamily = SfPro, color = if (dark) Color.White.copy(0.3f) else Color.Black.copy(0.3f)))
                            }
                            if (idx < searchResults.size - 1)
                                HorizontalDivider(color = Color(0x25888888), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        } else if (searchActive && searchQuery.isNotBlank()) {
            Box(Modifier.fillMaxSize().padding(top = topPadding + 4.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
                Text(stringResource(R.string.ui_no_search_results), style = captionStyle(dark), modifier = Modifier.padding(top = 20.dp))
            }
        } else if (state.isLocallyConnected || state.isA2dpConnected) {
            // A2DP is enough to classify the device as connected. Keep limited-mode
            // devices on this screen too; AACP-only controls explain their own availability.
            ConnectedScreen(
                state = screenState, appState = appState,
                viewModel = viewModel, appSettingsViewModel = appSettingsViewModel,
                navController = navController, sharedPrefs = sharedPreferences,
                topPadding = topPadding, bottomPadding = bottomPadding,
                hazeState = hazeState, dark = dark, blockTouches = blockTouches,
                onOpenContact = { contactBottomSheet.value = true },
            )
        } else {
            DisconnectedScreen(
                state = screenState, viewModel = viewModel,
                appSettingsViewModel = appSettingsViewModel,
                navController = navController,
                topPadding = topPadding, bottomPadding = bottomPadding,
                hazeState = hazeState, dark = dark,
                onOpenContact = { contactBottomSheet.value = true }
            )
        }
    }

    // Contact bottom sheet — shared between connected + disconnected modes
    StyledBottomSheet(visible = contactBottomSheet.value, onDismiss = { contactBottomSheet.value = false }, backdrop = backdrop) { innerBackdrop, progress ->
        val animPad = lerp(16.dp, 2.dp, progress)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = animPad)
                .padding(bottom = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StyledIconButton(icon = "\uDBC0\uDD84", backdrop = innerBackdrop, onClick = { contactBottomSheet.value = false })
                Text(
                    stringResource(R.string.describe_your_issue),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 18.sp, fontFamily = SfPro, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (dark) Color.White else Color.Black)
                )
                StyledIconButton(
                    icon = "\uDBC0\uDE1F", backdrop = innerBackdrop,
                    surfaceColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF),
                    iconTint = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray,
                    enabled = subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty(),
                    onClick = {
                        val sent = context.openEveryPodsSupportEmail(
                            subject = "EveryPods: ${subjectState.text}",
                            body = "${descriptionState.text}\n\n----------" +
                                "\nMANUFACTURER: ${Build.MANUFACTURER}" +
                                "\nMODEL: ${Build.MODEL} (${Build.PRODUCT})" +
                                "\nDISPLAY: ${Build.DISPLAY}" +
                                "\nSDK: ${Build.VERSION.SDK_INT_FULL}" +
                                "\nVERSION: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                                "\nBUILD_TYPE: ${BuildConfig.BUILD_TYPE}"
                        )
                        if (sent) {
                            contactBottomSheet.value = false
                            subjectState.clearText()
                            descriptionState.clearText()
                        }
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

@Composable
private fun PeerConnectionWarning(
    state: io.automated.ventures.everypods.presentation.viewmodel.AirPodsUiState,
    dark: Boolean,
    onRetry: () -> Unit,
) {
    val offlinePeers = state.crossDevicePeers.filter { !it.connected }
    if (offlinePeers.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val warningBackground = if (dark) Color(0xFF4A3514) else Color(0xFFFFF1CD)
    val warningTitle = if (dark) Color(0xFFFFC85A) else Color(0xFF5B3B00)
    val warningBody = if (dark) Color(0xFFFFF3D6) else Color(0xFF5B430B)
    val message = if (offlinePeers.size == 1) {
        stringResource(R.string.peer_connection_warning_one, offlinePeers.single().name)
    } else {
        stringResource(R.string.peer_connection_warning_many, offlinePeers.size)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("peer_connection_warning")
            .background(warningBackground, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("peer_connection_warning_toggle")
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (offlinePeers.size == 1) stringResource(R.string.peer_connection_warning_title)
                else stringResource(R.string.peer_connection_warning_title_many, offlinePeers.size),
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = warningTitle, fontFamily = SfPro),
            )
            Text(
                if (expanded) "⌃" else "⌄",
                style = TextStyle(fontSize = 18.sp, color = warningTitle, fontFamily = SfPro),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    message,
                    style = TextStyle(fontSize = 13.sp, color = warningBody, fontFamily = SfPro),
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("peer_connection_warning_retry"),
                ) {
                    Text(
                        stringResource(R.string.peer_connection_warning_retry),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = warningTitle, fontFamily = SfPro),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ConnectedScreen(
    state: io.automated.ventures.everypods.presentation.viewmodel.AirPodsUiState,
    appState: io.automated.ventures.everypods.presentation.viewmodel.AppSettingsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    sharedPrefs: SharedPreferences,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    blockTouches: Boolean,
    onOpenContact: () -> Unit,
) {
    val context      = LocalContext.current
    val capabilities = state.capabilities

    // Tall viewports (portrait phones, tablets either orientation): weight-based
    // layout — top content takes natural height, the tile grid fills the rest, so
    // all 6 tiles are visible without scrolling.
    // Short viewports (phone landscape ~411dp): the weight-based layout collapses
    // the tile grid and pushes controls off-screen. Switch to a scrollable column
    // with a fixed-height tile grid so everything is reachable.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isShortViewport = configuration.screenHeightDp < 480
    var relayCardVisible by remember { mutableStateOf(false) }
    val hasOfflineConnectionWarning = state.crossDevicePeers.any { !it.connected }
    // Warnings and relay status are additive content. Keep the category tiles at
    // their normal height and let the Home column scroll instead of giving the
    // tile grid less space when these cards appear.
    val scrollHome = isShortViewport || hasOfflineConnectionWarning || relayCardVisible
    val scrollGridHeight = if (isShortViewport) 240.dp else
        (configuration.screenHeightDp * 0.42f).coerceIn(300f, 420f).dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .padding(horizontal = 16.dp)
            .padding(top = topPadding, bottom = bottomPadding)
            .then(if (scrollHome) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .then(if (blockTouches) Modifier.pointerInput(Unit) {
                awaitPointerEventScope { while (true) { val e = awaitPointerEvent(PointerEventPass.Initial); e.changes.forEach { it.consume() } } }
            } else Modifier),
    ) {
        // Limited-mode devices can render this screen while AirPods are elsewhere.
        // Keep that actionable status; a successful local connection needs no label.
        val audioHere = state.isLocallyConnected || state.isA2dpConnected
        if (!audioHere) {
            val airpodsNearby = state.battery.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.size(8.dp).background(
                        if (airpodsNearby) Color(0xFFFF9500) else Color(0xFF8E8E93),
                        RoundedCornerShape(50),
                    ),
                )
                Text(
                    stringResource(if (airpodsNearby) R.string.airpods_nearby_elsewhere else R.string.airpods_not_nearby),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontFamily = SfPro,
                        color = if (dark) Color.White.copy(0.7f) else Color.Black.copy(0.7f),
                    ),
                )
            }
        }

        PeerConnectionWarning(
            state = state,
            dark = dark,
            onRetry = {
                state.crossDevicePeers.filter { !it.connected }
                    .forEach { viewModel.reconnectCrossDevicePeer(it.mac) }
            },
        )

        AudioRelayHomeSurface(
            peers = state.crossDevicePeers,
            localAirPodsConnected = state.isLocallyConnected || state.isA2dpConnected,
            navController = navController,
            modifier = Modifier.padding(top = 8.dp),
            onContentVisibilityChanged = { relayCardVisible = it },
        )

        // ── Battery ──────────────────────────────────────────────────────────
        BatteryView(
            batteryList = state.battery,
            budsRes = state.instance?.model?.budsRes ?: R.drawable.airpods_pro_2_case,
            caseRes = state.instance?.model?.caseRes ?: R.drawable.airpods_pro_2_case,
            caseControl = if (state.aacpAvailable && state.modelIdentified && capabilities.contains(Capability.HRM)) {
                { io.automated.ventures.everypods.presentation.components.HeartRateCaseControl(viewModel) }
            } else null,
        )

        // ── Listening Mode ────────────────────────────────────────────────
        if (capabilities.contains(Capability.LISTENING_MODE)) {
            Spacer(Modifier.height(8.dp))
            if (state.aacpAvailable) {
                NoiseControlSettings(
                    showOffListeningMode = true,
                    noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                    onNoiseControlModeChanged = { viewModel.setControlCommandInt(AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE, it) }
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f).alpha(DisabledAlpha.toFloat())) {
                        NoiseControlSettings(
                            showOffListeningMode = true,
                            noiseControlModeValue = state.controlStates[AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                            onNoiseControlModeChanged = { /* no-op: AACP not available */ }
                        )
                    }
                    io.automated.ventures.everypods.presentation.components.RequiresAacpIcon()
                }
            }
        }

        // ── Upgrade banner ────────────────────────────────────────────────
        if (!state.isPremium) {
            Spacer(Modifier.height(8.dp))
            StyledButton(onClick = { navController.navigate("purchase_screen") }, backdrop = rememberLayerBackdrop(),
                modifier = Modifier.fillMaxWidth(), maxScale = 0.05f,
                surfaceColor = if (dark) Color(0xFF916100) else Color(0xFFE59900)) {
                Text(stringResource(R.string.unlock_advanced_features), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Tile grid ─────────────────────────────────────────────────────
        // In scroll mode, preserve a useful menu size independently of warning
        // content. The screen-height proportion follows the normal weight-based
        // grid size on portrait phones and remains bounded on tablets.
        CategoryTileGrid(
            modifier = if (scrollHome) Modifier.fillMaxWidth().height(scrollGridHeight) else Modifier.weight(1f),
            navController = navController,
            dark = dark
        )
    }
}

@Composable
private fun CategoryTileGrid(
    modifier: Modifier = Modifier,
    navController: NavController,
    dark: Boolean,
) {
    val tileColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black

    // Scale tile typography + iconography based on screen width.
    // Reference: phone is ~360–420 dp wide; tablet is ≥ 600 dp; large tablet ≥ 840 dp.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    // In landscape the three tile rows share a short height, so a width-derived
    // emoji (44sp) overflows and clips. The per-tile emoji size is additionally
    // capped to the actual tile height via BoxWithConstraints below; this is just
    // the upper bound.
    val isLandscape = screenWidthDp > screenHeightDp
    val emojiFontSize = when {
        screenWidthDp >= 840 -> 44.sp
        screenWidthDp >= 600 -> 36.sp
        else                 -> 26.sp
    }
    val emojiSlotWidth = when {
        screenWidthDp >= 840 -> 60.dp
        screenWidthDp >= 600 -> 48.dp
        else                 -> 34.dp
    }
    val labelFontSize = when {
        screenWidthDp >= 840 -> 20.sp
        screenWidthDp >= 600 -> 17.sp
        else                 -> 13.sp
    }
    val tileCorner = if (screenWidthDp >= 600) 24.dp else 18.dp
    val tilePadH   = if (screenWidthDp >= 600) 20.dp else 12.dp
    // Landscape tiles are vertically compressed — trim vertical padding so the
    // icon + label have room and the emoji doesn't get clipped.
    val tilePadV   = when {
        isLandscape          -> 6.dp
        screenWidthDp >= 600 -> 16.dp
        else                 -> 10.dp
    }
    val rowSpacing = if (screenWidthDp >= 600) 12.dp else 8.dp

    val tiles = listOf(
        Triple("controls", "🎧", stringResource(R.string.search_category_controls)),
        Triple("audio", "🔊", stringResource(R.string.search_category_audio)),
        Triple("connection", "🔗", stringResource(R.string.design_connection)),
        Triple("smart", "✨", stringResource(R.string.search_category_smart)),
        Triple("appsettings", "📱", stringResource(R.string.search_category_app)),
        Triple("help", "❓", stringResource(R.string.search_category_help)),
    )
    // Each row takes an equal share of the available height via weight(1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowSpacing)
    ) {
        tiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                row.forEach { (key, emoji, label) ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(tileColor, RoundedCornerShape(tileCorner))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { navController.navigate("category/$key") }
                            .padding(horizontal = tilePadH, vertical = tilePadV),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            // Cap the emoji to the available tile height. An emoji
                            // glyph's line box is ~1.3× its font size, so to fit it
                            // vertically we keep fontSize ≤ height / 1.3 (≈ ×0.76).
                            // Use the width breakpoint value as the upper bound.
                            val heightCapSp = maxHeight.value * 0.72f
                            val effectiveEmojiSp = minOf(emojiFontSize.value, heightCapSp).sp
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    emoji,
                                    modifier = Modifier.width(emojiSlotWidth),
                                    style = TextStyle(fontSize = effectiveEmojiSp, fontFamily = SfPro)
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    style = TextStyle(fontSize = labelFontSize, fontWeight = FontWeight.Medium,
                                    fontFamily = SfPro, color = textColor))
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
//  DISCONNECTED MODE
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun DisconnectedScreen(
    state: io.automated.ventures.everypods.presentation.viewmodel.AirPodsUiState,
    viewModel: AirPodsViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    navController: NavController,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hazeState: HazeState,
    dark: Boolean,
    onOpenContact: () -> Unit
) {
    val appState  by appSettingsViewModel.uiState.collectAsState()
    val context   = LocalContext.current
    val backdrop  = rememberLayerBackdrop()
    val cardBg    = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black
    val tapCount  = remember { mutableIntStateOf(0) }
    val lastTap   = remember { mutableLongStateOf(0L) }
    val handoverTemporarilyPaused by io.automated.ventures.everypods.relay.DeviceConnectionStore.emittingState.collectAsState()
    val handoverUnlocked = rememberHandoverAvailable(state.crossDevicePeers)
    // Same gate as before: show reconnect when a saved MAC exists (or a prior
    // successful connection left one). Subtitle uses the last known AirPods name.
    val showReconnect = state.hasSavedDevice || state.connectionSuccessful
    val lastDeviceName = state.deviceName.takeIf {
        it.isNotBlank() && !it.equals("AirPods", ignoreCase = true) &&
            !it.equals("AirPods Pro", ignoreCase = true)
    }

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

        // 1) Hero — status + reconnect in one stacked card (no product artwork)
        item(key = "status") {
            val heroColor = if (dark) Color(0xFF143D32) else Color(0xFFD7F9E9)
            val heroTextColor = if (dark) Color(0xFFF3FFFA) else Color(0xFF12352A)
            val heroSecondaryColor = if (dark) Color(0xFFB4D9CA) else Color(0xFF416C5C)
            val statusLabel = if (state.isA2dpConnected) {
                stringResource(R.string.connected_via_bluetooth)
            } else {
                stringResource(R.string.airpods_not_connected)
            }
            val titleRes = if (state.isA2dpConnected) {
                R.string.connected_via_bluetooth
            } else {
                R.string.airpods_not_connected
            }
            val guideRes = if (state.isA2dpConnected) {
                R.string.connected_via_bluetooth_description
            } else {
                R.string.disconnected_hero_guide
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(heroColor, RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // App launcher icon — not AirPods product art
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.app_name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(27.dp))
                )
                Text(
                    stringResource(titleRes),
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = heroTextColor, fontFamily = SfPro),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(guideRes),
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = heroSecondaryColor, fontFamily = SfPro),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = if (dark) 0.12f else 0.66f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (state.isA2dpConnected) Color(0xFF22A06B) else Color(0xFFF0A020), RoundedCornerShape(50))
                    )
                    Text(
                        statusLabel,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = heroTextColor, fontFamily = SfPro)
                    )
                }
                if (showReconnect) {
                    StyledButton(
                        onClick = { viewModel.reconnectFromSavedMac() },
                        backdrop = backdrop,
                        height = 56.dp,
                        surfaceColor = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.reconnect_to_last),
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro, color = Color.White),
                                textAlign = TextAlign.Center
                            )
                            if (lastDeviceName != null) {
                                Text(
                                    lastDeviceName,
                                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = SfPro, color = Color.White.copy(alpha = 0.85f)),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "peer_connection_warning") {
            PeerConnectionWarning(
                state = state,
                dark = dark,
                onRetry = {
                    state.crossDevicePeers.filter { !it.connected }
                        .forEach { viewModel.reconnectCrossDevicePeer(it.mac) }
                },
            )
        }

        item(key = "audio_relay") {
            AudioRelayHomeSurface(
                peers = state.crossDevicePeers,
                localAirPodsConnected = state.isLocallyConnected || state.isA2dpConnected,
                navController = navController,
            )
        }

        // Keep the disconnected hub's connection, handover, finder, and support tools.
        // Their headings and group surfaces follow the shared settings hierarchy.
        item(key = "connections") {
            io.automated.ventures.everypods.presentation.components.SettingsSection(
                title = stringResource(R.string.connections_title)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connections_guide), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    DeviceConnectionsList(viewModel, navController)
                    HorizontalDivider(thickness = 1.dp, color = Color(0x40888888))
                    StyledToggle(
                        label = stringResource(R.string.lid_open_last_holder_autoconnect),
                        description = stringResource(R.string.lid_open_last_holder_autoconnect_description),
                        checked = state.lidOpenLastHolderAutoconnect && !handoverTemporarilyPaused,
                        onCheckedChange = viewModel::setLidOpenLastHolderAutoconnect,
                        independent = true,
                        enabled = !handoverTemporarilyPaused
                    )
                }
            }
        }

        item(key = "handover") {
            io.automated.ventures.everypods.presentation.components.SettingsSection(
                title = stringResource(R.string.handover_title)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.handover_guide), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    if (handoverTemporarilyPaused) {
                        Text(stringResource(R.string.audio_relay_auto_takeover_suppressed), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    }
                    if (!handoverUnlocked) {
                        Text(stringResource(R.string.handover_needs_peer_helper), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.5f)))
                    }
                    StyledToggle(
                        label = stringResource(R.string.cross_device_handover),
                        description = stringResource(R.string.cross_device_handover_description),
                        checked = state.crossDeviceEnabled && handoverUnlocked && !handoverTemporarilyPaused,
                        onCheckedChange = { viewModel.setCrossDeviceEnabled(it) },
                        independent = true,
                        enabled = handoverUnlocked && !handoverTemporarilyPaused
                    )
                    StyledToggle(
                        label = stringResource(R.string.takeover_media_start),
                        description = stringResource(R.string.takeover_media_start_desc),
                        checked = appState.takeoverWhenMediaStart && !handoverTemporarilyPaused,
                        onCheckedChange = appSettingsViewModel::setTakeoverWhenMediaStart,
                        independent = true,
                        enabled = !handoverTemporarilyPaused
                    )
                    if (appState.takeoverWhenMediaStart) io.automated.ventures.everypods.presentation.components.MediaHandoverAccessNotice()
                    StyledToggle(
                        label = stringResource(R.string.takeover_ringing_call),
                        description = stringResource(R.string.takeover_ringing_call_desc),
                        checked = appState.takeoverWhenRingingCall && handoverUnlocked && !handoverTemporarilyPaused,
                        onCheckedChange = appSettingsViewModel::setTakeoverWhenRingingCall,
                        independent = true,
                        enabled = handoverUnlocked && !handoverTemporarilyPaused
                    )
                }
            }
        }

        item(key = "find_nearby") {
            io.automated.ventures.everypods.presentation.components.SettingsSection(
                title = stringResource(R.string.find_my_airpods_title)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.find_my_airpods_guide), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    StyledButton(
                        onClick = { navController.navigate("proximity_finder") },
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.find_nearby), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                    }
                }
            }
        }

        item(key = "help") {
            io.automated.ventures.everypods.presentation.components.SettingsSection(
                title = stringResource(R.string.get_help_title)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(remember { MutableInteractionSource() }, null) { navController.navigate("purchase_screen") }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(imageVector = Icons.Filled.VolunteerActivism, contentDescription = null,
                            tint = if (dark) Color(0xFF64D2FF) else Color(0xFF0088FF), modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.support_everypods_title), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = SfPro, color = textColor))
                            Text(stringResource(R.string.support_everypods_card_subtitle), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                        }
                        Text("›", style = TextStyle(fontSize = 20.sp, fontFamily = SfPro, color = textColor.copy(0.35f)))
                    }
                    HorizontalDivider(thickness = 1.dp, color = Color(0x40888888))
                    Text(stringResource(R.string.get_help_guide), style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledButton(onClick = onOpenContact, backdrop = backdrop, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ui_email), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                        }
                        StyledButton(onClick = { context.openEveryPodsIssues() }, backdrop = backdrop, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ui_github), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                        }
                    }
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(bottomPadding)) }
    }
}
