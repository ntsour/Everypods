package io.automated.ventures.everypods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.automated.ventures.everypods.utils.EVERYPODS_SUPPORT_EMAIL
import io.automated.ventures.everypods.utils.openEveryPodsSupportEmail

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun EmailSupportScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val cardColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    
    StyledScaffold(title = "Email Support") { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dest_email_support")
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(topPadding))
            
            Column(Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("Need help? Contact us at $EVERYPODS_SUPPORT_EMAIL.", style = TextStyle(fontSize = 16.sp, fontFamily = SfPro, color = if (dark) Color.White else Color.Black))
                Spacer(Modifier.height(16.dp))
                StyledButton(onClick = {
                    context.openEveryPodsSupportEmail()
                }, backdrop = rememberLayerBackdrop(), modifier = Modifier.fillMaxWidth()) {
                    Text("Send Email", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = SfPro, color = Color.White))
                }
            }
            
            Spacer(Modifier.height(bottomPadding))
        }
    }
}
