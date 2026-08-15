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

package io.automated.ventures.everypods.presentation.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.utils.AnnouncementPrefs

private data class AppEntry(val packageName: String, val label: String)

@Composable
fun AnnouncementAppPickerScreen(navController: NavController) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color.White else Color.Black

    var showSystem by remember { mutableStateOf(false) }
    val apps = remember(showSystem) {
        val pm = context.packageManager
        if (showSystem) {
            // All installed packages (system + user). Lets users target apps
            // without a launcher icon — e.g. com.android.shell, system phone /
            // dialer / messaging apps on stock Android.
            pm.getInstalledApplications(0).asSequence()
                .map { it to pm.getApplicationLabel(it).toString() }
                .filter { (info, _) -> info.packageName != context.packageName }
                .map { (info, label) -> AppEntry(info.packageName, label) }
                .sortedBy { it.label.lowercase() }
                .toList()
        } else {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launcherIntent, 0)
            resolved.asSequence()
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != context.packageName }
                .map { pkg ->
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg
                    }
                    AppEntry(pkg, label)
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
    }

    val checkedMap = remember(apps) {
        mutableStateMapOf<String, Boolean>().apply {
            apps.forEach { put(it.packageName, AnnouncementPrefs.isAppEnabled(context, it.packageName)) }
        }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    StyledScaffold(title = "Apps — disable to silence") { topPadding, _, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(topPadding))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("Search apps") },
                singleLine = true,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Show system apps",
                    style = TextStyle(fontSize = 14.sp, color = textColor, fontFamily = FontFamily(Font(R.font.sf_pro)))
                )
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
            ) {
                items(filtered, key = { it.packageName }) { entry ->
                    AppRow(
                        label = entry.label,
                        packageName = entry.packageName,
                        textColor = textColor,
                        checked = checkedMap[entry.packageName] ?: false,
                        onCheckedChange = {
                            checkedMap[entry.packageName] = it
                            AnnouncementPrefs.setAppEnabled(context, entry.packageName, it)
                        }
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun AppRow(
    label: String,
    packageName: String,
    textColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(
                text = label,
                style = TextStyle(fontSize = 15.sp, color = textColor, fontFamily = FontFamily(Font(R.font.sf_pro)))
            )
            Text(
                text = packageName,
                style = TextStyle(fontSize = 12.sp, color = textColor.copy(alpha = 0.5f), fontFamily = FontFamily(Font(R.font.sf_pro)))
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
