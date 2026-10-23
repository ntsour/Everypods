package io.automated.ventures.everypods.bluetooth.hrm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.automated.ventures.everypods.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeartRateSharingPrefsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun `no saved choice is monitoring only while legacy fallback remains compatible`() {
        assertNull(HeartRateSharingPrefs.configuredMethod(context))
        assertEquals(HeartRateSharingMethod.TICKR, HeartRateSharingPrefs.method(context))
    }

    @Test fun `explicit selection is retained`() {
        HeartRateSharingMethod.entries.forEach { method ->
            HeartRateSharingPrefs.setMethod(context, method)
            assertEquals(if (method == HeartRateSharingMethod.DIRCON && !BuildConfig.DIRCON_ENABLED) null else method,
                HeartRateSharingPrefs.configuredMethod(context))
        }
    }

    @Test fun `monitoring only clears sharing and automation without changing other preferences`() {
        prefs.edit().putBoolean("unrelated", true).commit()
        HeartRateSharingPrefs.setMethod(context, HeartRateSharingMethod.BLUETOOTH)
        HeartRateSharingPrefs.setGymAuto(context, true)
        HeartRateSharingPrefs.clearMethod(context)
        assertNull(HeartRateSharingPrefs.configuredMethod(context))
        assertFalse(HeartRateSharingPrefs.gymAuto(context))
        assertTrue(prefs.getBoolean("unrelated", false))
    }

    @Test fun `invalid persisted selection is treated as unconfigured`() {
        prefs.edit().putString(HeartRateSharingPrefs.METHOD, "unknown").commit()
        assertNull(HeartRateSharingPrefs.configuredMethod(context))
    }
}
