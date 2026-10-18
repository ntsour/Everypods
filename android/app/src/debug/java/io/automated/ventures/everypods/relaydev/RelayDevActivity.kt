package io.automated.ventures.everypods.relaydev

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.automated.ventures.everypods.relay.OutputMatcher

/** DEV ONLY (P2) manual relay controls. Replaced by per-peer actions and the active-relay screen in P4. */
// ComponentActivity (not a Fragment host); lint mis-detects an old transitive fragment.
@android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
class RelayDevActivity : ComponentActivity() {
    private var pendingAfterPerms: (() -> Unit)? = null
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pendingAfterPerms?.invoke(); pendingAfterPerms = null }
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, RelaySourceService::class.java)
                .putExtra(RelaySourceService.EXTRA_RESULT, r.resultCode).putExtra(RelaySourceService.EXTRA_TOKEN, data))
        } else RelayDevStatus.source.value = "Capture permission refused"
    }

    private fun withPerms(extra: List<String>, then: () -> Unit) {
        val net = if (Build.VERSION.SDK_INT >= 37) "android.permission.ACCESS_LOCAL_NETWORK" else Manifest.permission.NEARBY_WIFI_DEVICES
        pendingAfterPerms = then
        perms.launch((extra + net + Manifest.permission.POST_NOTIFICATIONS).toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    @Composable
    private fun Screen() {
        val ctx = this
        var host by remember { mutableStateOf(RelayDevPrefs.host(ctx)) }
        var key by remember { mutableStateOf(RelayDevPrefs.testKey(ctx)) }
        var mute by remember { mutableStateOf(RelayDevPrefs.muteSource(ctx)) }
        var picked by remember { mutableStateOf(RelayDevPrefs.outputAddress(ctx)) }
        var refresh by remember { mutableIntStateOf(0) }
        val src by RelayDevStatus.source.collectAsState()
        val rcv by RelayDevStatus.receiver.collectAsState()
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Wi-Fi audio relay (dev only)", style = MaterialTheme.typography.titleLarge)
            Text("Temporary manual setup until P3 pairing. Use the same test key on both phones. For relay-only tests, turn production EveryPods Handover OFF on the Xiaomi or force-stop it there.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it; RelayDevPrefs.setTestKey(ctx, it) }, label = { Text("Test key (dev only, 8+ chars)") }, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Receiver (Pixel)", style = MaterialTheme.typography.titleMedium)
            Text("This phone: ${localIpv4().joinToString()}")
            Text("Play on (Bluetooth outputs only; never the speaker):")
            val outs = remember(refresh) { outputCandidates(ctx).map { it.second }.filter { it.isBluetoothOutput }.distinctBy { it.address } }
            if (outs.isEmpty()) Text("No Bluetooth audio output connected")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(picked == null, { picked = null; RelayDevPrefs.setOutputAddress(ctx, null) })
                Text("Auto (the only connected AirPods)")
            }
            outs.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(picked.equals(c.address, true), { picked = c.address; RelayDevPrefs.setOutputAddress(ctx, c.address) })
                    Text("${c.name} · ${c.address}${if (OutputMatcher.isAppleAudio(c.name)) " · Apple" else ""}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ withPerms(emptyList()) { startForegroundService(Intent(ctx, RelayReceiverService::class.java)) } }) { Text("Start receiver") }
                OutlinedButton({ startService(Intent(ctx, RelayReceiverService::class.java).setAction(RelayReceiverService.ACTION_STOP)) }) { Text("Stop") }
                OutlinedButton({ refresh++ }) { Text("Refresh") }
            }
            Text(rcv, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Source (Xiaomi)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, { host = it; RelayDevPrefs.setHost(ctx, it) }, label = { Text("Receiver IPv4 (shown on the Pixel)") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(mute, { mute = it; RelayDevPrefs.setMuteSource(ctx, it) }); Text("Mute this phone's speaker during relay")
            }
            Text("Android will ask to capture; only app audio is sent, the screen is not recorded.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    withPerms(listOf(Manifest.permission.RECORD_AUDIO)) {
                        consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                    }
                }) { Text("Play through Pixel") }
                OutlinedButton({ startService(Intent(ctx, RelaySourceService::class.java).setAction(RelaySourceService.ACTION_STOP)) }) { Text("Stop") }
            }
            Text(src, style = MaterialTheme.typography.bodySmall)
        }
    }
}
