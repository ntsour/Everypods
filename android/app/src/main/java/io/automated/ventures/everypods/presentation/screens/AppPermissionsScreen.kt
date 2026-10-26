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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.core.app.ActivityCompat
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.automation.GymLocationPermission
import io.automated.ventures.everypods.services.CallNotifListener

private val PermSfPro get() = FontFamily(Font(R.font.sf_pro))

@Composable
fun AppPermissionsScreen(onPermissionsGranted: (() -> Unit)? = null) {
    val context     = LocalContext.current
    val dark        = isSystemInDarkTheme()
    val cardBg      = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor   = if (dark) Color.White else Color.Black
    val accent      = Color(0xFF0A84FF)
    val green       = Color(0xFF34C759)
    val orange      = Color(0xFFFF9500)
    val scrollState = rememberScrollState()

    // ── Permission check helpers ─────────────────────────────────────────
    fun isGranted(perm: String) =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun openAppSettings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ── State — refreshed every second ───────────────────────────────────
    var btGranted           by remember { mutableStateOf(false) }
    var locationGranted     by remember { mutableStateOf(false) }
    var notifGranted        by remember { mutableStateOf(false) }
    var phoneGranted        by remember { mutableStateOf(false) }
    var overlayGranted      by remember { mutableStateOf(false) }
    var notifAccessGranted  by remember { mutableStateOf(false) }
    var gymLocationStep     by remember { mutableStateOf(GymLocationPermission.step(context)) }

    fun refreshAll() {
        btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            isGranted(Manifest.permission.BLUETOOTH_CONNECT) && isGranted(Manifest.permission.BLUETOOTH_SCAN)
        else isGranted(Manifest.permission.BLUETOOTH)
        // Location is only needed on Android 11 (API 30) and below for BT scanning.
        // On API 31+, BLUETOOTH_SCAN with neverForLocation covers this — mark as not-required.
        locationGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        notifGranted        = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isGranted(Manifest.permission.POST_NOTIFICATIONS) else true
        phoneGranted        = isGranted(Manifest.permission.READ_PHONE_STATE) && isGranted(Manifest.permission.ANSWER_PHONE_CALLS)
        overlayGranted      = Settings.canDrawOverlays(context)
        notifAccessGranted  = CallNotifListener.isAccessGranted(context)
        gymLocationStep     = GymLocationPermission.step(context)
    }

    LaunchedEffect(Unit) {
        // Clear stale permissions_asked flags for permissions that are not relevant on this
        // Android version (e.g. ACCESS_FINE_LOCATION on API 31+). A stale flag causes the
        // "permanently denied" branch to fire even though the dialog was never shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSharedPreferences("permissions_asked", Context.MODE_PRIVATE).edit()
                .remove(Manifest.permission.ACCESS_FINE_LOCATION)
                .remove(Manifest.permission.ACCESS_COARSE_LOCATION)
                .apply()
        }
        refreshAll()
        while (true) {
            kotlinx.coroutines.delay(1000)
            refreshAll()
        }
    }

    // ── Launchers ────────────────────────────────────────────────────────
    val multiLauncher  = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAll() }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }
    // Optional; only requested when the user taps the Gym location row.
    val requestGymLocation = rememberGymLocationPermissionAction { refreshAll() }

    // Whether we've actually launched a system request for a permission IN THIS SESSION.
    // The "permanently denied → App Info" decision must be based on this, NOT on a
    // persisted flag: a persisted "asked" flag can be restored by Android Auto Backup
    // on reinstall (or survive an OS auto-revoke) while the permission is in the
    // never-asked state, which would wrongly send the user to App Info instead of
    // showing the real system dialog. Session-scoped tracking means the first tap
    // always attempts the dialog; only after we've genuinely asked and still can't
    // show a rationale do we treat it as permanently denied.
    val askedThisSession = remember { mutableSetOf<String>() }

    // Smart grant: try system dialog first; fall back to App Info only if permanently denied
    fun grantRuntime(permissions: Array<String>) {
        if (permissions.all { isGranted(it) }) return
        val activity = context as? Activity
        val asked = context.getSharedPreferences("permissions_asked", Context.MODE_PRIVATE)
        val permanentlyDenied = activity != null && permissions.any { perm ->
            !isGranted(perm)
                && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
                && askedThisSession.contains(perm)
        }
        if (permanentlyDenied) {
            openAppSettings()
        } else {
            askedThisSession.addAll(permissions)
            asked.edit().apply { permissions.forEach { putBoolean(it, true) }; apply() }
            if (permissions.size == 1) singleLauncher.launch(permissions[0])
            else multiLauncher.launch(permissions)
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────
    StyledScaffold(title = stringResource(R.string.ui_permissions_d06d5)) { topPadding, _, bottomPadding ->
        Column(
            Modifier.fillMaxSize()
.testTag("dest_permissions").verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Text(stringResource(R.string.ui_grant_the_permissions_everypod),
                style = TextStyle(fontSize = 14.sp, fontFamily = PermSfPro, color = textColor.copy(0.6f))
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (dark) Color(0xFF102A43) else Color(0xFFEAF4FF),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.cross_device_setup_title),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = PermSfPro,
                        color = if (dark) Color(0xFFB9DCFF) else Color(0xFF145A9C)
                    )
                )
                Text(
                    text = stringResource(R.string.cross_device_setup_description),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontFamily = PermSfPro,
                        color = if (dark) Color(0xFFD6E9FF) else Color(0xFF28506F)
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ── STANDARD PERMISSIONS ─────────────────────────────────────
            SectionTitle(stringResource(R.string.ui_standard), textColor)
            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp))) {
                PermissionRow(
                    title = stringResource(R.string.ui_bluetooth),
                    description = stringResource(R.string.ui_communicate_with_your_airpods),
                    granted = btGranted, dark = dark, accent = accent, green = green
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        grantRuntime(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                    else
                        grantRuntime(arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN))
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    RowDivider()
                    PermissionRow(
                        title = stringResource(R.string.ui_location),
                        description = stringResource(R.string.ui_required_for_bluetooth_scannin),
                        granted = locationGranted, dark = dark, accent = accent, green = green
                    ) { grantRuntime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
                }
                RowDivider()
                PermissionRow(
                    title = stringResource(R.string.ui_notifications),
                    description = stringResource(R.string.ui_show_battery_status_and_alerts),
                    granted = notifGranted, dark = dark, accent = accent, green = green
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        grantRuntime(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                RowDivider()
                PermissionRow(
                    title = stringResource(R.string.ui_phone),
                    description = stringResource(R.string.hg_permission_description),
                    granted = phoneGranted, dark = dark, accent = accent, green = green
                ) { grantRuntime(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)) }
            }

            // ── Grant all outstanding permissions ────────────────────────
            val locationNeeded = Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationGranted
            val notifNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted
            val anyCriticalMissing = !btGranted || locationNeeded || notifNeeded || !phoneGranted
            if (anyCriticalMissing) {
                Button(
                    onClick = {
                        val toRequest = buildList {
                            if (!btGranted) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    add(Manifest.permission.BLUETOOTH_CONNECT)
                                    add(Manifest.permission.BLUETOOTH_SCAN)
                                } else {
                                    add(Manifest.permission.BLUETOOTH)
                                    add(Manifest.permission.BLUETOOTH_ADMIN)
                                }
                            }
                            if (locationNeeded) add(Manifest.permission.ACCESS_FINE_LOCATION)
                            if (notifNeeded) add(Manifest.permission.POST_NOTIFICATIONS)
                            if (!phoneGranted) {
                                add(Manifest.permission.READ_PHONE_STATE)
                                add(Manifest.permission.ANSWER_PHONE_CALLS)
                            }
                        }
                        if (toRequest.isNotEmpty()) multiLauncher.launch(toRequest.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.ui_grant_required_permissions),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            fontFamily = PermSfPro, color = Color.White))
                }
            }

            // ── SPECIAL PERMISSIONS ──────────────────────────────────────
            SectionTitle(stringResource(R.string.ui_special_access), textColor)

            // Privacy notice
            Row(
                Modifier.fillMaxWidth()
                    .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFFFF3E0), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("⚠", style = TextStyle(fontSize = 14.sp, color = orange))
                Text(stringResource(R.string.ui_special_permissions_give_every),
                    style = TextStyle(fontSize = 13.sp, fontFamily = PermSfPro, color = orange)
                )
            }

            Column(Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(18.dp))) {
                PermissionRow(
                    title = stringResource(R.string.ui_display_over_other_apps),
                    description = stringResource(R.string.ui_show_dynamic_island_popup_when),
                    granted = overlayGranted, dark = dark, accent = accent, green = green
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.fromParts("package", context.packageName, null))
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }
                RowDivider()
                PermissionRow(
                    title = stringResource(R.string.ui_notification_access_4dca8),
                    description = stringResource(R.string.ui_sync_mute_state_with_teams_vib),
                    granted = notifAccessGranted, dark = dark, accent = accent, green = green
                ) { CallNotifListener.openAccessSettings(context) }
                RowDivider()
                PermissionRow(
                    title = stringResource(R.string.gym_perm_bg_title),
                    description = stringResource(R.string.gym_perm_bg_desc) + when (gymLocationStep) {
                        GymLocationPermission.Step.NEED_PRECISE -> "\n\n" + stringResource(R.string.gym_perm_bg_step1)
                        GymLocationPermission.Step.NEED_BACKGROUND -> "\n\n" + stringResource(R.string.gym_perm_bg_step2)
                        GymLocationPermission.Step.GRANTED -> ""
                    },
                    granted = gymLocationStep == GymLocationPermission.Step.GRANTED,
                    dark = dark, accent = accent, green = green,
                    modifier = Modifier.testTag("perm_gym_background_location"),
                ) { requestGymLocation() }
            }

            // Continue button for first-launch flow
            if (onPermissionsGranted != null) {
                Button(
                    onClick = { onPermissionsGranted() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.ui_continue),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            fontFamily = PermSfPro, color = Color.White))
                }
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

// ── Shared primitives ─────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String, textColor: Color) {
    io.automated.ventures.everypods.presentation.components.SettingsHeading(text)
}

@Composable
private fun RowDivider() = HorizontalDivider(
    color = Color(0x30888888), thickness = 0.5.dp,
    modifier = Modifier.padding(horizontal = 16.dp)
)

@Composable
private fun PermissionRow(
    title: String, description: String, granted: Boolean,
    dark: Boolean, accent: Color, green: Color, modifier: Modifier = Modifier, onGrant: () -> Unit
) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal,
                fontFamily = PermSfPro, color = textColor))
            Text(description, style = TextStyle(fontSize = 13.sp, fontFamily = PermSfPro,
                color = textColor.copy(0.55f)))
        }
        if (granted) {
            Text("✓", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = PermSfPro, color = green))
        } else {
            Button(
                onClick = onGrant,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.ui_grant), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    fontFamily = PermSfPro, color = Color.White))
            }
        }
    }
}
