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



@file:OptIn(ExperimentalEncodingApi::class)

package io.automated.ventures.everypods.presentation.screens

import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import io.automated.ventures.everypods.presentation.components.SettingsExpansionHeader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledIconButton
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.components.SettingsSection
import io.automated.ventures.everypods.presentation.screens.MenuNavRow
import io.automated.ventures.everypods.presentation.components.StyledToggle
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import io.automated.ventures.everypods.services.ServiceManager
import io.automated.ventures.everypods.utils.HeadTracking
import io.automated.ventures.everypods.utils.HeadGesturePrefs
import io.automated.ventures.everypods.data.HeadGesture
import io.automated.ventures.everypods.presentation.components.*
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@ExperimentalHazeMaterialsApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadTrackingScreen(
    viewModel: AirPodsViewModel,
    navController: NavController,
    initialGym: Boolean = false,
    anchor: String? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val (prefs, settings) = rememberHeadGestureSettings()
    val service = ServiceManager.getService()
    var advancedExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var gymProfile by androidx.compose.runtime.saveable.rememberSaveable(initialGym) { mutableStateOf(initialGym) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(io.automated.ventures.everypods.utils.GestureDetector.Status()) }
    val enabled = state.isPremium && state.capabilities.contains(io.automated.ventures.everypods.data.Capability.HEAD_GESTURES)
    val dark = isSystemInDarkTheme()
    val scrollState = rememberScrollState()
    val registry = remember { AnchorRegistry() }
    val targetTop = with(androidx.compose.ui.platform.LocalDensity.current) { 112.dp.roundToPx() }
    LaunchedEffect(anchor) {
        if (anchor != null) {
            val y = snapshotFlow { registry.positions[anchor] }.filterNotNull().first()
            scrollState.animateScrollTo((scrollState.value + y - targetTop).coerceAtLeast(0))
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, service, advancedExpanded) {
        val tools = io.automated.ventures.everypods.utils.HeadTrackingToolsLifecycle(
            diagnosticsExpanded = advancedExpanded,
            startDiagnostics = { service?.startHeadTracking() },
            stopDiagnostics = { service?.stopHeadTracking() },
            cancelTest = { service?.setHeadGestureTest(false) },
        )
        lifecycleOwner.lifecycle.addObserver(tools)
        onDispose { lifecycleOwner.lifecycle.removeObserver(tools); tools.close() }
    }
    LaunchedEffect(service) {
        service?.headGestureStatus?.collect { status = it }
    }
    LaunchedEffect(service) {
        service?.headGestureTesting?.collect { testing = it }
    }
    var gestureLog by remember { mutableStateOf(emptyList<io.automated.ventures.everypods.services.AirPodsService.HeadGestureLogEntry>()) }
    LaunchedEffect(service) {
        service?.headGestureLog?.collect { gestureLog = it }
    }
    CompositionLocalProvider(LocalAnchorRegistry provides registry, LocalActiveAnchor provides anchor) {
    StyledScaffold(title = stringResource(R.string.hg_configuration)) { topPadding, hazeState, bottomPadding ->
        Column(Modifier.fillMaxSize().testTag("dest_head_tracking").hazeSource(hazeState)
            .verticalScroll(scrollState).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(topPadding))
            Text(stringResource(R.string.hg_tuning_help), style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            AnchorWrapper("gym_gesture_tuning") { modifier -> SettingsSection(stringResource(R.string.hg_tuning_profile), modifier) {
                StyledToggle(label = stringResource(R.string.hg_separate_gym), checked = settings.separateGymTuning,
                    enabled = enabled, onCheckedChange = { HeadGesturePrefs.setGymTuning(prefs, it) }, independent = true)
                GestureSettingPicker(stringResource(R.string.hg_tuning_profile), gymProfile,
                    listOf(false to stringResource(R.string.hg_normal), true to stringResource(R.string.hg_gym_controls)), enabled) {
                    service?.setHeadGestureTest(false)
                    gymProfile = it
                }
            } }
            val editGym = gymProfile && settings.separateGymTuning
            if (gymProfile && !settings.separateGymTuning) Text(stringResource(R.string.hg_gym_shared), style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HeadGesture.entries.forEach { gesture ->
                val tuning = settings.tuningFor(gesture, gymProfile)
                SettingsSection(headGestureLabel(gesture)) {
                    AnchorWrapper("${gesture.name.lowercase()}_amplitude") { modifier -> Column(modifier) {
                        GestureSettingPicker(stringResource(R.string.hg_amplitude), tuning.amplitude,
                            (200..2000 step 100).map { it to it.toString() }, enabled && (!gymProfile || editGym)) {
                            HeadGesturePrefs.setTuning(prefs, gesture, editGym, tuning.copy(amplitude = it))
                        }
                    } }
                    AnchorWrapper("${gesture.name.lowercase()}_repetitions") { modifier -> Column(modifier) {
                        GestureSettingPicker(stringResource(R.string.hg_repetitions), tuning.repetitions,
                            (1..4).map { it to it.toString() }, enabled && (!gymProfile || editGym)) {
                            HeadGesturePrefs.setTuning(prefs, gesture, editGym, tuning.copy(repetitions = it))
                        }
                    } }
                    AnchorWrapper("${gesture.name.lowercase()}_direction") { modifier -> Column(modifier) {
                        GestureSettingPicker(stringResource(R.string.hg_axis_dominance), tuning.axisDominancePercent,
                            (150..400 step 50).map { it to stringResource(R.string.hg_axis_ratio, it / 100f) }, enabled && (!gymProfile || editGym)) {
                            HeadGesturePrefs.setTuning(prefs, gesture, editGym, tuning.copy(axisDominancePercent = it))
                        }
                        Text(stringResource(R.string.hg_direction_help), style = gestureSupportingStyle(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    } }
                    AnchorWrapper("${gesture.name.lowercase()}_speed") { modifier -> Column(modifier) {
                        GestureSettingPicker(stringResource(R.string.hg_max_phase_gap), tuning.maxPhaseGapMs,
                            (200..1500 step 100).map { it to stringResource(R.string.hg_milliseconds, it) }, enabled && (!gymProfile || editGym)) {
                            HeadGesturePrefs.setTuning(prefs, gesture, editGym, tuning.copy(maxPhaseGapMs = it))
                        }
                        GestureSettingPicker(stringResource(R.string.hg_max_duration), tuning.maxDurationMs,
                            (500..6000 step 250).map { it to stringResource(R.string.hg_milliseconds, it) }, enabled && (!gymProfile || editGym)) {
                            HeadGesturePrefs.setTuning(prefs, gesture, editGym, tuning.copy(maxDurationMs = it))
                        }
                        Text(stringResource(R.string.hg_speed_help), style = gestureSupportingStyle(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    } }
                    AnchorWrapper("${gesture.name.lowercase()}_cooldown") { modifier -> Column(modifier) {
                        GestureSettingPicker(stringResource(R.string.hg_cooldown), tuning.cooldownMs,
                            (500..5000 step 500).map { it to stringResource(R.string.hg_seconds, it / 1000f) }, enabled && (!gymProfile || editGym)) {
                            HeadGesturePrefs.setTuning(prefs, gesture, editGym, tuning.copy(cooldownMs = it))
                        }
                    } }
                }
            }
            GestureSettingAction(stringResource(R.string.hg_reset_tuning), enabled = enabled && (!gymProfile || editGym)) {
                HeadGesturePrefs.resetTuning(prefs, editGym)
            }
            val advancedAnchor = anchor in setOf("gesture_test", "head_diagnostics")
            LaunchedEffect(advancedAnchor) { if (advancedAnchor) advancedExpanded = true }
            SettingsExpansionHeader(label = stringResource(R.string.hg_advanced_diagnostics), expanded = advancedExpanded,
                onToggle = {
                    advancedExpanded = !advancedExpanded
                    if (!advancedExpanded) service?.setHeadGestureTest(false)
                }, contentColor = if (dark) Color.White else Color.Black)
            if (advancedExpanded) {
            AnchorWrapper("gesture_test") { modifier -> SettingsSection(stringResource(R.string.ui_test_head_gestures), modifier) {
                Text(stringResource(R.string.hg_test_help), style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                GestureSettingAction(stringResource(if (testing) R.string.hg_stop_test else R.string.ui_test_head_gestures),
                    enabled = enabled && state.aacpAvailable && service != null) { service?.setHeadGestureTest(!testing, gymProfile) }
                if (testing) {
                    HeadGesture.entries.forEach { gesture ->
                        val progress = status.progress(gesture)
                        Text(stringResource(R.string.hg_test_progress, headGestureLabel(gesture), progress.amplitude,
                            progress.repetitions, settings.tuningFor(gesture, gymProfile).repetitions), style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    Text(stringResource(R.string.hg_cooldown_remaining, status.cooldownMs / 1000f), style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            }
            // One live view for both testing and everyday use: signals, thresholds and results.
            AnchorWrapper("head_diagnostics") { modifier -> Column(modifier) {
                SettingsHeading(stringResource(R.string.ui_velocity))
                AccelerationPlot(HeadGesture.entries.associateWith { settings.tuningFor(it, gymProfile).amplitude },
                    gestureLog.firstOrNull { it.outcome.accepted })
                GestureLogSection(gestureLog) { settings.tuningFor(it, gymProfile).axisDominancePercent }
                SettingsHeading(stringResource(R.string.ui_head_orientation))
                HeadVisualization()
            } }
            if (io.automated.ventures.everypods.BuildConfig.DEBUG) MotionRecorderSection()
            }
            Spacer(Modifier.height(if (anchor != null) androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp else bottomPadding))
        }
    }
    }
}

/** Debug builds only: raw payload capture for gesture research. Text is intentionally untranslated. */
@Composable
private fun MotionRecorderSection() {
    val context = LocalContext.current
    val recorder = io.automated.ventures.everypods.utils.HeadMotionRecorder
    val recording by recorder.state.collectAsState()
    SettingsSection("Motion recorder (debug)") {
        Text("Saves raw sensor payloads with the selected label. Recording continues in the background while head tracking is running.",
            style = gestureSupportingStyle(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        GestureSettingPicker("Label", recording.label, recorder.LABELS.map { it to it }) { recorder.setLabel(it) }
        GestureSettingAction(if (recording.recording) "Stop recording" else "Start recording") {
            if (recording.recording) recorder.stop()
            else recorder.start(java.io.File(context.filesDir, "head-motion"), android.os.SystemClock.elapsedRealtime())
        }
        recording.file?.let {
            Text("${recording.rows} rows → ${it.name}", style = gestureSupportingStyle(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun HeadVisualization() {
    val context = LocalContext.current
    val orientation by HeadTracking.orientation.collectAsState()
    val darkTheme = isSystemInDarkTheme()
    val backgroundColor = if (darkTheme) Color(0xFF1C1C1E) else Color.White
    val strokeColor = if (darkTheme) Color.White else Color.Black

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
.testTag("dest_head_tracking"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                val width = size.width
                val height = size.height
                val center = Offset(width / 2, height / 2)
                val faceRadius = height * 0.35f

                val pitch = Math.toRadians(orientation.pitch.toDouble())
                val yaw = Math.toRadians(orientation.yaw.toDouble())

                val cosY = cos(yaw).toFloat()
                val sinY = sin(yaw).toFloat()
                val cosP = cos(pitch).toFloat()
                val sinP = sin(pitch).toFloat()

                fun rotate3D(point: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
                    val (x, y, z) = point
                    val x1 = x * cosY - z * sinY
                    val z1 = x * sinY + z * cosY

                    val y2 = y * cosP - z1 * sinP
                    val z2 = y * sinP + z1 * cosP

                    return Triple(x1, y2, z2)
                }

                fun project(point: Triple<Float, Float, Float>): Pair<Float, Float> {
                    val (x, y, z) = point
                    val scale = 1f + (z / width)
                    return Pair(center.x + x * scale, center.y + y * scale)
                }

                val earWidth = height * 0.08f
                val earHeight = height * 0.2f
                val earOffsetX = height * 0.4f
                val earOffsetY = 0f
                val earZ = 0f

                for (xSign in listOf(-1f, 1f)) {
                    val rotated = rotate3D(Triple(earOffsetX * xSign, earOffsetY, earZ))
                    val (earX, earY) = project(rotated)
                    drawRoundRect(
                        color = strokeColor,
                        topLeft = Offset(earX - earWidth/2, earY - earHeight/2),
                        size = Size(earWidth, earHeight),
                        cornerRadius = CornerRadius(earWidth/2),
                        style = Stroke(width = 4.dp.toPx())
                    )
                }

                val spherePath = Path()
                val firstPoint = project(rotate3D(Triple(faceRadius, 0f, 0f)))
                spherePath.moveTo(firstPoint.first, firstPoint.second)

                for (i in 1..32) {
                    val angle = (i * 2 * Math.PI / 32).toFloat()
                    val point = project(rotate3D(Triple(
                        cos(angle) * faceRadius,
                        sin(angle) * faceRadius,
                        0f
                    )))
                    spherePath.lineTo(point.first, point.second)
                }
                spherePath.close()

                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        style = Paint.Style.FILL
                        shader = RadialGradient(
                            center.x + sinY * faceRadius * 0.3f,
                            center.y - sinP * faceRadius * 0.3f,
                            faceRadius * 1.4f,
                            intArrayOf(
                                backgroundColor.copy(alpha = 1f).toArgb(),
                                backgroundColor.copy(alpha = 0.95f).toArgb(),
                                backgroundColor.copy(alpha = 0.9f).toArgb(),
                                backgroundColor.copy(alpha = 0.8f).toArgb(),
                                backgroundColor.copy(alpha = 0.7f).toArgb()
                            ),
                            floatArrayOf(0.3f, 0.5f, 0.7f, 0.8f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    drawPath(spherePath.asAndroidPath(), paint)

                    val highlightPaint = Paint().apply {
                        style = Paint.Style.FILL
                        shader = RadialGradient(
                            center.x - faceRadius * 0.4f - sinY * faceRadius * 0.5f,
                            center.y - faceRadius * 0.4f - sinP * faceRadius * 0.5f,
                            faceRadius * 0.9f,
                            intArrayOf(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.argb(100, 255, 255, 255),
                                android.graphics.Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 0.3f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        alpha = if (darkTheme) 30 else 60
                    }
                    drawPath(spherePath.asAndroidPath(), highlightPaint)

                    val secondaryHighlightPaint = Paint().apply {
                        style = Paint.Style.FILL
                        shader = RadialGradient(
                            center.x + faceRadius * 0.3f + sinY * faceRadius * 0.3f,
                            center.y + faceRadius * 0.3f - sinP * faceRadius * 0.3f,
                            faceRadius * 0.7f,
                            intArrayOf(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        alpha = if (darkTheme) 15 else 30
                    }
                    drawPath(spherePath.asAndroidPath(), secondaryHighlightPaint)

                    val shadowPaint = Paint().apply {
                        style = Paint.Style.FILL
                        shader = RadialGradient(
                            center.x + sinY * faceRadius * 0.5f,
                            center.y - sinP * faceRadius * 0.5f,
                            faceRadius * 1.1f,
                            intArrayOf(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.BLACK
                            ),
                            floatArrayOf(0.7f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        alpha = if (darkTheme) 40 else 20
                    }
                    drawPath(spherePath.asAndroidPath(), shadowPaint)
                }

                drawPath(
                    path = spherePath,
                    color = strokeColor,
                    style = Stroke(width = 4.dp.toPx())
                )

                val smileRadius = faceRadius * 0.5f
                val smileStartAngle = -340f
                val smileSweepAngle = 140f
                val smileOffsetY = faceRadius * 0.1f

                val smilePath = Path()
                for (i in 0..32) {
                    val angle = Math.toRadians(smileStartAngle + (smileSweepAngle * i / 32.0))
                    val x = cos(angle.toFloat()) * smileRadius
                    val y = sin(angle.toFloat()) * smileRadius + smileOffsetY

                    val rotated = rotate3D(Triple(x, y, 0f))
                    val projected = project(rotated)

                    if (i == 0) {
                        smilePath.moveTo(projected.first, projected.second)
                    } else {
                        smilePath.lineTo(projected.first, projected.second)
                    }
                }

                drawPath(
                    path = smilePath,
                    color = strokeColor,
                    style = Stroke(
                        width = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                val eyeOffsetX = height * 0.15f
                val eyeOffsetY = height * 0.1f
                val eyeLength = height * 0.08f

                for (xSign in listOf(-1f, 1f)) {
                    val rotated = rotate3D(Triple(eyeOffsetX * xSign, -eyeOffsetY, 0f))
                    val (eyeX, eyeY) = project(rotated)
                    drawLine(
                        color = strokeColor,
                        start = Offset(eyeX, eyeY - eyeLength/2),
                        end = Offset(eyeX, eyeY + eyeLength/2),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.RIGHT
                        typeface = Typeface.create(
                            "SF Pro",
                            Typeface.NORMAL
                        )
                    }

                    val pitch = orientation.pitch.toInt()
                    val yaw = orientation.yaw.toInt()
                    val text = "Pitch: ${pitch}° Yaw: ${yaw}°"

                    drawText(
                        text,
                        width - 8.dp.toPx(),
                        height - 8.dp.toPx(),
                        paint
                    )
                }
            }
        }
    }
}

// Signal lines keep the original blue and red. Gestures have their own colours for
// threshold lines, detection markers and log titles, because shake and tilt share a signal.
private val horizontalColor = Color(0xFF007AFF)
private val verticalColor = Color(0xFFFF3B30)
private val turnColor = Color(0xFF8E8E93)
private val gestureColors = mapOf(
    HeadGesture.SHAKE to Color(0xFFFF9500), HeadGesture.NOD to Color(0xFFFF3B30), HeadGesture.TILT to Color(0xFF34C759),
)

/** The check a completed sequence came closest to failing: measured ratio and the ratio it needed. */
private fun straightness(outcome: io.automated.ventures.everypods.utils.GestureDetector.Outcome, strictnessPercent: Int): Pair<Float, Float> {
    val needed = strictnessPercent / 100f
    fun ratio(own: Int, other: Int) = if (other <= 0) 99.9f else (own.toFloat() / other).coerceAtMost(99.9f)
    val checks = when (outcome.gesture) {
        HeadGesture.NOD -> listOf(ratio(outcome.verticalRange, outcome.horizontalRange) to needed)
        HeadGesture.SHAKE -> listOf(
            ratio(maxOf(outcome.horizontalRange, outcome.turnRange * 100 / io.automated.ventures.everypods.utils.GestureDetector.TURN_TO_HORIZONTAL_PERCENT), outcome.verticalRange) to needed,
            ratio(outcome.turnRange, outcome.horizontalRange) to 0.5f)
        HeadGesture.TILT -> listOf(ratio(outcome.horizontalRange, outcome.verticalRange) to needed, ratio(outcome.horizontalRange, outcome.turnRange) to needed)
    }
    return checks.minBy { it.first / it.second }
}

@Composable
private fun GestureLogSection(
    log: List<io.automated.ventures.everypods.services.AirPodsService.HeadGestureLogEntry>,
    strictnessPercent: (HeadGesture) -> Int,
) {
    val time = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT) }
    SettingsSection(stringResource(R.string.hg_gesture_log)) {
        if (log.isEmpty()) Text(stringResource(R.string.hg_log_empty), style = gestureSupportingStyle(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        log.forEach { entry ->
            val outcome = entry.outcome
            val (measured, needed) = straightness(outcome, strictnessPercent(outcome.gesture))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(stringResource(if (outcome.accepted) R.string.hg_log_detected else R.string.hg_log_rejected,
                    time.format(java.util.Date(entry.wallTimeMs)), headGestureLabel(outcome.gesture)),
                    style = gestureLabelStyle().copy(color = if (outcome.accepted) gestureColors.getValue(outcome.gesture) else gestureLabelStyle().color))
                Text(stringResource(R.string.hg_log_ranges, outcome.verticalRange, outcome.horizontalRange, outcome.turnRange) +
                    "\n" + stringResource(R.string.hg_log_ratio, measured, needed), style = gestureSupportingStyle())
            }
        }
    }
}

@Composable
private fun AccelerationPlot(
    thresholds: Map<HeadGesture, Int>,
    lastDetection: io.automated.ventures.everypods.services.AirPodsService.HeadGestureLogEntry?,
) {
    data class PlotPoint(val horizontal: Float, val vertical: Float, val turn: Float, val detected: HeadGesture? = null)
    val acceleration by HeadTracking.acceleration.collectAsState()
    val horizontalLabel = stringResource(R.string.ui_horizontal)
    val verticalLabel = stringResource(R.string.ui_vertical)
    val turnLabel = stringResource(R.string.hg_axis_turn)
    val maxPoints = 100
    val points = remember { mutableStateListOf<PlotPoint>() }
    val darkTheme = isSystemInDarkTheme()

    var maxAbs by remember { mutableFloatStateOf(1000f) }

    LaunchedEffect(acceleration) {
        points.add(PlotPoint(acceleration.horizontal, acceleration.vertical,
            acceleration.lateral * io.automated.ventures.everypods.utils.GestureDetector.TURN_TO_HORIZONTAL_PERCENT / 100))
        if (points.size > maxPoints) {
            points.removeAt(0)
        }

        val currentMax = points.maxOf { maxOf(abs(it.horizontal), abs(it.vertical), abs(it.turn)) }
        maxAbs = maxOf(currentMax * 1.2f, (thresholds.values.maxOrNull() ?: 0) * 1.2f, 1000f)
    }
    // Mark the sample at which each gesture was recognised.
    LaunchedEffect(lastDetection) {
        if (lastDetection != null && points.isNotEmpty()) {
            points[points.lastIndex] = points.last().copy(detected = lastDetection.outcome.gesture)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) Color(0xFF1C1C1E) else Color.White
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val width = size.width
                val height = size.height
                val xScale = width / maxPoints
                val yScale = (height - 40.dp.toPx()) / (maxAbs * 2)
                val zeroY = height / 2

                val gridColor = if (darkTheme) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)

                for (i in 0..maxPoints step 10) {
                    val x = i * xScale
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val gridStep = maxAbs / 4
                for (value in (-maxAbs.toInt()..maxAbs.toInt()) step gridStep.toInt()) {
                    val y = zeroY - value * yScale
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                drawLine(
                    color = if (darkTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.3f),
                    start = Offset(0f, zeroY),
                    end = Offset(width, zeroY),
                    strokeWidth = 1.5f.dp.toPx()
                )

                if (points.size > 1) {
                    // Dashed lines show each gesture's amplitude threshold in its own colour.
                    thresholds.forEach { (gesture, amplitude) ->
                        for (sign in listOf(-1, 1)) {
                            val y = zeroY - sign * amplitude * yScale
                            drawLine(gestureColors.getValue(gesture).copy(alpha = 0.45f), Offset(0f, y), Offset(width, y),
                                strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                        }
                    }
                    points.forEachIndexed { index, point ->
                        point.detected?.let {
                            drawLine(gestureColors.getValue(it), Offset(index * xScale, 0f), Offset(index * xScale, height), strokeWidth = 3.dp.toPx())
                        }
                    }
                    for (i in 0 until points.size - 1) {
                        val x1 = i * xScale
                        val x2 = (i + 1) * xScale
                        listOf<Pair<Color, (PlotPoint) -> Float>>(
                            horizontalColor to { it.horizontal },
                            verticalColor to { it.vertical },
                            turnColor to { it.turn },
                        ).forEach { (color, value) ->
                            drawLine(color, Offset(x1, zeroY - value(points[i]) * yScale),
                                Offset(x2, zeroY - value(points[i + 1]) * yScale), strokeWidth = 2.dp.toPx())
                        }
                    }
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.RIGHT
                    }

                    drawText("${maxAbs.toInt()}", 30.dp.toPx(), 20.dp.toPx(), paint)
                    drawText("0", 30.dp.toPx(), height/2, paint)
                    drawText("-${maxAbs.toInt()}", 30.dp.toPx(), height - 10.dp.toPx(), paint)
                }

                val legendY = 15.dp.toPx()
                val textOffsetY = legendY + 5.dp.toPx() / 2

                drawCircle(turnColor, 5.dp.toPx(), Offset(width - 215.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText(turnLabel, width - 205.dp.toPx(), textOffsetY, paint)
                }

                drawCircle(Color(0xFF007AFF), 5.dp.toPx(), Offset(width - 150.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText(horizontalLabel, width - 140.dp.toPx(), textOffsetY, paint)
                }

                drawCircle(Color(0xFFFF3B30), 5.dp.toPx(), Offset(width - 70.dp.toPx(), legendY))
                drawContext.canvas.nativeCanvas.apply {
                    val paint = Paint().apply {
                        color = if (darkTheme) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        textSize = 12.sp.toPx()
                        textAlign = Paint.Align.LEFT
                    }
                    drawText(verticalLabel, width - 60.dp.toPx(), textOffsetY, paint)
                }
            }
        }
    }
}
