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


package io.automated.ventures.everypods.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.utils.RelayDevMode

/**
 * Dev-only coexistence banner (English only; never shipped: shown only when BuildConfig.RELAY_DEV).
 */
@Composable
fun RelayDevBanner() {
    if (!RelayDevMode.isDevBuild) return
    val context = LocalContext.current
    val prodInstalled = remember { RelayDevMode.isProductionInstalled(context) }
    var aacp by remember { mutableStateOf(RelayDevMode.aacpEnabled(context)) }
    val warn = RelayDevMode.shouldWarn(true, context.packageName, prodInstalled)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE65100))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(if (aacp) R.string.relaydev_banner_aacp_on else R.string.relaydev_banner_relay_only),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        if (warn) {
            Text(
                stringResource(R.string.relaydev_banner_prod_warning),
                color = Color.White, fontSize = 12.sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.relaydev_banner_toggle), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(checked = aacp, onCheckedChange = {
                aacp = it
                RelayDevMode.setAacpEnabled(context, it)
            })
        }
    }
}
