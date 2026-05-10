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
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.services.AppListenerService
import me.kavishdevar.librepods.services.TeamsNotifListener

private val PermSfPro get() = FontFamily(Font(R.font.sf_pro))

@Composable
fun AppPermissionsScreen() {
    val context     = LocalContext.current
    val dark        = isSystemInDarkTheme()
    val cardBg      = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor   = if (dark) Color.White else Color.Black
    val accent      = Color(0xFF0A84FF)
    val green       = Color(0xFF34C759)
    val scrollState = rememberScrollState()

    // ── Permission check helpers ─────────────────────────────────────────
    fun isGranted(perm: String) =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun isAppListenerEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val sc = ComponentName(context, AppListenerService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == sc.packageName && it.resolveInfo.serviceInfo.name == sc.className }
    }

    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ── State — refreshed every second ───────────────────────────────────
    var btGranted          by remember { mutableStateOf(false) }
    var locationGranted    by remember { mutableStateOf(false) }
    var notifGranted       by remember { mutableStateOf(false) }
    var phoneGranted       by remember { mutableStateOf(false) }
    var contactsGranted    by remember { mutableStateOf(false) }
    var overlayGranted     by remember { mutableStateOf(false) }
    var notifAccessGranted by remember { mutableStateOf(false) }
    var cameraAccessGranted by remember { mutableStateOf(false) }

    fun refreshAll() {
        btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            isGranted(Manifest.permission.BLUETOOTH_CONNECT) && isGranted(Manifest.permission.BLUETOOTH_SCAN)
        else isGranted(Manifest.permission.BLUETOOTH) && isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        locationGranted     = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        notifGranted        = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isGranted(Manifest.permission.POST_NOTIFICATIONS) else true
        phoneGranted        = isGranted(Manifest.permission.READ_PHONE_STATE) && isGranted(Manifest.permission.ANSWER_PHONE_CALLS)
        contactsGranted     = isGranted(Manifest.permission.READ_CONTACTS)
        overlayGranted      = Settings.canDrawOverlays(context)
        notifAccessGranted  = TeamsNotifListener.isAccessGranted(context)
        cameraAccessGranted = isAppListenerEnabled()

    }

    // Initial + periodic refresh
    LaunchedEffect(Unit) {
        refreshAll()
        while (true) {
            kotlinx.coroutines.delay(1000)
            refreshAll()
        }
    }

    // ── Launchers ────────────────────────────────────────────────────────
    // Universal launcher — refreshes all state after any grant
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAll() }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }

    // ── Smart grant: check if permanently denied, then either show dialog or open settings
    fun grantRuntime(permissions: Array<String>) {
        if (permissions.all { isGranted(it) }) return

        // Check if any permission is permanently denied (user tapped "Don't ask again")
        val activity = context as? Activity
        val permanentlyDenied = activity != null && permissions.any { perm ->
            !isGranted(perm) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
                && context.getSharedPreferences("permissions_asked", Context.MODE_PRIVATE).getBoolean(perm, false)
        }

        if (permanentlyDenied) {
            // Can't show dialog — open system app settings where user can toggle manually
            openAppSettings()
        } else {
            // Mark that we've asked, so next time we know if it was permanently denied
            context.getSharedPreferences("permissions_asked", Context.MODE_PRIVATE).edit().apply {
                permissions.forEach { putBoolean(it, true) }
                apply()
            }
            if (permissions.size == 1) singleLauncher.launch(permissions[0])
            else multiLauncher.launch(permissions)
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────
    StyledScaffold(title = "Permissions") { topPadding, _, bottomPadding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Text(
                "Manage the permissions LibrePods needs to function correctly.",
                style = TextStyle(fontSize = 14.sp, fontFamily = SfPro, color = textColor.copy(0.6f))
            )

            // ── Runtime permissions ──────────────────────────────────────
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp))) {
                PermissionRow("Bluetooth", "Communicate with your AirPods", btGranted, dark, accent, green) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        grantRuntime(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE))
                    else
                        grantRuntime(arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION))
                }
                RowDivider()
                PermissionRow("Location", "Required for Bluetooth scanning", locationGranted, dark, accent, green) {
                    grantRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }
                RowDivider()
                PermissionRow("Notifications", "Show battery status and alerts", notifGranted, dark, accent, green) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        grantRuntime(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                RowDivider()
                PermissionRow("Phone", "Answer calls with head gestures and stem press", phoneGranted, dark, accent, green) {
                    grantRuntime(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
                }
                RowDivider()
                PermissionRow("Contacts", "Show caller names in announcements", contactsGranted, dark, accent, green) {
                    grantRuntime(arrayOf(Manifest.permission.READ_CONTACTS))
                }
            }

            // ── Special permissions (require system settings) ────────────
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp))) {
                PermissionRow("Display Over Other Apps", "Popup animations when AirPods connect", overlayGranted, dark, accent, green) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                RowDivider()
                PermissionRow("Notification Access", "Sync mute state with Microsoft Teams", notifAccessGranted, dark, accent, green) {
                    TeamsNotifListener.openAccessSettings(context)
                }
                RowDivider()
                PermissionRow("Camera Listener", "Detect camera app to trigger shutter via stem press", cameraAccessGranted, dark, accent, green) {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                            putString(":settings:fragment_args_key", "${context.packageName}/.services.AppListenerService")
                        })
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    }
                }
            }

            // ── Grant all ────────────────────────────────────────────────
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
                    if (toRequest.isNotEmpty()) multiLauncher.launch(toRequest.toTypedArray())
                    if (!overlayGranted) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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
    title: String, description: String, granted: Boolean,
    dark: Boolean, accent: Color, green: Color, onGrant: () -> Unit
) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text("Grant", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
            }
        }
    }
}
