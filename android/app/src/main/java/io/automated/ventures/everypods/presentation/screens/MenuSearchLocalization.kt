package io.automated.ventures.everypods.presentation.screens

import android.content.Context
import io.automated.ventures.everypods.R

/** Localize display names without changing routes or stored preference values. */
internal fun localizeSearchItem(context: Context, item: SearchableItem): SearchableItem {
    val names = mapOf(
        "Listening Mode Configuration" to R.string.ui_listening_mode_configuration,
        "Call Controls" to R.string.call_controls,
        "Volume Control" to R.string.volume_control,
        "Controls Configuration" to R.string.controls_configuration,
        "Gym Mode" to R.string.quick_settings_gym_mode,
        "Gym Press Actions" to R.string.ui_gym_press_actions,
        "Device Name" to R.string.ui_device_name,
        "Hearing Protection" to R.string.hearing_protection,
        "Conversation Awareness" to R.string.conversational_awareness,
        "Disconnect" to R.string.disconnect,
        "Notification Announcements" to R.string.ui_notification_announcements,
        "Find my Case" to R.string.ui_find_my_case,
        "Head Gestures" to R.string.head_gestures,
        "Adaptive Audio" to R.string.adaptive_audio,
        "Sleep Detection" to R.string.ui_sleep_detection,
        "Optimized Charging" to R.string.optimized_charging,
        "Resume Media After Call" to R.string.ui_resume_media_after_call,
        "Battery Alerts" to R.string.battery_alerts,
        "Sleep Timer" to R.string.ui_sleep_timer,
        "Gym Timer" to R.string.ui_gym_timer,
        "Battery in Widget" to R.string.ui_battery_in_widget,
        "Pop-up Animations" to R.string.popup_animations,
        "Bottom Sheet Popup" to R.string.show_bottom_sheet_popup,
        "Dynamic Island Popup" to R.string.show_island_popup,
        "Permissions" to R.string.ui_permissions,
        "Background Activity" to R.string.background_activity,
        "Audio Settings" to R.string.ui_audio_settings,
        "Personalized Volume" to R.string.personalized_volume,
        "Handover to Other Devices" to R.string.cross_device_handover,
        "Peer Android Device" to R.string.ui_peer_android_device,
        "Ear Detection" to R.string.ear_detection,
        "Disconnect When Not Wearing" to R.string.disconnect_when_not_wearing,
        "Microphone Settings" to R.string.ui_microphone_settings,
        "Connection Settings" to R.string.ui_connection_settings,
        "Open Source Licenses" to R.string.open_source_licenses,
        "Version Info" to R.string.ui_version_info,
        "Support EveryPods" to R.string.support_everypods_title,
        "Email Support" to R.string.ui_email_support,
        "GitHub Issues" to R.string.github_issues,
    )
    val pressNames = mapOf(
        "Single Press" to R.string.ui_single_press,
        "Double Press" to R.string.press_twice,
        "Triple Press" to R.string.press_three_times,
        "Long Press" to R.string.press_and_hold_airpods,
    )
    val connectionStates = mapOf(
        "Connect When Disconnected" to R.string.takeover_disconnected,
        "Connect When Idle" to R.string.takeover_idle,
        "Connect When Playing Media" to R.string.takeover_music,
        "Connect When On Call" to R.string.takeover_call,
        "Connect When Receiving a Call" to R.string.takeover_ringing_call,
        "Connect When Starting Media" to R.string.takeover_media_start,
    )
    val earbudPress = item.label.split(" — ", limit = 2)
    val localizedLabel = when {
        names.containsKey(item.label) -> context.getString(names.getValue(item.label))
        earbudPress.size == 2 && pressNames.containsKey(earbudPress[1]) -> {
            val side = if (earbudPress[0] == "Left Bud") R.string.ui_left_bud else R.string.ui_right_bud
            context.getString(side) + " — " + context.getString(pressNames.getValue(earbudPress[1]))
        }
        connectionStates.containsKey(item.label) ->
            context.getString(R.string.automatic_connection) + " · " +
                context.getString(connectionStates.getValue(item.label))
        else -> item.label
    }
    return item.copy(
        label = localizedLabel,
        category = categoryTitles[item.categoryKey]?.let(context::getString) ?: item.category,
        // English search aliases remain useful for protocol names and existing users.
        keywords = item.keywords + item.label,
    )
}
