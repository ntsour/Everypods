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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.utils.RelayDevMode

/** Per-process dismissal: the banner returns on the next app launch. */
private val dismissedThisSession = mutableStateOf(false)

/**
 * Hosts the app content under a compact dev banner (dev builds only). The banner takes the status-bar
 * inset itself and consumes it, so the app's Scaffold/top bar doesn't pad for it twice, and the
 * content keeps the remaining height (bottom navigation and settings entry points stay on screen).
 * Production builds render [content] unchanged.
 */
@Composable
fun RelayDevBannerHost(content: @Composable () -> Unit) {
    if (!RelayDevMode.isDevBuild || dismissedThisSession.value) { content(); return }
    Column(Modifier.fillMaxSize()) {
        RelayDevBanner()
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}

/** Dev-only coexistence banner: one line; details and the AACP switch behind "More". */
@Composable
fun RelayDevBanner() {
    if (!RelayDevMode.isDevBuild || dismissedThisSession.value) return
    val context = LocalContext.current
    val prodInstalled = remember { RelayDevMode.isProductionInstalled(context) }
    var aacp by remember { mutableStateOf(RelayDevMode.aacpEnabled(context)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val warn = RelayDevMode.shouldWarn(true, context.packageName, prodInstalled)
    val relayIntent = remember {
        android.content.Intent().setClassName(context, RelayDevMode.RELAY_DEV_ACTIVITY)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFE65100))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp)
    ) {
        Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (aacp) R.string.relaydev_banner_short_aacp else R.string.relaydev_banner_short) +
                    if (warn) " " + stringResource(R.string.relaydev_banner_short_prod) else "",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.relaydev_banner_less else R.string.relaydev_banner_more), color = Color.White, fontSize = 12.sp)
            }
            TextButton(onClick = { dismissedThisSession.value = true }) {
                Text(stringResource(R.string.relaydev_banner_dismiss), color = Color.White, fontSize = 12.sp)
            }
        }
        if (expanded) {
            Text(stringResource(if (aacp) R.string.relaydev_banner_aacp_on else R.string.relaydev_banner_relay_only), color = Color.White, fontSize = 12.sp)
            if (warn) Text(stringResource(R.string.relaydev_banner_prod_warning), color = Color.White, fontSize = 12.sp)
            if (relayIntent != null) {
                TextButton(onClick = { context.startActivity(relayIntent) }) {
                    Text(stringResource(R.string.relaydev_banner_open_relay), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.relaydev_banner_toggle), color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(checked = aacp, onCheckedChange = { aacp = it; RelayDevMode.setAacpEnabled(context, it) })
            }
        }
    }
}
