package io.automated.ventures.everypods.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A group owns its surface; nested controls must render as rows. */
internal val LocalSettingsGroup = compositionLocalOf { false }

@Composable
internal fun SettingsHeading(title: String, modifier: Modifier = Modifier) {
    val foreground = if (isSystemInDarkTheme()) Color.White else Color.Black
    Text(title, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        .semantics { heading() }, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily(Font(io.automated.ventures.everypods.R.font.sf_pro)), color = foreground.copy(alpha = 0.6f)))
}

@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val background = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White
    Column(modifier.fillMaxWidth().background(background, RoundedCornerShape(18.dp))) {
        CompositionLocalProvider(LocalSettingsGroup provides true) { content() }
    }
}

@Composable
internal fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        SettingsHeading(title)
        SettingsGroup(content = content)
    }
}
