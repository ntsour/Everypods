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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.services.TeamsNotifListener

private val SfPro get() = FontFamily(Font(R.font.sf_pro))

@Composable
fun AppPermissionsScreen() {
    val context   = LocalContext.current
    val dark      = isSystemInDarkTheme()
    val cardBg    = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black
    val accent    = Color(0xFF0A84FF)
    val green     = Color(0xFF34C759)
    val scrollState = rememberScrollState()

    // ── Runtime permission state ─────────────────────────────────────────────
    fun isGranted(perm: String) =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    var btGranted by remember { mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            isGranted(Manifest.permission.BLUETOOTH_CONNECT) && isGranted(Manifest.permission.BLUETOOTH_SCAN)
        else
            isGranted(Manifest.permission.BLUETOOTH) && isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    )}
    var notifGranted by remember { mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isGranted(Manifest.permission.POST_NOTIFICATIONS) else true
    )}
    var phoneGranted by remember { mutableStateOf(
        isGranted(Manifest.permission.READ_PHONE_STATE) && isGranted(Manifest.permission.ANSWER_PHONE_CALLS)
    )}
    var locationGranted by remember { mutableStateOf(isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var overlayGranted  by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifAccessGranted by remember { mutableStateOf(TeamsNotifListener.isAccessGranted(context)) }
    var contactsGranted by remember { mutableStateOf(isGranted(Manifest.permission.READ_CONTACTS)) }

    // Poll special permissions every second
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            overlayGranted     = Settings.canDrawOverlays(context)
            notifAccessGranted = TeamsNotifListener.isAccessGranted(context)
        }
    }

    // ── Permission launchers ─────────────────────────────────────────────────
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        btGranted = results.values.all { it }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        phoneGranted = results.values.all { it }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationGranted = it }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }

    // ── UI ───────────────────────────────────────────────────────────────────
    StyledScaffold(title = "Permissions") { topPadding, _, bottomPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Text(
                "Manage the permissions LibrePods needs to function correctly.",
                style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.6f))
            )

            // ── Permission cards ─────────────────────────────────────────────
            Column(
                Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp))
            ) {
                PermissionRow(
                    title = "Bluetooth",
                    description = "Communicate with your AirPods",
                    granted = btGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            btLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE))
                        else
                            btLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                )
                RowDivider()
                PermissionRow(
                    title = "Location",
                    description = "Required for Bluetooth scanning on Android < 12",
                    granted = locationGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
                RowDivider()
                PermissionRow(
                    title = "Notifications",
                    description = "Show battery status and alerts",
                    granted = notifGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
                RowDivider()
                PermissionRow(
                    title = "Phone",
                    description = "Answer and manage calls with head gestures and stem press",
                    granted = phoneGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = {
                        phoneLauncher.launch(arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.ANSWER_PHONE_CALLS
                        ))
                    }
                )
                RowDivider()
                PermissionRow(
                    title = "Contacts",
                    description = "Show caller names in announcements",
                    granted = contactsGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
                )
            }

            // ── Special permissions ──────────────────────────────────────────
            Column(
                Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp))
            ) {
                PermissionRow(
                    title = "Display Over Other Apps",
                    description = "Show popup animations when AirPods connect",
                    granted = overlayGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
                    }
                )
                RowDivider()
                PermissionRow(
                    title = "Notification Access",
                    description = "Sync mute state with Microsoft Teams",
                    granted = notifAccessGranted,
                    dark = dark, accent = accent, green = green,
                    onGrant = { TeamsNotifListener.openAccessSettings(context) }
                )
            }

            // ── Grant all button ─────────────────────────────────────────────
            Button(
                onClick = {
                    val toRequest = buildList {
                        if (!btGranted) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(Manifest.permission.BLUETOOTH_CONNECT)
                                add(Manifest.permission.BLUETOOTH_SCAN)
                                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            } else {
                                add(Manifest.permission.BLUETOOTH)
                                add(Manifest.permission.BLUETOOTH_ADMIN)
                            }
                        }
                        if (!locationGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (!phoneGranted) { add(Manifest.permission.READ_PHONE_STATE); add(Manifest.permission.ANSWER_PHONE_CALLS) }
                        if (!contactsGranted) add(Manifest.permission.READ_CONTACTS)
                    }
                    if (toRequest.isNotEmpty()) btLauncher.launch(toRequest.toTypedArray())
                    if (!overlayGranted) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
                    if (!notifAccessGranted) TeamsNotifListener.openAccessSettings(context)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Grant All Permissions", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(
    color = Color(0x30888888), thickness = 0.5.dp,
    modifier = Modifier.padding(horizontal = 16.dp)
)

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    dark: Boolean,
    accent: Color,
    green: Color,
    onGrant: () -> Unit
) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
            Text(description, style = TextStyle(fontSize = 12.sp, fontFamily = SfPro, color = textColor.copy(0.55f)))
        }
        if (granted) {
            Text("✓ Granted", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = green))
        } else {
            Button(
                onClick = onGrant,
                modifier = Modifier.height(34.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text("Grant", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
            }
        }
    }
}
