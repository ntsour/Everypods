/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.automated.ventures.everypods.R

/** Inline expandable settings always expose a chevron, independent of their state. */
@Composable
fun SettingsExpansionHeader(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val description = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "Settings expansion")
    TextButton(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = description },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f), color = contentColor, style = TextStyle(
                fontFamily = FontFamily(Font(R.font.sf_pro)), fontSize = 16.sp, fontWeight = FontWeight.Normal))
            Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }) {
                val thickness = 2.dp.toPx()
                drawLine(contentColor, Offset(size.width * .2f, size.height * .35f), Offset(size.width * .5f, size.height * .65f), thickness, StrokeCap.Round)
                drawLine(contentColor, Offset(size.width * .5f, size.height * .65f), Offset(size.width * .8f, size.height * .35f), thickness, StrokeCap.Round)
            }
        }
    }
}
