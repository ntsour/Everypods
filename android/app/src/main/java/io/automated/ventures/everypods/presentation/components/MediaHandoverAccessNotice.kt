/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.utils.MediaController
import io.automated.ventures.everypods.utils.MediaSessionAccess

@Composable
fun MediaHandoverAccessNotice() {
    val context = LocalContext.current
    val granted by MediaController.mediaSessionAccessGranted.collectAsState()
    LaunchedEffect(context) { MediaController.refreshMediaSessionMonitoring(context) }
    if (!granted) {
        val dark = isSystemInDarkTheme()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.media_handover_access_description), fontSize = 13.sp,
                color = if (dark) Color(0xFFAEAEB2) else Color(0xFF636366))
            TextButton(onClick = { MediaSessionAccess.openSettings(context) }) {
                Text(stringResource(R.string.media_handover_access_action),
                    color = if (dark) Color(0xFF64D2FF) else Color(0xFF0066CC))
            }
        }
    }
}
