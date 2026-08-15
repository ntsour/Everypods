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

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.SelectItem
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.StyledSelectList
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@Composable
fun EqualizerScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val customEq = state.customEq
    val enabled = customEq.isEnabled()

    val recommendedString = stringResource(R.string.eq_recommended)
    val customString      = stringResource(R.string.eq_custom)

    val eqStateOptions = remember(state.customEq) {
        listOf(
            SelectItem(
                name = recommendedString,
                selected = !enabled,
                onClick = { viewModel.setCustomEqEnabled(false) }
            ),
            SelectItem(
                name = customString,
                selected = enabled,
                onClick = { viewModel.setCustomEqEnabled(true) }
            ),
        )
    }

    StyledScaffold(
        title = stringResource(R.string.equalizer)
    ) { spacerHeight ->
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val height   = 200.dp
            val maxOffset = with(LocalDensity.current) { height.toPx() } / 2

            val offsets = remember(state.customEq) {
                listOf(
                    mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.low.toFloat()  / 100)),
                    mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.mid.toFloat()  / 100)),
                    mutableFloatStateOf(lerp(maxOffset, -maxOffset, customEq.high.toFloat() / 100))
                )
            }

            Spacer(modifier = Modifier.height(spacerHeight))

            StyledSelectList(items = eqStateOptions)

            Spacer(modifier = Modifier.height(12.dp))

            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

            Crossfade(enabled) { isCustom ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isCustom) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(backgroundColor, RoundedCornerShape(28.dp))
                        ) {
                            val dashColor =
                                if (isSystemInDarkTheme()) Color(0x80AAAAAA) else Color(0x809D9D9D)

                            // Debounce slider moves to avoid flooding AirPods with AACP packets
                            LaunchedEffect(offsets) {
                                snapshotFlow {
                                    Triple(
                                        offsets[0].floatValue,
                                        offsets[1].floatValue,
                                        offsets[2].floatValue
                                    )
                                }
                                    .debounce(300.milliseconds)
                                    .collect { (lo, mi, hi) ->
                                        val low  = (((-lo / (2 * maxOffset)) + 0.5f) * 100).roundToInt().coerceIn(0, 100)
                                        val mid  = (((-mi / (2 * maxOffset)) + 0.5f) * 100).roundToInt().coerceIn(0, 100)
                                        val high = (((-hi / (2 * maxOffset)) + 0.5f) * 100).roundToInt().coerceIn(0, 100)
                                        viewModel.setCustomEq(low, mid, high)
                                    }
                            }

                            // Canvas EQ curve
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                // Dashed center line
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val dashLength = 8.dp.toPx()
                                    val gapLength  = 6.dp.toPx()
                                    val midY = size.height / 2f
                                    var x = 0f
                                    while (x < size.width) {
                                        drawLine(
                                            color = dashColor,
                                            start = Offset(x, midY),
                                            end   = Offset((x + dashLength).coerceAtMost(size.width), midY),
                                            strokeWidth = 1.dp.toPx()
                                        )
                                        x += dashLength + gapLength
                                    }
                                }

                                // EQ curve
                                val accentColor = Color(0xFF007AFF)
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val w = size.width
                                    val h = size.height
                                    val midY = h / 2f

                                    val x0 = 0f
                                    val x1 = w / 6f
                                    val x2 = w / 2f
                                    val x3 = w * 5f / 6f
                                    val x4 = w

                                    val y1 = midY + offsets[0].floatValue
                                    val y2 = midY + offsets[1].floatValue
                                    val y3 = midY + offsets[2].floatValue

                                    val path = Path().apply {
                                        moveTo(x0, midY)
                                        // cubic bezier through each band point
                                        cubicTo(
                                            x0 + (x1 - x0) * 0.5f, midY,
                                            x1 - (x1 - x0) * 0.5f, y1,
                                            x1, y1
                                        )
                                        cubicTo(
                                            x1 + (x2 - x1) * 0.5f, y1,
                                            x2 - (x2 - x1) * 0.5f, y2,
                                            x2, y2
                                        )
                                        cubicTo(
                                            x2 + (x3 - x2) * 0.5f, y2,
                                            x3 - (x3 - x2) * 0.5f, y3,
                                            x3, y3
                                        )
                                        cubicTo(
                                            x3 + (x4 - x3) * 0.5f, y3,
                                            x4 - (x4 - x3) * 0.5f, midY,
                                            x4, midY
                                        )
                                    }

                                    // Filled gradient area
                                    val fillPath = Path().apply {
                                        addPath(path)
                                        lineTo(x4, h)
                                        lineTo(x0, h)
                                        close()
                                    }
                                    drawPath(
                                        fillPath,
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.25f),
                                                accentColor.copy(alpha = 0f)
                                            ),
                                            startY = 0f,
                                            endY = h
                                        )
                                    )

                                    // Line
                                    drawPath(
                                        path,
                                        color = accentColor,
                                        style = Stroke(width = 2.dp.toPx())
                                    )

                                    // Band dots
                                    listOf(x1 to y1, x2 to y2, x3 to y3).forEach { (x, y) ->
                                        drawCircle(color = accentColor, radius = 5.dp.toPx(), center = Offset(x, y))
                                        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(x, y))
                                    }
                                }
                            }

                            // Sliders row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(height)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                val bandLabels = listOf(
                                    stringResource(R.string.eq_bass),
                                    stringResource(R.string.eq_mid),
                                    stringResource(R.string.eq_treble)
                                )

                                offsets.forEachIndexed { index, offsetState ->
                                    EqBandSlider(
                                        label    = bandLabels[index],
                                        offset   = offsetState.floatValue,
                                        maxOffset = maxOffset,
                                        onOffsetChange = { offsetState.floatValue = it }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EqBandSlider(
    label: String,
    offset: Float,
    maxOffset: Float,
    onOffsetChange: (Float) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val textColor   = if (isDarkTheme) Color.White else Color.Black
    val trackColor  = if (isDarkTheme) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)
    val accentColor = Color(0xFF007AFF)

    // Map offset to a 0..1 percentage for label display
    val pct by remember(offset, maxOffset) {
        derivedStateOf {
            ((-offset / (2 * maxOffset) + 0.5f) * 100).roundToInt().coerceIn(0, 100)
        }
    }

    // Gradient color: blue at extremes, neutral in middle
    val distFromCenter = abs(offset) / maxOffset
    val thumbColor     = lerp(trackColor, accentColor, distFromCenter)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxHeight()
    ) {
        Text(
            text  = "$pct",
            style = TextStyle(
                fontSize   = 12.sp,
                color      = textColor.copy(alpha = 0.6f),
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                textAlign  = TextAlign.Center
            )
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Vertical draggable track
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(32.dp)
                .weight(1f)
        ) {
            // Track
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(trackColor, RoundedCornerShape(2.dp))
            )

            // Filled portion (from center to thumb)
            val fillFraction by remember(offset) { derivedStateOf { -offset / (2 * maxOffset) } }
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(fraction = abs(fillFraction))
                    .align(if (fillFraction >= 0) Alignment.Center else Alignment.BottomCenter)
                    .background(accentColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            )

            // Thumb
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, offset.roundToInt()) }
                    .size(28.dp)
                    .background(thumbColor, CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            val newOffset = (offset + delta).coerceIn(-maxOffset, maxOffset)
                            onOffsetChange(newOffset)
                        }
                    )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text  = label,
            style = TextStyle(
                fontSize   = 12.sp,
                color      = textColor.copy(alpha = 0.6f),
                fontFamily = FontFamily(Font(R.font.sf_pro)),
                textAlign  = TextAlign.Center
            )
        )
    }
}
