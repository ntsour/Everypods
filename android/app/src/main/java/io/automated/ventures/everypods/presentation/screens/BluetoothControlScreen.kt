package io.automated.ventures.everypods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.AirPodsViewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BluetoothControlScreen(viewModel: AirPodsViewModel) {
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    StyledScaffold(title = "Bluetooth Control") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_bluetooth_control")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))

            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyledButton(onClick = { viewModel.disconnect() },
                        backdrop = rememberLayerBackdrop(), isInteractive = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text("Disconnect", style = TextStyle(fontSize = 16.sp,
                            fontWeight = FontWeight.Normal, fontFamily = SfPro, textAlign = TextAlign.Start,
                            color = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }
}
