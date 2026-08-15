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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.bluetooth.ProximityScanner
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledIconButton
import io.automated.ventures.everypods.presentation.components.StyledScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximityFinderScreen(navController: NavController) {
    val context = LocalContext.current
    val scanner = remember { ProximityScanner(context.applicationContext) }
    val state by scanner.state.collectAsState()

    DisposableEffect(scanner) {
        scanner.start()
        onDispose {
            scanner.stop()
        }
    }

    StyledScaffold(
        title = stringResource(R.string.find_nearby),
        actionButtons = listOf(
            { backdrop ->
                StyledIconButton(
                    onClick = {
                        if (state.isScanning) scanner.stop() else scanner.start()
                    },
                    icon = if (state.isScanning) "􀊃" else "􀊄",
                    backdrop = backdrop
                )
            },
            { backdrop ->
                StyledIconButton(
                    onClick = scanner::clear,
                    icon = "􀅉",
                    backdrop = backdrop
                )
            }
        )
    ) { topPadding, hazeState, bottomPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_proximity_finder")
                .hazeSource(hazeState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "top") { Spacer(modifier = Modifier.height(topPadding)) }
            item(key = "radar") {
                RadarCard(
                    device = state.focusedDevice,
                    isScanning = state.isScanning,
                    error = state.error
                )
            }
            item(key = "calibration") {
                CalibrationCard(
                    hasOwnerFingerprint = state.hasOwnerFingerprint,
                    isCalibrating = state.isCalibrating,
                    calibrationProgress = state.calibrationProgress,
                    calibrationMessage = state.calibrationMessage,
                    onStartCalibration = scanner::startCalibration,
                    onCancelCalibration = scanner::cancelCalibration,
                    onForget = scanner::clearOwnerFingerprint
                )
            }
            item(key = "controls") {
                FinderControls(
                    isScanning = state.isScanning,
                    hasDevices = state.devices.isNotEmpty(),
                    feedbackMode = state.feedbackMode,
                    onScanToggle = { if (state.isScanning) scanner.stop() else scanner.start() },
                    onFocusStrongest = scanner::focusStrongest,
                    onToggleFeedback = {
                        scanner.setFeedbackMode(
                            if (state.feedbackMode == ProximityScanner.FeedbackMode.SOUND)
                                ProximityScanner.FeedbackMode.VIBRATION
                            else
                                ProximityScanner.FeedbackMode.SOUND
                        )
                    }
                )
            }
            if (state.devices.isEmpty()) {
                item(key = "empty") {
                    EmptyFinderCard(isScanning = state.isScanning)
                }
            } else {
                item(key = "section") {
                    SectionLabel(text = stringResource(R.string.nearby_signals))
                }
                items(
                    items = state.devices,
                    key = { it.id }
                ) { device ->
                    CandidateCard(
                        device = device,
                        focused = state.focusedId == device.id,
                        onFocus = { scanner.focus(device.id) }
                    )
                }
            }
            item(key = "bottom") { Spacer(modifier = Modifier.height(bottomPadding)) }
        }
    }
}

@Composable
private fun RadarCard(
    device: ProximityScanner.ProximityDevice?,
    isScanning: Boolean,
    error: String?
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = signalColor(device?.score ?: 0)
    val sfPro = FontFamily(Font(R.font.sf_pro))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RadarCanvas(
            score = device?.score ?: 0,
            hasSignal = device != null,
            accentColor = accentColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = error ?: device?.proximityLabel ?: if (isScanning) {
                stringResource(R.string.scanning)
            } else {
                stringResource(R.string.scan_paused)
            },
            style = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (error == null) textColor else Color(0xFFFF453A),
                fontFamily = sfPro
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = device?.displayName ?: stringResource(R.string.no_signal_selected),
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = textColor.copy(alpha = 0.68f),
                fontFamily = sfPro
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(16.dp))

        SignalBar(score = device?.score ?: 0, color = accentColor)

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SignalMetric("Score", "${device?.score ?: 0}%", textColor)
            SignalMetric("RSSI", device?.let { "${it.smoothedRssi.toInt()} dBm" } ?: "-", textColor)
            SignalMetric("Seen", device?.seenCount?.toString() ?: "0", textColor)
        }
    }
}

