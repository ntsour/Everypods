/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.utils.CompanionPairing

/** Offers the one-time companion link that speeds up call handover. Hidden once linked. */
@Composable
fun CompanionPairingNotice() {
    val context = LocalContext.current
    val mac = CompanionPairing.savedMac(context)
    if (mac.isBlank()) return
    var associated by remember(mac) { mutableStateOf(CompanionPairing.isAssociated(context, mac)) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        associated = CompanionPairing.isAssociated(context, mac)
        if (!associated && result.resultCode != Activity.RESULT_CANCELED) error = ""
    }
    if (associated) return
    val dark = isSystemInDarkTheme()
    val secondary = if (dark) Color(0xFFAEAEB2) else Color(0xFF636366)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.companion_pairing_description), fontSize = 13.sp, color = secondary)
        error?.let {
            Text(stringResource(R.string.companion_pairing_failed), fontSize = 13.sp,
                color = if (dark) Color(0xFFFF6961) else Color(0xFFD70015))
        }
        TextButton(onClick = {
            error = null
            CompanionPairing.request(context,
                onConfirmationNeeded = { launcher.launch(IntentSenderRequest.Builder(it).build()) },
                onDone = { ok, message ->
                    associated = ok || CompanionPairing.isAssociated(context, mac)
                    if (!associated && message != null) error = message
                })
        }) {
            Text(stringResource(R.string.companion_pairing_action),
                color = if (dark) Color(0xFF64D2FF) else Color(0xFF0066CC))
        }
    }
}
