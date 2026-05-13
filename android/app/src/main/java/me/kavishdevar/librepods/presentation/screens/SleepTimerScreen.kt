package me.kavishdevar.librepods.presentation.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledScaffold
import me.kavishdevar.librepods.utils.SleepTimer
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SleepTimerScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (dark) Color.White else Color.Black
    val sleepRemainingMs = remember { mutableStateOf(SleepTimer.remainingMs(context)) }
    
    DisposableEffect(Unit) {
        val l: () -> Unit = { sleepRemainingMs.value = SleepTimer.remainingMs(context) }
        SleepTimer.addListener(l); onDispose { SleepTimer.removeListener(l) }
    }
    
    StyledScaffold(title = "Sleep Timer") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 90).forEach { mins ->
                        StyledButton(onClick = { SleepTimer.start(context, mins * 60_000L); sleepRemainingMs.value = SleepTimer.remainingMs(context) },
                            backdrop = rememberLayerBackdrop(), modifier = Modifier.weight(1f).heightIn(min = 40.dp)) {
                            Text("${mins}m", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = textColor))
                        }
                    }
                }
                if (sleepRemainingMs.value > 0L) {
                    val mins = (sleepRemainingMs.value / 60_000L).toInt()
                    val secs = ((sleepRemainingMs.value % 60_000L) / 1000L).toInt()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("⏱ ${mins}m ${secs}s remaining", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor.copy(0.7f)))
                        StyledButton(onClick = { SleepTimer.cancel(context); sleepRemainingMs.value = 0L }, backdrop = rememberLayerBackdrop(), modifier = Modifier.heightIn(min = 36.dp)) {
                            Text("Cancel", style = TextStyle(fontSize = 13.sp, fontFamily = SfPro, color = textColor))
                        }
                    }
                } else {
                    Text("No timer running", style = captionStyle(dark))
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
