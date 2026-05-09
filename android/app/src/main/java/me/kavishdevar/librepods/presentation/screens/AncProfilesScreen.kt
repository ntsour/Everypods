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

package me.kavishdevar.librepods.presentation.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.AncProfile
import me.kavishdevar.librepods.data.NoiseControlMode
import me.kavishdevar.librepods.presentation.components.SelectItem
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSelectList
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.utils.AncProfilesManager

@Composable
fun AncProfilesScreen(navController: NavController) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color.White else Color.Black

    var enabled by remember { mutableStateOf(AncProfilesManager.isEnabled(context)) }
    var profiles by remember { mutableStateOf(AncProfilesManager.loadAll(context)) }
    var editing by remember { mutableStateOf<AncProfile?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    DisposableEffect("anc_profiles") {
        val l: () -> Unit = { profiles = AncProfilesManager.loadAll(context) }
        AncProfilesManager.addListener(l)
        onDispose { AncProfilesManager.removeListener(l) }
    }

    StyledScaffold(title = "ANC profiles") { topPadding, _, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                StyledToggle(
                    label = "Auto-ANC profiles",
                    description = "Switch noise control mode automatically based on time of day. The first matching profile wins; check in declaration order.",
                    checked = enabled,
                    independent = false,
                    onCheckedChange = {
                        enabled = it
                        AncProfilesManager.setEnabled(context, it)
                    }
                )
            }

            Text(
                "Profiles",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.6f),
                    fontFamily = FontFamily(Font(R.font.sf_pro))
                ),
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )

            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        "No profiles yet. Tap \"Add profile\" below.",
                        style = TextStyle(fontSize = 14.sp, color = textColor.copy(alpha = 0.6f), fontFamily = FontFamily(Font(R.font.sf_pro)))
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(20.dp))
                ) {
                    profiles.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(thickness = 1.dp, color = Color(0x40888888), modifier = Modifier.padding(horizontal = 12.dp))
                        ProfileRow(p, textColor, onClick = { editing = p })
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
                    .clickable { showAdd = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    "Add profile",
                    style = TextStyle(fontSize = 16.sp, color = Color(0xFF0A84FF), fontFamily = FontFamily(Font(R.font.sf_pro)))
                )
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }

    if (showAdd) {
        ProfileEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { p ->
                AncProfilesManager.add(context, p)
                profiles = AncProfilesManager.loadAll(context)
                showAdd = false
            },
        )
    }
    editing?.let { p ->
        ProfileEditDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { updated ->
                AncProfilesManager.update(context, updated)
                profiles = AncProfilesManager.loadAll(context)
                editing = null
            },
            onDelete = {
                AncProfilesManager.delete(context, p.id)
                profiles = AncProfilesManager.loadAll(context)
                editing = null
            },
        )
    }
}

@Composable
private fun ProfileRow(p: AncProfile, textColor: Color, onClick: () -> Unit) {
    val display = "%02d:%02d → %02d:%02d".format(
        p.startMinute / 60, p.startMinute % 60,
        p.endMinute / 60, p.endMinute % 60
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(p.name, style = TextStyle(fontSize = 15.sp, color = textColor, fontFamily = FontFamily(Font(R.font.sf_pro))))
            Text(
                "$display · ${labelFor(p.ancMode)}",
                style = TextStyle(fontSize = 12.sp, color = textColor.copy(alpha = 0.6f), fontFamily = FontFamily(Font(R.font.sf_pro))),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ProfileEditDialog(
    initial: AncProfile?,
    onDismiss: () -> Unit,
    onSave: (AncProfile) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var startMin by remember { mutableStateOf(initial?.startMinute ?: 8 * 60) }
    var endMin by remember { mutableStateOf(initial?.endMinute ?: 18 * 60) }
    var mode by remember { mutableStateOf(initial?.ancMode ?: NoiseControlMode.TRANSPARENCY) }

    fun show(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, h, m -> onPicked(h * 60 + m) },
            current / 60, current % 60, true
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New profile" else "Edit profile") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { show(startMin) { startMin = it } }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Start", style = TextStyle(fontSize = 15.sp))
                    Text("%02d:%02d".format(startMin / 60, startMin % 60), style = TextStyle(fontSize = 15.sp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { show(endMin) { endMin = it } }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("End", style = TextStyle(fontSize = 15.sp))
                    Text("%02d:%02d".format(endMin / 60, endMin % 60), style = TextStyle(fontSize = 15.sp))
                }
                Spacer(Modifier.height(8.dp))
                StyledSelectList(items = NoiseControlMode.entries.map { m ->
                    SelectItem(
                        name = labelFor(m),
                        selected = mode == m,
                        onClick = { mode = m },
                    )
                })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                onSave(
                    initial?.copy(
                        name = name,
                        startMinute = startMin,
                        endMinute = endMin,
                        ancMode = mode,
                    ) ?: AncProfile(
                        name = name,
                        startMinute = startMin,
                        endMinute = endMin,
                        ancMode = mode,
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF453A)) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun labelFor(m: NoiseControlMode): String = when (m) {
    NoiseControlMode.OFF -> "Off"
    NoiseControlMode.NOISE_CANCELLATION -> "Noise Cancellation"
    NoiseControlMode.TRANSPARENCY -> "Transparency"
    NoiseControlMode.ADAPTIVE -> "Adaptive"
}