@Composable
private fun RadarCanvas(
    score: Int,
    hasSignal: Boolean,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "proximity pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val ringColor = if (isSystemInDarkTheme()) Color.White else Color.Black

    Canvas(
        modifier = Modifier.size(220.dp)
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2.2f

        for (index in 1..4) {
            drawCircle(
                color = ringColor.copy(alpha = 0.08f),
                radius = maxRadius * index / 4f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        if (hasSignal) {
            drawCircle(
                color = accentColor.copy(alpha = 0.16f * (1f - pulse)),
                radius = maxRadius * pulse,
                center = center,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            val normalized = score / 100f
            val dotDistance = maxRadius * (1f - normalized) * 0.78f
            val dot = Offset(center.x, center.y - dotDistance)

            drawCircle(
                color = accentColor.copy(alpha = 0.18f),
                radius = 18.dp.toPx(),
                center = dot
            )
            drawCircle(
                color = accentColor,
                radius = 7.dp.toPx(),
                center = dot
            )
        }
    }
}

@Composable
private fun FinderControls(
    isScanning: Boolean,
    hasDevices: Boolean,
    feedbackMode: ProximityScanner.FeedbackMode,
    onScanToggle: () -> Unit,
    onFocusStrongest: () -> Unit,
    onToggleFeedback: () -> Unit
) {
    val sfPro = FontFamily(Font(R.font.sf_pro))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StyledButton(
                onClick = onScanToggle,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp),
                maxScale = 0.05f
            ) {
                Text(
                    text = if (isScanning) stringResource(R.string.pause_scan) else stringResource(R.string.start_scan),
                    style = buttonTextStyle()
                )
            }

            StyledButton(
                onClick = onFocusStrongest,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp),
                enabled = hasDevices,
                maxScale = 0.05f
            ) {
                Text(
                    text = stringResource(R.string.focus_strongest),
                    style = buttonTextStyle(enabled = hasDevices)
                )
            }
        }

        // Sound / Vibration toggle
        val isSound = feedbackMode == ProximityScanner.FeedbackMode.SOUND
        StyledButton(
            onClick = onToggleFeedback,
            backdrop = rememberLayerBackdrop(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            maxScale = 0.05f
        ) {
            Text(
                text = if (isSound) "􀊡  Feedback: Sound" else "􀝗  Feedback: Vibration",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSystemInDarkTheme()) Color.White else Color.Black,
                    fontFamily = sfPro
                )
            )
        }
    }
}

