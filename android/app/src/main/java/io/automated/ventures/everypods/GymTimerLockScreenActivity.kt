package io.automated.ventures.everypods

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.setContent
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import io.automated.ventures.everypods.presentation.screens.GymTimerScreen
import io.automated.ventures.everypods.presentation.theme.EveryPodsTheme

/** The user-requested, lock-screen presentation for a Gym Timer started by gesture. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
class GymTimerLockScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            EveryPodsTheme {
                GymTimerScreen()
            }
        }
    }
}
