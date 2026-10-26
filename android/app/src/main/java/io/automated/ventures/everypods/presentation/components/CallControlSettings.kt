package io.automated.ventures.everypods.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.data.HeadGestureCallAction

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun CallControlSettings(
    hazeState: HazeState,
    flipped: Boolean,
    onCallControlValueChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
    gesturesEnabled: Boolean = true,
    gestureEditingEnabled: Boolean = true,
) {
    // A single layout owns both header and rows; the old sibling Box overlapped them.
    SettingsSection(stringResource(R.string.hg_call_actions)) {
        HeadGestureCallAction.entries.forEachIndexed { index, action ->
            if (index > 0) SettingsDivider()
            Text(stringResource(when (action) {
                HeadGestureCallAction.ANSWER -> R.string.answer_call
                HeadGestureCallAction.DECLINE -> R.string.hg_decline_call
                HeadGestureCallAction.MUTE_TOGGLE -> R.string.mute_unmute
                HeadGestureCallAction.HANG_UP -> R.string.hang_up
            }), style = gestureLabelStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when (action) {
                HeadGestureCallAction.ANSWER -> Text(stringResource(R.string.press_once), Modifier.padding(horizontal = 16.dp), style = gestureSupportingStyle())
                HeadGestureCallAction.DECLINE -> Text(stringResource(R.string.press_twice), Modifier.padding(horizontal = 16.dp), style = gestureSupportingStyle())
                else -> {
                    val mute = action == HeadGestureCallAction.MUTE_TOGGLE
                    GestureSettingPicker(
                        title = stringResource(R.string.hg_stem_press),
                        value = if (mute) flipped else !flipped,
                        options = listOf(false to stringResource(R.string.press_once), true to stringResource(R.string.press_twice)),
                        enabled = enabled,
                    ) { double -> onCallControlValueChanged(if (mute) double else !double) }
                }
            }
            if (gesturesEnabled) HeadGestureCallPicker(action, enabled && gestureEditingEnabled)
        }
    }
}
