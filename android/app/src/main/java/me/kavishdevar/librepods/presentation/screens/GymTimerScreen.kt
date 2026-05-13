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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.presentation.components.StyledSlider
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.utils.GymModePrefs
import me.kavishdevar.librepods.utils.GymTimer
import me.kavishdevar.librepods.utils.TtsEngine

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GymTimerScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val backdrop = rememberLayerBackdrop()

    var elapsedMs by remember { mutableLongStateOf(GymTimer.elapsedMs()) }
    var timerState by remember { mutableStateOf(GymTimer.state()) }
    var timerMode by remember { mutableStateOf(GymTimer.mode()) }
    var voiceEnabled by remember { mutableStateOf(GymModePrefs.voiceAnnouncementsEnabled(context)) }
    var laps by remember { mutableStateOf(GymTimer.laps()) }
    var countdownRemainingMs by remember { mutableLongStateOf(GymTimer.countdownRemainingMs()) }
    var countdownDurationSec by remember { mutableIntStateOf((GymTimer.getCountdownDurationMs() / 1000).toInt()) }
    var hiitWorkSec by remember { mutableIntStateOf((GymTimer.getHiitWorkMs() / 1000).toInt()) }
    var hiitRestSec by remember { mutableIntStateOf((GymTimer.getHiitRestMs() / 1000).toInt()) }
    var hiitRounds by remember { mutableIntStateOf(GymTimer.getHiitRounds()) }

    DisposableEffect(Unit) {
        val listener = {
            elapsedMs = GymTimer.elapsedMs()
            timerState = GymTimer.state()
            timerMode = GymTimer.mode()
            laps = GymTimer.laps()
            countdownRemainingMs = GymTimer.countdownRemainingMs()
            countdownDurationSec = (GymTimer.getCountdownDurationMs() / 1000).toInt()
            hiitWorkSec = (GymTimer.getHiitWorkMs() / 1000).toInt()
            hiitRestSec = (GymTimer.getHiitRestMs() / 1000).toInt()
            hiitRounds = GymTimer.getHiitRounds()
        }
        GymTimer.addListener(listener)
        onDispose { GymTimer.removeListener(listener) }
    }

    StyledScaffold(title = "Gym Timer") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            // Mode selector
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Timer Mode", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                Spacer(Modifier.height(8.dp))
                ModeSelectorRow(timerMode, timerState, dark) { selectedMode ->
                    // Allow mode change when idle or when paused (timer finished)
                    if (timerState == GymTimer.State.IDLE || timerState == GymTimer.State.PAUSED) {
                        GymTimer.setMode(selectedMode)
                        timerMode = selectedMode
                    }
                }
            }

            // Timer display
            Column(
                Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (timerMode) {
                    GymTimer.Mode.COUNTDOWN -> {
                        val totalSec = countdownRemainingMs / 1000
                        val mins = totalSec / 60
                        val secs = totalSec % 60
                        val centis = (countdownRemainingMs % 1000) / 10
                        Text(
                            String.format("%02d:%02d.%02d", mins, secs, centis),
                            style = TextStyle(
                                fontSize = 56.sp, fontWeight = FontWeight.Light,
                                fontFamily = SfPro, color = if (dark) Color.White else Color.Black,
                                textAlign = TextAlign.Center
                            )
                        )
                        if (timerState == GymTimer.State.IDLE) {
                            Spacer(Modifier.height(4.dp))
                            Text("Duration: ${countdownDurationSec / 60}m ${countdownDurationSec % 60}s", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro,
                                color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
                        }
                    }
                    GymTimer.Mode.STOPWATCH -> {
                        val totalSec = elapsedMs / 1000
                        val mins = totalSec / 60
                        val secs = totalSec % 60
                        val centis = (elapsedMs % 1000) / 10
                        Text(
                            String.format("%02d:%02d.%02d", mins, secs, centis),
                            style = TextStyle(
                                fontSize = 56.sp, fontWeight = FontWeight.Light,
                                fontFamily = SfPro, color = if (dark) Color.White else Color.Black,
                                textAlign = TextAlign.Center
                            )
                        )
                        if (laps.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            val lastLap = laps.last()
                            Text("Lap ${lastLap.number}: ${lastLap.splitMs / 1000}s",
                                style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
                                    color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
                        }
                    }
                    GymTimer.Mode.HIIT -> {
                        val (phase, round, remainingInPhase) = GymTimer.hiitPhaseInfo()
                        val totalSec = remainingInPhase / 1000
                        val mins = totalSec / 60
                        val secs = totalSec % 60
                        val phaseColor = when (phase) {
                            GymTimer.Phase.WORK -> Color(0xFF34C759)
                            GymTimer.Phase.REST -> Color(0xFF0A84FF)
                        }
                        Text(
                            phase.name,
                            style = TextStyle(
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                fontFamily = SfPro, color = phaseColor,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            String.format("%02d:%02d", mins, secs),
                            style = TextStyle(
                                fontSize = 56.sp, fontWeight = FontWeight.Light,
                                fontFamily = SfPro, color = if (dark) Color.White else Color.Black,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Round $round / ${GymTimer.getHiitRounds()}",
                            style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
                                color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f))
                        )
                    }
                }
            }

            // Controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StyledButton(
                    onClick = {
                        when (timerState) {
                            GymTimer.State.IDLE -> GymTimer.start()
                            GymTimer.State.RUNNING -> GymTimer.pause()
                            GymTimer.State.PAUSED -> GymTimer.start()
                        }
                    },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) {
                    val label = when (timerState) {
                        GymTimer.State.IDLE -> "START"
                        GymTimer.State.RUNNING -> "PAUSE"
                        GymTimer.State.PAUSED -> "RESUME"
                    }
                    Text(label, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                }
                StyledButton(
                    onClick = { GymTimer.reset() },
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) {
                    Text("RESET", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                }
            }

            // LAP button (only for stopwatch)
            if (timerMode == GymTimer.Mode.STOPWATCH) {
                StyledButton(
                    onClick = { GymTimer.lap() },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("LAP", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                }
            }

            // Laps list (only for stopwatch)
            if (timerMode == GymTimer.Mode.STOPWATCH && laps.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("Laps", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                    Spacer(Modifier.height(8.dp))
                    laps.reversed().forEachIndexed { idx, lap ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Lap ${lap.number}", style = bodyStyle(dark))
                            Text("${lap.splitMs / 1000}s / ${lap.elapsedMs / 1000}s",
                                style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
                                    color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
                        }
                        if (idx < laps.size - 1) {
                            HorizontalDivider(color = Color(0x30888888), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }

            // Mode-specific settings
            if (timerState == GymTimer.State.IDLE) {
                when (timerMode) {
                    GymTimer.Mode.COUNTDOWN -> CountdownSettings(
                        dark = dark,
                        durationSec = countdownDurationSec,
                        onDurationChange = { newDurationSec ->
                            countdownDurationSec = newDurationSec
                            GymTimer.setCountdownDurationMs(newDurationSec * 1000L)
                        }
                    )
                    GymTimer.Mode.HIIT -> HiitSettings(
                        dark = dark,
                        workSec = hiitWorkSec,
                        onWorkChange = { newWorkSec ->
                            hiitWorkSec = newWorkSec
                            GymTimer.setHiitWorkMs(newWorkSec * 1000L)
                        },
                        restSec = hiitRestSec,
                        onRestChange = { newRestSec ->
                            hiitRestSec = newRestSec
                            GymTimer.setHiitRestMs(newRestSec * 1000L)
                        },
                        rounds = hiitRounds,
                        onRoundsChange = { newRounds ->
                            hiitRounds = newRounds
                            GymTimer.setHiitRounds(newRounds)
                        }
                    )
                    else -> {}
                }
            }

            // Voice toggle
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                StyledToggle(
                    label = "Voice announcements",
                    description = "Announce voice timer events",
                    checked = voiceEnabled,
                    onCheckedChange = {
                        voiceEnabled = it
                        GymModePrefs.setVoiceAnnouncementsEnabled(context, it)
                    },
                    independent = true
                )
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun ModeSelectorRow(
    currentMode: GymTimer.Mode,
    timerState: GymTimer.State,
    dark: Boolean,
    onSelect: (GymTimer.Mode) -> Unit
) {
    val modes = listOf(
        GymTimer.Mode.COUNTDOWN to "Countdown",
        GymTimer.Mode.STOPWATCH to "Stopwatch",
        GymTimer.Mode.HIIT to "HIIT",
    )
    val textColor = if (dark) Color.White else Color.Black

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, label) ->
            val selected = mode == currentMode
            val bg = if (selected) Color(0xFF0A84FF) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
            val fg = if (selected) Color.White else textColor
            // Allow mode change when idle or when paused (timer finished)
            val enabled = timerState == GymTimer.State.IDLE || timerState == GymTimer.State.PAUSED

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bg, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled
                    ) { onSelect(mode) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    fontFamily = SfPro, color = if (enabled) fg else fg.copy(0.4f)))
            }
        }
    }
}

@Composable
private fun CountdownSettings(
    dark: Boolean,
    durationSec: Int,
    onDurationChange: (Int) -> Unit
) {
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text("Duration", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
        Spacer(Modifier.height(8.dp))
        val minLabel = "${durationSec / 60}m ${durationSec % 60}s"
        Text(minLabel, style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
            color = if (dark) Color.White.copy(0.6f) else Color.Black.copy(0.6f)))
        Spacer(Modifier.height(12.dp))
        
        // Quick seconds (sub-minute timers)
        Column(Modifier.fillMaxWidth()) {
            Text("Quick", style = bodyStyle(dark))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 15, 20, 30, 45, 60).forEach { s ->
                    val selected = durationSec == s
                    val bg = if (selected) Color(0xFF0A84FF) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
                    val fg = if (selected) Color.White else if (dark) Color.White else Color.Black
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(bg, RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onDurationChange(s)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${s}s", style = TextStyle(fontSize = 12.sp, fontFamily = SfPro, color = fg))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // Minutes
        Column(Modifier.fillMaxWidth()) {
            Text("Minutes", style = bodyStyle(dark))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5, 10).forEach { m ->
                    val s = m * 60
                    val selected = durationSec == s
                    val bg = if (selected) Color(0xFF0A84FF) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
                    val fg = if (selected) Color.White else if (dark) Color.White else Color.Black
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(bg, RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onDurationChange(s)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${m}m", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = fg))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        
        // Seconds
        Column(Modifier.fillMaxWidth()) {
            Text("Seconds", style = bodyStyle(dark))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 45).forEach { s ->
                    val total = (durationSec / 60) * 60 + s
                    val selected = durationSec == total
                    val bg = if (selected) Color(0xFF0A84FF) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
                    val fg = if (selected) Color.White else if (dark) Color.White else Color.Black
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(bg, RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onDurationChange(total)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${s}s", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = fg))
                    }
                }
            }
        }
    }
}

@Composable
private fun HiitSettings(
    dark: Boolean,
    workSec: Int,
    onWorkChange: (Int) -> Unit,
    restSec: Int,
    onRestChange: (Int) -> Unit,
    rounds: Int,
    onRoundsChange: (Int) -> Unit
) {
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text("HIIT Configuration", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
        Spacer(Modifier.height(12.dp))

        // Work
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Work", style = bodyStyle(dark), modifier = Modifier.weight(1f))
            Text("$workSec s", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
                color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(20, 30, 40, 45, 60).forEach { s ->
                val selected = workSec == s
                val bg = if (selected) Color(0xFF34C759) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
                val fg = if (selected) Color.White else if (dark) Color.White else Color.Black
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(bg, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onWorkChange(s)
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${s}s", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = fg))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        MenuDivider()
        Spacer(Modifier.height(12.dp))

        // Rest
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rest", style = bodyStyle(dark), modifier = Modifier.weight(1f))
            Text("$restSec s", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
                color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 15, 20, 30, 45).forEach { s ->
                val selected = restSec == s
                val bg = if (selected) Color(0xFF0A84FF) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
                val fg = if (selected) Color.White else if (dark) Color.White else Color.Black
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(bg, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onRestChange(s)
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${s}s", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = fg))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        MenuDivider()
        Spacer(Modifier.height(12.dp))

        // Rounds
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rounds", style = bodyStyle(dark), modifier = Modifier.weight(1f))
            Text("$rounds", style = TextStyle(fontSize = 14.sp, fontFamily = SfPro,
                color = if (dark) Color.White.copy(0.5f) else Color.Black.copy(0.5f)))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(4, 6, 8, 10, 12).forEach { r ->
                val selected = rounds == r
                val bg = if (selected) Color(0xFFFF9500) else if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
                val fg = if (selected) Color.White else if (dark) Color.White else Color.Black
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(bg, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onRoundsChange(r)
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$r", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = fg))
                }
            }
        }
    }
}
