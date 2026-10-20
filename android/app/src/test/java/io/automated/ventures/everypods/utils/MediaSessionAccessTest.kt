package io.automated.ventures.everypods.utils

import android.content.ComponentName
import io.automated.ventures.everypods.services.CallNotifListener
import io.automated.ventures.everypods.services.NotificationAnnouncementService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSessionAccessTest {
    private val installedPackage = "io.automated.ventures.everypods.relay"

    @Test fun notificationAccessForAnOlderPreviewDoesNotAuthorizeTheCurrentApp() {
        val old = ComponentName("io.automated.ventures.everypods.relaydev", NotificationAnnouncementService::class.java.name)
        assertNull(MediaSessionAccess.selectListener(installedPackage, old.flattenToString()))
    }

    @Test fun callListenerCanProvideMediaAccessWithoutEnablingAnnouncements() {
        val component = ComponentName(installedPackage, CallNotifListener::class.java.name)
        assertEquals(component, MediaSessionAccess.selectListener(installedPackage, component.flattenToString()))
    }

    @Test fun announcementListenerAlsoProvidesMediaAccess() {
        val component = ComponentName(installedPackage, NotificationAnnouncementService::class.java.name)
        assertEquals(component, MediaSessionAccess.selectListener(installedPackage, " ${component.flattenToString()}\n"))
    }

    @Test fun anUnrelatedEnabledListenerDoesNotAuthorizeEveryPods() {
        assertNull(MediaSessionAccess.selectListener(installedPackage, "some.other.app/.NotificationService"))
    }

    @Test fun permissionCanBeGrantedAndRevokedWithoutChangingTheAppIdentity() {
        val component = ComponentName(installedPackage, CallNotifListener::class.java.name)
        assertNull(MediaSessionAccess.selectListener(installedPackage, null))
        assertEquals(component, MediaSessionAccess.selectListener(installedPackage, component.flattenToString()))
        assertNull(MediaSessionAccess.selectListener(installedPackage, ""))
    }
}
