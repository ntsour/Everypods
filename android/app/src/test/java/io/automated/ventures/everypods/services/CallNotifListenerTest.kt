package io.automated.ventures.everypods.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import io.automated.ventures.everypods.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotifListenerTest {
    private val listener = CallNotifListener()
    private var current: StatusBarNotification? = null

    @After fun cleanUp() {
        current?.let(listener::onNotificationRemoved)
        CallNotifListener.onMuteStateChanged = null
    }

    private fun post(label: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun action(title: String, request: Int) = Notification.Action.Builder(
            0, title, PendingIntent.getBroadcast(context, request, Intent("test.$request"), PendingIntent.FLAG_IMMUTABLE)
        ).build()
        val notification = Notification.Builder(context, "calls")
            .setCategory(Notification.CATEGORY_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .addAction(action(label, 1))
            .addAction(action("Colgar", 2))
            .build()
        current = StatusBarNotification("com.microsoft.teams", "com.microsoft.teams", -1,
            null, 10317, 1, 0, notification, Process.myUserHandle(), 0L).also { listener.onNotificationPosted(it) }
    }

    @Test fun `Spanish Teams muted call is detected and reconciled on first notification`() {
        val changes = mutableListOf<Boolean>()
        CallNotifListener.onMuteStateChanged = { changes.add(it) }
        post("Reactivar audio")
        assertTrue(CallNotifListener.isVoipNotifActive)
        assertEquals(true, CallNotifListener.isMuteShownByAppNotif())
        assertEquals(listOf(true), changes)
        post("Silenciar")
        assertEquals(false, CallNotifListener.isMuteShownByAppNotif())
        assertEquals(listOf(true, false), changes)
        post("Reactivar audio")
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun `Spanish Teams alternate unmute label is recognized`() {
        post("Reactivar el audio")
        assertEquals(true, CallNotifListener.isMuteShownByAppNotif())
    }

    @Test fun `identical notification updates do not repeat mute callbacks`() {
        val changes = mutableListOf<Boolean>()
        CallNotifListener.onMuteStateChanged = { changes.add(it) }
        post("Reactivar audio")
        post("Reactivar audio")
        assertEquals(listOf(true), changes)
    }
    @Test fun `semantic mute metadata works without translated labels`() {
        assertEquals(true, CallNotifListener.muteStateForAction(Notification.Action.SEMANTIC_ACTION_UNMUTE, null))
        assertEquals(false, CallNotifListener.muteStateForAction(Notification.Action.SEMANTIC_ACTION_MUTE, null))
        assertNull(CallNotifListener.muteStateForAction(Notification.Action.SEMANTIC_ACTION_NONE, null))
    }

    @Test fun `Teams icon names identify both states without translated labels`() {
        assertEquals(true, CallNotifListener.muteStateForAction(0, "icn_callaction_audio_off"))
        assertEquals(false, CallNotifListener.muteStateForAction(0, "icn_callaction_audio"))
        assertNull(CallNotifListener.muteStateForAction(0, "icn_muted_chat"))
    }

    @Test fun `label fallback reads Teams resources for every supported app language`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val tags = app.resources.getStringArray(R.array.supported_locale_tags)
        assertEquals(12, tags.size)
        val teams = mockk<Context>()
        every { teams.resources } returns app.resources
        val seen = mutableSetOf<String>()
        every { teams.createConfigurationContext(any()) } answers {
            val tag = firstArg<Configuration>().locales[0].toLanguageTag()
            seen.add(tag)
            val localized = mockk<Context>()
            val res = mockk<Resources>()
            every { localized.resources } returns res
            every { res.getIdentifier("notifications_call_mute_title", "string", "com.microsoft.teams") } returns 1
            every { res.getIdentifier("notifications_call_unmute_title", "string", "com.microsoft.teams") } returns 2
            every { res.getString(1) } returns "Teams mute resource $tag"
            every { res.getString(2) } returns "Teams unmute resource $tag"
            localized
        }
        val labels = CallNotifListener.readTeamsMuteLabels(teams, "com.microsoft.teams", tags)
        assertEquals(tags.toSet(), seen)
        tags.forEach { tag ->
            assertEquals(false, labels["teams mute resource ${tag.lowercase()}"])
            assertEquals(true, labels["teams unmute resource ${tag.lowercase()}"])
        }
    }

}
