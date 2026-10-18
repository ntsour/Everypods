package io.automated.ventures.everypods.presentation.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test fun connection_settings_keep_their_target_after_audio_split() {
        listOf("handover", "ear_detection", "lid_open_autoconnect", "disconnect_when_not_wearing", "takeover_ringing", "takeover_media").forEach { target ->
            assertEquals("category/connection?anchor=$target", canonicalSettingsRoute("category/audio?anchor=$target"))
        }
        assertEquals("category/audio?anchor=personalized_volume", canonicalSettingsRoute("category/audio?anchor=personalized_volume"))
        assertEquals("category/connection?anchor=lid_open_autoconnect", canonicalSettingsRoute("category/connection?anchor=lid_open_autoconnect"))
    }

    @Test fun stem_search_preserves_bud_and_gesture() {
        listOf("left", "right").forEach { bud ->
            listOf("SINGLE_PRESS", "DOUBLE_PRESS", "TRIPLE_PRESS", "LONG_PRESS").forEach { gesture ->
                assertEquals("category/controls?bud=$bud&anchor=stem_$gesture", canonicalSettingsRoute("press_actions?bud=$bud&gesture=$gesture"))
            }
        }
    }

    @Test fun old_screens_resolve_to_canonical_inline_controls() {
        mapOf(
            "call_controls" to "category/controls?anchor=call_controls",
            "accessibility" to "category/controls?anchor=controls_configuration",
            "conversation_awareness" to "category/audio?anchor=conversation_awareness",
            "adaptive_strength" to "category/audio?anchor=adaptive_audio",
            "microphone_settings" to "category/audio?anchor=microphone",
            "phone_battery" to "category/appsettings?anchor=phone_battery",
            "connection_settings" to "category/connection",
            "sleep_timer" to "category/smart?anchor=sleep_timer",
        ).forEach { (old, canonical) ->
            assertEquals(canonical, canonicalSettingsRoute(old))
            assertEquals(canonical, canonicalSettingsRoute(canonical))
        }
    }

    @Test fun complex_tools_keep_their_dedicated_destinations() {
        listOf("notification_announcements?target=quiet_hours", "gym_timer", "head_tracking", "proximity_finder", "equalizer_screen", "paired_devices").forEach {
            assertEquals(it, canonicalSettingsRoute(it))
        }
    }
}
