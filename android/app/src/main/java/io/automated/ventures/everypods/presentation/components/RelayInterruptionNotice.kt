/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.relay.RelayInterruption
import io.automated.ventures.everypods.relay.RelayRole
import io.automated.ventures.everypods.relay.RelayStage
import io.automated.ventures.everypods.relay.RelayUiState

@Composable
fun relaySuspensionMessage(state: RelayUiState, peerName: String): String = stringResource(
    when {
        state.resumeUnavailable -> R.string.audio_relay_resume_unavailable
        state.interruption == RelayInterruption.TEMPORARY -> R.string.audio_relay_suspended_temporary
        else -> R.string.audio_relay_suspended_local
    },
    if (state.role == RelayRole.SOURCE) peerName else stringResource(R.string.audio_relay_route_this_device),
)

/** Nonblocking information and explicit Resume; all actions go to the service. */
@Composable
fun RelayInterruptionNotice(state: RelayUiState, peerName: String, textColor: Color, onResume: () -> Unit) {
    if (state.stage != RelayStage.SUSPENDED) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(relaySuspensionMessage(state, peerName), color = textColor)
        Text(stringResource(R.string.audio_relay_suspended_capture_note), color = textColor.copy(alpha = .7f))
        OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.audio_relay_resume))
        }
    }
}
