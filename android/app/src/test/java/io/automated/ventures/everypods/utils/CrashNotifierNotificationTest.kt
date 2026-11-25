package io.automated.ventures.everypods.utils

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashNotifierNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    // The real logs dir: the FileProvider only shares files/logs/.
    private val dir by lazy { CrashLog.logsDir(context).apply { listFiles()?.forEach { it.delete() } } }

    @Before fun reset() {
        context.getSharedPreferences("crash_log", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
    }

    @Test fun `a fresh crash report posts one shareable notification`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        CrashLog.writeCrash(dir, System.currentTimeMillis() - 2_000L, "appVersion=test (1)\n", 1, "worker", NullPointerException())

        CrashNotifier.announcePending(context, dir)
        CrashNotifier.announcePending(context, dir) // second start: already announced

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        val text = shadowOf(posted.single()).contentText.toString()
        assertTrue(text, text.startsWith("NullPointerException at "))
        assertNotNull(posted.single().contentIntent)
        assertNotNull(manager.getNotificationChannel(CrashNotifier.CHANNEL_ID))
    }

    @Test fun `nothing is posted or marked without notification permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        CrashLog.writeCrash(dir, System.currentTimeMillis() - 2_000L, "appVersion=test (1)\n", 1, "worker", NullPointerException())

        CrashNotifier.announcePending(context, dir)

        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertEquals(null, context.getSharedPreferences("crash_log", Context.MODE_PRIVATE).getString("last_announced_report", null))
    }
}
