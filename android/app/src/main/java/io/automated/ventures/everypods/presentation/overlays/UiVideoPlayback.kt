package io.automated.ventures.everypods.presentation.overlays

import android.media.AudioAttributes
import android.media.AudioManager
import android.widget.VideoView

/** Decorative video must neither request media focus nor look like user playback. */
internal object UiVideoPlayback {
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun configure(view: VideoView) {
        view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        view.setAudioAttributes(attributes())
    }
}
