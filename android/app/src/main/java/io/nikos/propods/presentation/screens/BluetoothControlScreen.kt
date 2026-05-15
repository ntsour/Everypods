package io.nikos.propods.presentation.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.nikos.propods.presentation.components.StyledButton
import io.nikos.propods.presentation.components.StyledScaffold
import io.nikos.propods.presentation.viewmodel.AirPodsViewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BluetoothControlScreen(viewModel: AirPodsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val hasRoot = state.hasRootPermissions
    val DisabledAlpha = 0.38f
    
    StyledScaffold(title = "Bluetooth Control") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp))) {
                Column(Modifier.fillMaxWidth().alpha(if (hasRoot) 1f else DisabledAlpha).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!hasRoot) RootRequiredBanner(dark)
                    StyledButton(onClick = { if (hasRoot) viewModel.disconnect() },
                        backdrop = rememberLayerBackdrop(), isInteractive = hasRoot,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text("Disconnect", style = TextStyle(fontSize = 16.sp,
                            fontWeight = FontWeight.Normal, fontFamily = SfPro, textAlign = TextAlign.Start,
                            color = if (hasRoot) { if (dark) Color(0xFF0091FF) else Color(0xFF0088FF) }
                                   else { if (dark) Color.White.copy(0.35f) else Color.Black.copy(0.35f) }),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
