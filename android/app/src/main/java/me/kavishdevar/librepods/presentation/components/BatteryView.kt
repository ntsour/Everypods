/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun BatteryView(
    batteryList: List<Battery>,
    budsRes: Int,
    caseRes: Int
) {
    val left  = batteryList.find { it.component == BatteryComponent.LEFT }
    val right = batteryList.find { it.component == BatteryComponent.RIGHT }
    val case  = batteryList.find { it.component == BatteryComponent.CASE }

    val isDark = isSystemInDarkTheme()
    val hintColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.4f)
    val sfPro = FontFamily(Font(R.font.sf_pro))

    // A bud is "known" when its status is not DISCONNECTED (or null)
    val leftKnown  = left  != null && left.status  != BatteryStatus.DISCONNECTED
    val rightKnown = right != null && right.status != BatteryStatus.DISCONNECTED
    val caseKnown  = case  != null && case.status  != BatteryStatus.DISCONNECTED

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.widthIn(max = 500.dp),
            horizontalArrangement = Arrangement.Center
        ) {

            // ── Buds column ───────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = ImageBitmap.imageResource(budsRes),
                    contentDescription = stringResource(R.string.buds),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )

                // Always show left and right individually
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Left bud
                    if (leftKnown) {
                        BatteryIndicator(
                            batteryPercentage = left!!.level,
                            status            = left.status,
                            prefix            = "\uDBC6\uDCE5"  // SF "L" glyph
                        )
                    } else {
                        // Bud disconnected / unknown — show a dash placeholder
                        BudPlaceholder(label = "\uDBC6\uDCE5", dark = isDark)
                    }

                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))

                    // Right bud
                    if (rightKnown) {
                        BatteryIndicator(
                            batteryPercentage = right!!.level,
                            status            = right.status,
                            prefix            = "\uDBC6\uDCE8"  // SF "R" glyph
                        )
                    } else {
                        BudPlaceholder(label = "\uDBC6\uDCE8", dark = isDark)
                    }
                }
            }

            // ── Case column ───────────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = ImageBitmap.imageResource(caseRes),
                    contentDescription = stringResource(R.string.case_alt),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )

                if (caseKnown) {
                    BatteryIndicator(
                        batteryPercentage = case!!.level,
                        status            = case.status
                    )
                } else {
                    // Case lid closed or not yet seen — guide the user
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "—",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontFamily = sfPro,
                                color = hintColor,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Open case lid to show charge",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontFamily = sfPro,
                                color = hintColor,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Grey dash placeholder shown for a bud that is disconnected/unknown. */
@Composable
private fun BudPlaceholder(label: String, dark: Boolean) {
    val color = if (dark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.3f)
    val sfPro = FontFamily(Font(R.font.sf_pro))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = "—",
            style = TextStyle(
                fontSize = 18.sp, fontFamily = sfPro,
                color = color, textAlign = TextAlign.Center
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$label —",
            style = TextStyle(
                fontSize = 14.sp, fontFamily = sfPro,
                color = color, textAlign = TextAlign.Center
            )
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BatteryViewPreview() {
    val fakeBattery = listOf(
        Battery(BatteryComponent.LEFT,  85, BatteryStatus.CHARGING),
        Battery(BatteryComponent.RIGHT, 40, BatteryStatus.OPTIMIZED_CHARGING),
        Battery(BatteryComponent.CASE,  60, BatteryStatus.NOT_CHARGING)
    )
    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    Box(modifier = Modifier.background(bg).padding(16.dp)) {
        BatteryView(
            batteryList = fakeBattery,
            budsRes = R.drawable.airpods_pro_2_buds,
            caseRes = R.drawable.airpods_pro_2_case
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Case closed")
@Composable
fun BatteryViewNoCase() {
    val fakeBattery = listOf(
        Battery(BatteryComponent.LEFT,  72, BatteryStatus.NOT_CHARGING),
        Battery(BatteryComponent.RIGHT, 68, BatteryStatus.NOT_CHARGING),
        Battery(BatteryComponent.CASE,   0, BatteryStatus.DISCONNECTED)
    )
    val bg = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    Box(modifier = Modifier.background(bg).padding(16.dp)) {
        BatteryView(
            batteryList = fakeBattery,
            budsRes = R.drawable.airpods_pro_2_buds,
            caseRes = R.drawable.airpods_pro_2_case
        )
    }
}
