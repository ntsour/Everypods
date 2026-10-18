package io.automated.ventures.everypods.presentation.screens

/** Canonical settings locations shared by old routes and search. No setting is changed by navigation. */
internal fun canonicalSettingsRoute(route: String): String {
    val path = route.substringBefore('?')
    val query = route.substringAfter('?', "").split('&').mapNotNull {
        val pair = it.split('=', limit = 2)
        if (pair.size == 2) pair[0] to pair[1] else null
    }.toMap()
    val destination = when (path) {
        "app_settings" -> "category/appsettings"
        "smart_features" -> "category/smart"
        "accessibility", "controls_configuration" -> "category/controls?anchor=controls_configuration"
        "call_controls" -> "category/controls?anchor=call_controls"
        "listening_mode_config" -> "category/controls?anchor=listening_modes"
        "conversation_awareness" -> "category/audio?anchor=conversation_awareness"
        "hearing_protection" -> "category/audio?anchor=hearing_protection"
        "adaptive_audio", "adaptive_strength" -> "category/audio?anchor=adaptive_audio"
        "microphone_settings" -> "category/audio?anchor=microphone"
        "bluetooth_control" -> "category/connection?anchor=device"
        "connection_settings", "category/settings" -> "category/connection"
        "audio_settings" -> "category/audio?anchor=audio_controls"
        "smart_automation" -> "category/smart?anchor=automation"
        "sleep_timer" -> "category/smart?anchor=sleep_timer"
        "phone_battery" -> "category/appsettings?anchor=phone_battery"
        "popup_animations" -> "category/appsettings?anchor=popup_animations"
        "press_actions" -> "category/controls?bud=${query["bud"] ?: "left"}&anchor=stem_${query["gesture"] ?: "actions"}"
        else -> route
    }
    if (destination.startsWith("category/audio") && query["anchor"] in setOf(
            "handover", "ear_detection", "lid_open_autoconnect", "disconnect_when_not_wearing", "takeover_ringing", "takeover_media")) {
        return destination.replace("category/audio", "category/connection")
    }
    return destination
}
