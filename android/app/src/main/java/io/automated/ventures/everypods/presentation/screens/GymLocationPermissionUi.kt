package io.automated.ventures.everypods.presentation.screens

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.automation.GymLocationPermission
import io.automated.ventures.everypods.automation.GymLocationPermission.Action

/**
 * Returns the tap action for the next Gym location permission step and renders the
 * explanation dialogs. Nothing is requested until the returned lambda is invoked.
 * [onResult] runs after every system round-trip so callers re-read the grant state.
 */
@Composable
fun rememberGymLocationPermissionAction(onResult: (fineGranted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val asked = remember { booleanArrayOf(false, false) } // [foreground, background] this session
    var dialog by rememberSaveable { mutableStateOf<Action?>(null) }
    val allowAll = GymLocationPermission.allowAllTheTimeLabel(context) ?: stringResource(R.string.gym_perm_allow_all_fallback)

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked[1] = true
        onResult(true)
        // Returned without "Allow all the time": guide them to App info next time they tap.
        if (!granted) dialog = Action.SETTINGS_BACKGROUND
    }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val fine = r[Manifest.permission.ACCESS_FINE_LOCATION] == true
        onResult(fine)
        val next = GymLocationPermission.continueAfterForeground(fine, GymLocationPermission.step(context) == GymLocationPermission.Step.GRANTED)
        if (next != Action.NONE) dialog = next
    }

    fun run(action: Action) {
        when (action) {
            Action.EXPLAIN_FOREGROUND, Action.EXPLAIN_BACKGROUND, Action.SETTINGS_BACKGROUND -> dialog = action
            Action.SETTINGS_FOREGROUND -> context.startActivity(GymLocationPermission.appSettingsIntent(context))
            Action.NONE -> Unit
        }
    }

    dialog?.let { d ->
        val (title, body, confirm) = when (d) {
            Action.EXPLAIN_FOREGROUND -> Triple(
                stringResource(R.string.gym_perm_fg_title), stringResource(R.string.gym_perm_fg_body),
                stringResource(R.string.gym_perm_continue))
            Action.EXPLAIN_BACKGROUND -> Triple(
                stringResource(R.string.gym_perm_bg_dialog_title), stringResource(R.string.gym_perm_bg_dialog_body, allowAll),
                stringResource(R.string.gym_perm_continue))
            else -> Triple(
                stringResource(R.string.gym_perm_bg_dialog_title), stringResource(R.string.gym_perm_settings_body, allowAll),
                stringResource(R.string.gym_auto_grant_background))
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = {
                dialog = null
                when (d) {
                    Action.EXPLAIN_FOREGROUND -> { asked[0] = true; foregroundLauncher.launch(GymLocationPermission.FOREGROUND) }
                    Action.EXPLAIN_BACKGROUND -> backgroundLauncher.launch(GymLocationPermission.BACKGROUND)
                    else -> context.startActivity(GymLocationPermission.appSettingsIntent(context))
                }
            }) { Text(confirm) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.gym_perm_not_now)) } },
        )
    }

    return {
        val activity = context as? Activity
        val rationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        run(GymLocationPermission.nextAction(GymLocationPermission.step(context), asked[0], rationale, asked[1]))
    }
}
