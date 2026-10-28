package io.automated.ventures.everypods.relay

import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayNetworkPermissionTest {
    private class PermissionContext : ContextWrapper(null) {
        var grant = PackageManager.PERMISSION_DENIED
        var checks = 0
        override fun checkSelfPermission(permission: String): Int {
            assertEquals(RelayNetworkPermission.PERMISSION, permission)
            checks++
            return grant
        }
    }

    @Test fun `older Android needs no new LAN grant and never probes an unavailable permission`() {
        val context = PermissionContext()
        for (sdk in 33..36) assertTrue(RelayNetworkPermission.granted(context, sdk))
        assertEquals(0, context.checks)
    }

    @Test fun `Android 17 and later enforce both denial and grant`() {
        val context = PermissionContext()
        assertFalse(RelayNetworkPermission.granted(context, 37))
        assertFalse(RelayNetworkPermission.granted(context, 38))
        context.grant = PackageManager.PERMISSION_GRANTED
        assertTrue(RelayNetworkPermission.granted(context, 37))
    }
}
