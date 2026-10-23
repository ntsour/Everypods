/*
    EveryPods - AirPods liberated from Apple’s ecosystem
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
import android.content.pm.PackageManager
import android.app.LocaleManager
import android.os.LocaleList
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import io.automated.ventures.everypods.BuildConfig
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.AppInfoCard
import io.automated.ventures.everypods.presentation.components.DeviceInfoCard
import io.automated.ventures.everypods.presentation.components.NavigationButton
import io.automated.ventures.everypods.presentation.components.StyledBottomSheet
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledIconButton
import io.automated.ventures.everypods.presentation.components.StyledInputField
import io.automated.ventures.everypods.utils.openEveryPodsIssues
import io.automated.ventures.everypods.utils.openEveryPodsSupportEmail
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.SettingsSection
import io.automated.ventures.everypods.presentation.components.StyledSlider
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.presentation.viewmodel.AppSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    navController: NavController, viewModel: AppSettingsViewModel = viewModel(),
    crossDevicePeers: List<io.automated.ventures.everypods.presentation.viewmodel.PeerUiInfo> = emptyList()
) {
    val context = LocalContext.current
    val handoverAvailable = rememberHandoverAvailable(crossDevicePeers)
    val scrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()

    val backdrop = rememberLayerBackdrop()

    val contactBottomSheet = remember { mutableStateOf(false) }
    val subjectState = remember { TextFieldState() }
    val descriptionState = remember { TextFieldState() }
    val subjectFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }

    StyledScaffold(
        title = stringResource(R.string.settings)
    ) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .hazeSource(state = hazeState)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            val isDarkTheme = isSystemInDarkTheme()
            val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
            val textColor = if (isDarkTheme) Color.White else Color.Black

            AppLanguageSetting()

            if (!state.isPremium && state.connectionSuccessful) {
                StyledButton(
                    onClick = {
                        navController.navigate("purchase_screen")
                    },
                    backdrop = rememberLayerBackdrop(),
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = if (isSystemInDarkTheme()) Color(0xFF916100) else Color(0xFFE59900)
                ) {
                    Text(
                        stringResource(R.string.unlock_advanced_features),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = Color.White
                        ),
                    )
                }
            }

            if (state.connectionSuccessful) {
                StyledToggle(
                    title = stringResource(R.string.widget),
                    label = stringResource(R.string.show_phone_battery_in_widget),
                    description = stringResource(R.string.show_phone_battery_in_widget_description),
                    checked = state.showPhoneBatteryInWidget,
                    onCheckedChange = viewModel::setShowPhoneBatteryInWidget,
                    enabled = state.isPremium
                )

                Text(
                    text = stringResource(R.string.popup_animations), style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ), modifier = Modifier.padding(16.dp, bottom = 2.dp, top = 24.dp)
                )

                Spacer(modifier = Modifier.height(2.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            backgroundColor, RoundedCornerShape(28.dp)
                        )
                        .padding(vertical = 4.dp)
                ) {
                    StyledToggle(
                        label = stringResource(R.string.show_bottom_sheet_popup),
                        description = stringResource(R.string.show_bottom_sheet_popup_description),
                        checked = state.showBottomSheetPopup,
                        onCheckedChange = viewModel::setShowBottomSheetPopup,
                        independent = false
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    StyledToggle(
                        label = stringResource(R.string.show_island_popup),
                        description = stringResource(R.string.show_island_popup_description),
                        checked = state.showIslandPopup,
                        onCheckedChange = viewModel::setShowIslandPopup,
                        independent = false
                    )
                }

                // Conversation Awareness moved to AirPods Settings menu in AirPodsSettingsScreen

                Spacer(modifier = Modifier.height(16.dp))
                if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                    StyledToggle(
                        title = stringResource(R.string.ear_detection),
                        label = stringResource(R.string.disconnect_when_not_wearing),
                        description = stringResource(R.string.disconnect_when_not_wearing_description),
                        checked = state.disconnectWhenNotWearing,
                        onCheckedChange = viewModel::setDisconnectWhenNotWearing,
                        enabled = state.isPremium
                    )
                }

                Text(
                    text = stringResource(R.string.takeover_phone_state), style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    ), modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            backgroundColor, RoundedCornerShape(28.dp)
                        )
                        .padding(vertical = 4.dp)
                ) {
                    StyledToggle(
                        label = stringResource(R.string.takeover_ringing_call),
                        description = stringResource(R.string.takeover_ringing_call_desc),
                        checked = state.takeoverWhenRingingCall && handoverAvailable,
                        onCheckedChange = viewModel::setTakeoverWhenRingingCall,
                        independent = false,
                        enabled = state.isPremium && handoverAvailable
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0x40888888),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    StyledToggle(
                        label = stringResource(R.string.takeover_media_start),
                        description = stringResource(R.string.takeover_media_start_desc),
                        checked = state.takeoverWhenMediaStart && handoverAvailable,
                        onCheckedChange = viewModel::setTakeoverWhenMediaStart,
                        independent = false,
                        enabled = state.isPremium && handoverAvailable
                    )
                }

                // Advanced Options (alternate head tracking) moved to HeadTrackingScreen
            } else {
                Box(
                    modifier = Modifier
                        .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7))
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.customizations_unavailable),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily(Font(R.font.sf_pro)),
                            color = textColor.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            NavigationButton(
                to = "permissions",
                name = stringResource(R.string.ui_manage_permissions),
                navController = navController,
                independent = true,
                description = stringResource(R.string.ui_grant_or_revoke_app_permission)
            )

            Box(
                modifier = Modifier
                    .background(if (isDarkTheme) Color.Black else Color(0xFFF2F2F7))
                    .padding(start = 16.dp, bottom = 2.dp, top = 24.dp, end = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.contact), style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.6f),
                        fontFamily = FontFamily(Font(R.font.sf_pro))
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        backgroundColor, RoundedCornerShape(28.dp)
                    )
                    .clip(RoundedCornerShape(28.dp))
            ) {
                NavigationButton(
                    to = "",
                    name = stringResource(R.string.email),
                    navController = navController,
                    onClick = { contactBottomSheet.value = true },
                    independent = false
                )

                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationButton(
                    to = "",
                    name = stringResource(R.string.github_issues),
                    navController = navController,
                    onClick = {
                        context.openEveryPodsIssues()
                    },
                    independent = false
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            DeviceInfoCard()
            Spacer(modifier = Modifier.height(16.dp))
            AppInfoCard()

            Spacer(modifier = Modifier.height(16.dp))

            NavigationButton(
                to = "open_source_licenses",
                name = stringResource(R.string.open_source_licenses),
                navController = navController,
                independent = true
            )

            Spacer(modifier = Modifier.height(bottomPadding))

        }
        StyledBottomSheet(
            visible = contactBottomSheet.value,
            onDismiss = { contactBottomSheet.value = false },
            backdrop = backdrop
        ) { innerBackdrop, progress ->
            val animatedPadding = lerp(16.dp, 2.dp, progress)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = animatedPadding)
                    .padding(bottom = 16.dp),
            ) {
               Row(
                   modifier = Modifier
                       .fillMaxWidth()
                       .padding(bottom = 16.dp),
                   horizontalArrangement = Arrangement.SpaceBetween,
                   verticalAlignment = Alignment.CenterVertically
               ) {
                   StyledIconButton(
                       icon = "\uDBC0\uDD84",
                       backdrop = innerBackdrop,
                       onClick = { contactBottomSheet.value = false }
                   )
                   Text (
                       text = stringResource(R.string.describe_your_issue),
                       modifier = Modifier
                           .weight(1f)
                           .padding(horizontal = 8.dp),
                       maxLines = 1,
                       overflow = TextOverflow.Ellipsis,
                       style = TextStyle(
                           fontSize = 18.sp,
                           fontFamily = FontFamily(Font(R.font.sf_pro)),
                           fontWeight = FontWeight.Bold,
                           textAlign = TextAlign.Center,
                           color = if (isSystemInDarkTheme()) Color.White else Color.Black
                       )
                   )
                   StyledIconButton(
                       icon = "\uDBC0\uDE1F",
                       backdrop = innerBackdrop,
                       surfaceColor = if (isSystemInDarkTheme()) Color(0xFF0091FF) else Color(0xFF0088FF),
                       iconTint = if (subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty()) Color.White else Color.Gray,
                       enabled = subjectState.text.isNotEmpty() && descriptionState.text.isNotEmpty(),
                       onClick = {
                           val sent = context.openEveryPodsSupportEmail(
                               subject = "EveryPods: ${subjectState.text}",
                               body = "${descriptionState.text}" +
                                   "\n\n----------" +
                                   "\nPhone details:" +
                                   "\nMANUFACTURER: ${Build.MANUFACTURER}" +
                                   "\nMODEL: ${Build.MODEL} (${Build.PRODUCT})" +
                                   "\nDISPLAY_VERSION: ${Build.DISPLAY}" +
                                   "\nID: ${Build.ID} (SDK ${Build.VERSION.SDK_INT_FULL})" +
                                   "\n\nApp details:" +
                                   "\nVERSION: ${BuildConfig.VERSION_NAME}" +
                                   "\nVERSION_CODE: ${BuildConfig.VERSION_CODE}" +
                                   "\nBUILD_TYPE: ${BuildConfig.BUILD_TYPE}"
                           )
                           if (sent) {
                               contactBottomSheet.value = false
                               subjectState.clearText()
                               descriptionState.clearText()
                           }
                       }
                   )
               }

               Spacer(modifier = Modifier.height(8.dp))

               StyledInputField(
                   inputState = subjectState,
                   focusRequester = subjectFocusRequester,
                   placeholder = stringResource(R.string.subject),
               )

               Spacer(modifier = Modifier.height(12.dp))

               StyledInputField(
                   inputState = descriptionState,
                   focusRequester = descriptionFocusRequester,
                   placeholder = stringResource(R.string.describe_your_issue),
                   singleLine = false
               )
            }
        }
    }
}


/** Shared by the current category screen and the legacy settings screen. */
@Composable
internal fun AppLanguageSetting() {
    val context = LocalContext.current
    val textColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    var languageMenuOpen by remember { mutableStateOf(false) }
    val supportedLocaleTags = remember(context) {
        context.resources.getStringArray(R.array.supported_locale_tags).toList()
    }
    val selectedLocale = context.getSystemService(LocaleManager::class.java).applicationLocales
    val activeLocaleTag = selectedLocale.get(0)?.toLanguageTag().orEmpty()
    val activeLanguageName = if (selectedLocale.isEmpty) {
        stringResource(R.string.system_default)
    } else {
        Locale.forLanguageTag(activeLocaleTag).getDisplayName(Locale.forLanguageTag(activeLocaleTag))
    }
    SettingsSection(stringResource(R.string.app_language)) {
    Box {
        TextButton(onClick = { languageMenuOpen = true }) {
            Text(activeLanguageName, modifier = Modifier.fillMaxWidth(), color = textColor, style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily(Font(R.font.sf_pro))))
        }
        DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.system_default)) },
                onClick = {
                    context.getSystemService(LocaleManager::class.java)
                        .applicationLocales = LocaleList.getEmptyLocaleList()
                    languageMenuOpen = false
                }
            )
            supportedLocaleTags.forEach { tag ->
                val locale = Locale.forLanguageTag(tag)
                DropdownMenuItem(
                    text = { Text(locale.getDisplayName(locale)) },
                    onClick = {
                        context.getSystemService(LocaleManager::class.java)
                            .applicationLocales = LocaleList.forLanguageTags(tag)
                        languageMenuOpen = false
                    }
                )
            }
        }
    }
}
}
