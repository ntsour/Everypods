package io.automated.ventures.everypods.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayNetworkSettingsTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val peer = "AA:BB:CC:DD:EE:01"

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun `new peers use automatic selection`() {
        assertEquals("auto", RelayNetworkSettings.load(prefs, peer))
    }

    @Test fun `manual overrides persist independently for each peer across case changes`() {
        RelayNetworkSettings.save(prefs, peer.lowercase(), "hotspot")
        RelayNetworkSettings.save(prefs, "AA:BB:CC:DD:EE:02", "wifi")
        assertEquals("hotspot", RelayNetworkSettings.load(prefs, peer))
        assertEquals("wifi", RelayNetworkSettings.load(prefs, "AA:BB:CC:DD:EE:02"))
    }

    @Test fun `negotiation does not mutate automatic policy or another peers override`() {
        RelayNetworkSettings.save(prefs, peer, "auto")
        assertEquals("hotspot", RelayNetworkSettings.receiverMode(RelayNetworkSettings.load(prefs, peer), "hotspot"))
        assertEquals("auto", RelayNetworkSettings.load(prefs, peer))
    }

    @Test fun `receiver override is honored only for an automatic sender`() {
        assertEquals("hotspot", RelayNetworkSettings.receiverMode("auto", "hotspot"))
        assertEquals("wifi", RelayNetworkSettings.receiverMode("auto", "wifi"))
        assertEquals("wifi", RelayNetworkSettings.receiverMode("wifi", "hotspot"))
        assertEquals("hotspot", RelayNetworkSettings.receiverMode("hotspot", "wifi"))
        assertEquals("auto", RelayNetworkSettings.receiverMode("auto", "auto"))
    }

    @Test fun `unknown stored modes recover to automatic instead of breaking route requests`() {
        prefs.edit().putString("audio_relay_network_$peer", "obsolete").commit()
        assertEquals("auto", RelayNetworkSettings.load(prefs, peer))
    }
}
