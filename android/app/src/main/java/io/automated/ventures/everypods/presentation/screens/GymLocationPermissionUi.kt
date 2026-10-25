package io.automated.ventures.everypods.presentation.screens

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import io.automated.ventures.everypods.automation.GymLocationPermission
import io.automated.ventures.everypods.automation.GymLocationPermission.Step

/**
 * Returns the tap action for the next Gym location permission step. Nothing is requested
 * until the returned lambda is invoked. [onForegroundResult] receives whether precise
 * location was granted after the runtime dialog.
 */
@Composable
fun rememberGymLocationPermissionAction(onForegroundResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val asked = remember { booleanArrayOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        onForegroundResult(r[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    return {
        when (GymLocationPermission.step(context)) {
            Step.NEED_PRECISE -> {
                val activity = context as? Activity
                val rationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                if (GymLocationPermission.shouldOpenSettingsForForeground(asked[0], rationale)) {
                    context.startActivity(GymLocationPermission.appSettingsIntent(context))
                } else {
                    asked[0] = true
                    launcher.launch(GymLocationPermission.FOREGROUND)
                }
            }
            Step.NEED_BACKGROUND -> context.startActivity(GymLocationPermission.appSettingsIntent(context))
            Step.GRANTED -> Unit
        }
    }
}