@Composable
private fun CandidateCard(
    device: ProximityScanner.ProximityDevice,
    focused: Boolean,
    onFocus: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = signalColor(device.score)
    val sfPro = FontFamily(Font(R.font.sf_pro))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "􀋦",
                    style = TextStyle(
                        fontSize = 20.sp,
                        color = accentColor,
                        fontFamily = sfPro
                    )
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = TextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        fontFamily = sfPro
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${device.ownerLabel} - ${device.kind.label} - ${device.proximityLabel}",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.62f),
                        fontFamily = sfPro
                    )
                )
            }

            Text(
                text = "${device.score}%",
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    fontFamily = sfPro
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        SignalBar(score = device.score, color = accentColor)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SignalMetric("RSSI", "${device.smoothedRssi.toInt()} dBm", textColor)
            SignalMetric("Match", "${device.ownerScore}%", textColor)
            SignalMetric("Last", ageText(device.lastSeen), textColor)
        }

        if (device.hints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = device.hints.joinToString(" / "),
                style = TextStyle(
                    fontSize = 12.sp,
                    color = textColor.copy(alpha = 0.58f),
                    fontFamily = sfPro
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color(0x40888888))
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.address,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = textColor.copy(alpha = 0.54f),
                        fontFamily = sfPro
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                device.appleManufacturerHex?.let {
                    Text(
                        text = it,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = textColor.copy(alpha = 0.38f),
                            fontFamily = sfPro
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            StyledButton(
                onClick = onFocus,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier.heightIn(min = 42.dp),
                isInteractive = !focused,
                surfaceColor = if (focused) accentColor.copy(alpha = 0.22f) else Color.Unspecified,
                maxScale = 0.04f
            ) {
                Text(
                    text = if (focused) stringResource(R.string.focused) else stringResource(R.string.focus),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (focused) accentColor else textColor,
                        fontFamily = sfPro
                    )
                )
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    hasOwnerFingerprint: Boolean,
    isCalibrating: Boolean,
    calibrationProgress: Float,
    calibrationMessage: String?,
    onStartCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    onForget: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accentColor = if (hasOwnerFingerprint) Color(0xFF30D158) else Color(0xFF0088FF)
    val sfPro = FontFamily(Font(R.font.sf_pro))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasOwnerFingerprint) "􀁣" else "􀋦",
                    style = TextStyle(
                        fontSize = 18.sp,
                        color = accentColor,
                        fontFamily = sfPro
                    )
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasOwnerFingerprint) "Your AirPods learned" else "Learn your AirPods",
                    style = TextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        fontFamily = sfPro
                    )
                )
                Text(
                    text = calibrationMessage ?: "Open the case beside the phone, then calibrate once.",
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.62f),
                        fontFamily = sfPro
                    )
                )
            }
        }

        if (isCalibrating) {
            Spacer(modifier = Modifier.height(14.dp))
            SignalBar(score = (calibrationProgress * 100f).toInt(), color = accentColor)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StyledButton(
                onClick = if (isCalibrating) onCancelCalibration else onStartCalibration,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                surfaceColor = if (isCalibrating) Color(0xFFFF9F0A).copy(alpha = 0.24f) else Color.Unspecified,
                maxScale = 0.05f
            ) {
                Text(
                    text = if (isCalibrating) "Cancel" else "Calibrate",
                    style = buttonTextStyle()
                )
            }

            StyledButton(
                onClick = onForget,
                backdrop = rememberLayerBackdrop(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                enabled = hasOwnerFingerprint && !isCalibrating,
                maxScale = 0.05f
            ) {
                Text(
                    text = "Forget",
                    style = buttonTextStyle(enabled = hasOwnerFingerprint && !isCalibrating)
                )
            }
        }
    }
}

@Composable
private fun EmptyFinderCard(isScanning: Boolean) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val sfPro = FontFamily(Font(R.font.sf_pro))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(28.dp))
            .padding(18.dp)
    ) {
        Text(
            text = if (isScanning) stringResource(R.string.no_airpods_signals_yet) else stringResource(R.string.scan_is_paused),
            style = TextStyle(
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                fontFamily = sfPro
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.find_nearby_empty_description),
            style = TextStyle(
                fontSize = 13.sp,
                color = textColor.copy(alpha = 0.62f),
                fontFamily = sfPro
            )
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f),
            fontFamily = FontFamily(Font(R.font.sf_pro))
        ),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 2.dp)
    )
}

@Composable
private fun SignalMetric(label: String, value: String, textColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                color = textColor.copy(alpha = 0.48f),
                fontFamily = FontFamily(Font(R.font.sf_pro))
            )
        )
    }
}

@Composable
private fun SignalBar(score: Int, color: Color) {
    val trackColor = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(trackColor, RoundedCornerShape(8.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((score / 100f).coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun buttonTextStyle(enabled: Boolean = true): TextStyle {
    return TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = (if (isSystemInDarkTheme()) Color.White else Color.Black).copy(alpha = if (enabled) 1f else 0.4f),
        fontFamily = FontFamily(Font(R.font.sf_pro))
    )
}

private fun signalColor(score: Int): Color {
    return when {
        score >= 85 -> Color(0xFF30D158)
        score >= 65 -> Color(0xFF64D2FF)
        score >= 45 -> Color(0xFFFFD60A)
        score >= 25 -> Color(0xFFFF9F0A)
        else -> Color(0xFFFF453A)
    }
}

private fun ageText(lastSeen: Long): String {
    val seconds = ((System.currentTimeMillis() - lastSeen) / 1000L).coerceAtLeast(0L)
    return if (seconds == 0L) "now" else "${seconds}s"
}


