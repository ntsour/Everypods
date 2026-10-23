package io.automated.ventures.everypods.utils.peer

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeerIdentityStoreTest {
    @Test
    fun `installation identity persists while session identity rotates per process`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("peer_identity", Context.MODE_PRIVATE).edit().clear().commit()
        PeerIdentityStore.rotateSessionForTesting("00000000-0000-4000-8000-000000000001")
        val first = PeerIdentityStore.load(context)
        assertEquals(first, PeerIdentityStore.load(context))

        PeerIdentityStore.rotateSessionForTesting("00000000-0000-4000-8000-000000000002")
        val second = PeerIdentityStore.load(context)
        assertEquals(first.installationId, second.installationId)
        assertNotEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun `headset identity is stable and address normalization is case insensitive`() {
        val lower = PeerIdentityStore.headsetIdFromAddress("aa:bb:cc:dd:ee:ff")
        assertEquals(lower, PeerIdentityStore.headsetIdFromAddress("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `invalid bluetooth address cannot be used as headset identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityStore.headsetIdFromAddress("AirPods Pro 3")
        }
    }

    @Test
    fun `peer installation identity is bound to the allowlisted Bluetooth address`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = "00000000-0000-4000-8000-000000000011"
        val changed = "00000000-0000-4000-8000-000000000012"
        assertNull(PeerIdentityStore.peerInstallationId(context, "AA:BB:CC:DD:EE:FF"))
        assertFalse(PeerIdentityStore.bindPeerAddress(context, "aa:bb:cc:dd:ee:ff", first))
        assertEquals(first, PeerIdentityStore.peerInstallationId(context, "AA:BB:CC:DD:EE:FF"))
        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityStore.bindPeerAddress(context, "AA:BB:CC:DD:EE:01", first)
        }
        assertTrue(PeerIdentityStore.bindPeerAddress(context, "AA:BB:CC:DD:EE:FF", changed))
        assertEquals(changed, PeerIdentityStore.peerInstallationId(context, "AA:BB:CC:DD:EE:FF"))
    }
}
