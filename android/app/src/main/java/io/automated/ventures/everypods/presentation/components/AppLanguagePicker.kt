/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.automated.ventures.everypods.R
import java.util.Locale

/** Uses the same per-app language preference as Android Settings. */
@Composable
fun AppLanguagePicker() {
    val context = LocalContext.current
    val foreground = if (isSystemInDarkTheme()) Color.White else Color.Black
    var expanded by remember { mutableStateOf(false) }
    val tags = remember(context) { context.resources.getStringArray(R.array.supported_locale_tags).toList() }
    val manager = context.getSystemService(LocaleManager::class.java)
    val selected = manager.applicationLocales
    val selectedLocale = selected.get(0)
    val activeName = selectedLocale?.getDisplayName(selectedLocale) ?: stringResource(R.string.system_default)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.app_language),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = foreground.copy(alpha = .6f)))
        Box {
            TextButton(onClick = { expanded = true }) { Text(activeName) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.system_default)) }, onClick = {
                    manager.applicationLocales = LocaleList.getEmptyLocaleList()
                    expanded = false
                })
                tags.forEach { tag ->
                    val locale = Locale.forLanguageTag(tag)
                    DropdownMenuItem(text = { Text(locale.getDisplayName(locale)) }, onClick = {
                        manager.applicationLocales = LocaleList.forLanguageTags(tag)
                        expanded = false
                    })
                }
            }
        }
    }
}
