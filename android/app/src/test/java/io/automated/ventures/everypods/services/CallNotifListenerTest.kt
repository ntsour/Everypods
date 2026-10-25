package io.automated.ventures.everypods.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import io.automated.ventures.everypods.R
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotifListenerTest {
    private val serviceController = Robolectric.buildService(CallNotifListener::class.java).create()
    private val listener = serviceController.get()
    private val posted = mutableListOf<StatusBarNotification>()
    private val receivedActions = mutableListOf<String>()
    private var nextRequestCode = 1
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            receivedActions += intent.getStringExtra("name").orEmpty()
        }
    }

    @Before fun registerReceiver() {
        context.registerReceiver(receiver, IntentFilter("test.call.action"), Context.RECEIVER_EXPORTED)
    }

    @After fun cleanUp() {
        posted.asReversed().forEach(listener::onNotificationRemoved)
        posted.clear()
        CallNotifListener.onMuteStateChanged = null
        CallNotifListener.onCallStateChanged = null
        runCatching { context.unregisterReceiver(receiver) }
        serviceController.destroy()
    }

    private fun pending(name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        nextRequestCode++,
        Intent("test.call.action").putExtra("name", name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(title: String, name: String = title): Notification.Action =
        Notification.Action.Builder(0, title, pending(name)).build()

    private fun post(
        packageName: String,
        id: Int,
        postTime: Long,
        notification: Notification,
    ): StatusBarNotification = StatusBarNotification(
        packageName,
        packageName,
        id,
        null,
        10317,
        1,
        0,
        notification,
        Process.myUserHandle(),
        postTime,
    ).also {
        posted += it
        listener.onNotificationPosted(it)
    }

    private fun notification(vararg actions: Notification.Action): Notification =
        Notification.Builder(context, "calls")
            .setCategory(Notification.CATEGORY_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .apply { actions.forEach(::addAction) }
            .build()

    private fun incomingCallStyle(answerName: String, declineName: String): Notification {
        val person = Person.Builder().setName("Caller").build()
        val answer = pending(answerName)
        val decline = pending(declineName)
        val extras = Bundle().apply {
            putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
            putParcelable(Notification.EXTRA_ANSWER_INTENT, answer)
            putParcelable(Notification.EXTRA_DECLINE_INTENT, decline)
        }
        return Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setStyle(Notification.CallStyle.forIncomingCall(
                person,
                decline,
                answer,
            ))
            .addExtras(extras)
            .build()
    }

    private fun ongoingCallStyle(hangUpName: String): Notification {
        val person = Person.Builder().setName("Caller").build()
        val hangUp = pending(hangUpName)
        val extras = Bundle().apply {
            putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_ONGOING)
            putParcelable(Notification.EXTRA_HANG_UP_INTENT, hangUp)
        }
        return Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setStyle(Notification.CallStyle.forOngoingCall(person, hangUp))
            .addAction(action("Mute", "mute"))
            .addExtras(extras)
            .build()
    }

    private fun callTypeNotification(callType: Int, vararg actions: Notification.Action): Notification {
        val extras = Bundle().apply { putInt(Notification.EXTRA_CALL_TYPE, callType) }
        return Notification.Builder(context, "calls")
            .setCategory(Notification.CATEGORY_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .apply { actions.forEach(::addAction) }
            .addExtras(extras)
            .build()
    }

    private fun dispatches(name: String): Boolean {
        val result = if (name == "answer") CallNotifListener.answerCall()
        else CallNotifListener.hangUp()
        shadowOf(Looper.getMainLooper()).idle()
        return result
    }

    private fun normalizedLabel(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    @Test fun `Spanish Teams muted call is detected and reconciled on first notification`() {
        val changes = mutableListOf<Boolean>()
        CallNotifListener.onMuteStateChanged = { changes.add(it) }
        post("com.microsoft.teams", -1, 1, notification(action("Reactivar audio"), action("Colgar")))
        assertTrue(CallNotifListener.isVoipNotifActive)
        assertEquals(true, CallNotifListener.isMuteShownByAppNotif())
        assertEquals(listOf(true), changes)
        post("com.microsoft.teams", -1, 2, notification(action("Silenciar"), action("Colgar")))
        assertEquals(false, CallNotifListener.isMuteShownByAppNotif())
        assertEquals(listOf(true, false), changes)
        post("com.microsoft.teams", -1, 3, notification(action("Reactivar audio"), action("Colgar")))
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun `Spanish Teams alternate unmute label is recognized`() {
        post("com.microsoft.teams", -1, 1, notification(action("Reactivar el audio"), action("Colgar")))
        assertEquals(true, CallNotifListener.isMuteShownByAppNotif())
    }

    @Test fun `identical notification updates do not repeat mute callbacks`() {
        val changes = mutableListOf<Boolean>()
        CallNotifListener.onMuteStateChanged = { changes.add(it) }
        post("com.microsoft.teams", -1, 1, notification(action("Reactivar audio"), action("Colgar")))
        post("com.microsoft.teams", -1, 2, notification(action("Reactivar audio"), action("Colgar")))
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
        val tags = context.resources.getStringArray(R.array.supported_locale_tags)
        assertEquals(12, tags.size)
        val teams = mockk<Context>()
        every { teams.resources } returns context.resources
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
            assertEquals("labels=$labels", false, labels["teams mute resource ${normalizedLabel(tag)}"])
            assertEquals(true, labels["teams unmute resource ${normalizedLabel(tag)}"])
        }
    }

    @Test fun `Teams call action labels read vendor resources for every supported language`() {
        val tags = context.resources.getStringArray(R.array.supported_locale_tags)
        val teams = mockk<Context>()
        every { teams.resources } returns context.resources
        val seen = mutableSetOf<String>()
        every { teams.createConfigurationContext(any()) } answers {
            val tag = firstArg<Configuration>().locales[0].toLanguageTag()
            seen += tag
            val localized = mockk<Context>()
            val res = mockk<Resources>()
            every { localized.resources } returns res
            every { res.getIdentifier("notifications_pre_call_answer_audio", "string", "com.microsoft.teams") } returns 1
            every { res.getIdentifier("notifications_pre_call_decline", "string", "com.microsoft.teams") } returns 2
            every { res.getString(1) } returns "Answer action $tag"
            every { res.getString(2) } returns "Decline action $tag"
            localized
        }
        val labels = CallNotifListener.readTeamsCallLabels(teams, "com.microsoft.teams", tags)
        assertEquals(tags.toSet(), seen)
        tags.forEach { tag ->
            assertEquals("answer", labels["answer action ${normalizedLabel(tag)}"])
            assertEquals("decline", labels["decline action ${normalizedLabel(tag)}"])
        }
    }

    @Test fun `Portuguese Atender is an answer and whole word matching does not call it end`() {
        post("com.viber.voip", 20, 1, notification(action("Atender", "portuguese-answer"), action("Recusar")))
        assertTrue(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.isVoipNotifActive)
        assertTrue(dispatches("answer"))
        assertEquals(listOf("portuguese-answer"), receivedActions)
    }

    @Test fun `CallStyle type outranks a misleading custom mute action and its array position`() {
        val incoming = incomingCallStyle("style-answer", "style-decline")
        val withCustomMute = Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setStyle(Notification.CallStyle.forIncomingCall(
                Person.Builder().setName("Caller").build(),
                pending("style-decline"),
                pending("style-answer"),
            ))
            .addAction(action("Mute", "custom-mute"))
            .addExtras(Bundle().apply {
                putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
                putParcelable(Notification.EXTRA_ANSWER_INTENT, pending("style-answer"))
                putParcelable(Notification.EXTRA_DECLINE_INTENT, pending("style-decline"))
            })
            .build()
        assertNotNull(incoming)
        post("com.viber.voip", 21, 2, withCustomMute)
        assertTrue(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.isVoipNotifActive)
        assertTrue(dispatches("answer"))
        assertEquals(listOf("style-answer"), receivedActions)
    }

    @Test fun `unknown action between decline and answer is never guessed as answer`() {
        val notification = callTypeNotification(
            Notification.CallStyle.CALL_TYPE_INCOMING,
            action("Decline", "decline"),
            action("Video", "custom-video"),
            action("Join", "unknown-answer"),
        )
        post("com.whatsapp", 22, 3, notification)
        assertTrue(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.answerCall())
        assertTrue(receivedActions.isEmpty())
    }

    @Test fun `removing one call releases only its own actions and state`() {
        val older = post("com.viber.voip", 23, 10, incomingCallStyle("older-answer", "older-decline"))
        val newer = post("com.whatsapp", 24, 20, incomingCallStyle("newer-answer", "newer-decline"))
        listener.onNotificationRemoved(older)
        assertTrue(CallNotifListener.isVoipNotifRinging)
        assertTrue(dispatches("answer"))
        assertEquals(listOf("newer-answer"), receivedActions)
        listener.onNotificationRemoved(newer)
        assertFalse(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.answerCall())
    }

    @Test fun `active and incoming notifications retain separate stage-owned actions`() {
        val active = post("com.viber.voip", 25, 10, ongoingCallStyle("active-hangup"))
        val ringing = post("com.whatsapp", 26, 20, incomingCallStyle("incoming-answer", "incoming-decline"))
        assertTrue(CallNotifListener.isVoipNotifActive)
        assertTrue(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.setMuted(true))
        assertTrue(CallNotifListener.declineCall())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("incoming-decline"), receivedActions)
        listener.onNotificationRemoved(ringing)
        assertFalse(CallNotifListener.isVoipNotifRinging)
        assertTrue(dispatches("hangup"))
        assertEquals(listOf("incoming-decline", "active-hangup"), receivedActions)
        listener.onNotificationRemoved(active)
        assertFalse(CallNotifListener.isVoipNotifActive)
    }

    @Test fun `duplicate answer dispatch is suppressed until notification transition or removal`() {
        val incoming = post("com.viber.voip", 27, 30, incomingCallStyle("answer-once", "decline-once"))
        assertTrue(CallNotifListener.answerCall())
        assertTrue(CallNotifListener.hasPendingCallAction())
        assertFalse(CallNotifListener.answerCall())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("answer-once"), receivedActions)
        listener.onNotificationRemoved(incoming)
        assertFalse(CallNotifListener.hasPendingCallAction())
    }

    @Test fun `Telecom answer fallback waits for the notification action and claims only the sole call`() {
        val incoming = post("com.whatsapp", 29, 50, incomingCallStyle("answer", "decline"))
        assertTrue(CallNotifListener.answerCall())
        val startedAt = android.os.SystemClock.elapsedRealtime()

        assertFalse(CallNotifListener.claimTelecomAnswerFallback(
            activeCallObserved = false, nowElapsedMs = startedAt + CallNotifListener.ANSWER_TELECOM_FALLBACK_DELAY_MS - 1,
        ))
        assertTrue(CallNotifListener.claimTelecomAnswerFallback(
            activeCallObserved = false, nowElapsedMs = startedAt + CallNotifListener.ANSWER_TELECOM_FALLBACK_DELAY_MS,
        ))
        assertFalse(CallNotifListener.answerCall())
        assertTrue(CallNotifListener.hasPendingCallAction())

        listener.onNotificationRemoved(incoming)
        assertFalse(CallNotifListener.hasPendingCallAction())
    }

    @Test fun `Telecom answer fallback does not target multiple incoming calls`() {
        post("com.viber.voip", 30, 60, incomingCallStyle("viber-answer", "viber-decline"))
        post("com.whatsapp", 31, 70, incomingCallStyle("whatsapp-answer", "whatsapp-decline"))
        assertTrue(CallNotifListener.answerCall())
        val fallbackTime = android.os.SystemClock.elapsedRealtime() +
            CallNotifListener.ANSWER_TELECOM_FALLBACK_DELAY_MS

        assertFalse(CallNotifListener.claimTelecomAnswerFallback(activeCallObserved = false, nowElapsedMs = fallbackTime))
        assertTrue(CallNotifListener.hasPendingCallAction())
    }

    @Test fun `connected system call blocks fallback while app notification is still incoming`() {
        post("com.whatsapp", 32, 80, incomingCallStyle("answer", "decline"))
        assertTrue(CallNotifListener.answerCall())
        val fallbackTime = android.os.SystemClock.elapsedRealtime() +
            CallNotifListener.ANSWER_TELECOM_FALLBACK_DELAY_MS

        assertFalse(CallNotifListener.claimTelecomAnswerFallback(
            activeCallObserved = true, nowElapsedMs = fallbackTime,
        ))
        assertTrue(CallNotifListener.hasPendingCallAction())
        assertFalse(CallNotifListener.answerCall())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("answer"), receivedActions)
    }

    @Test fun `replacing the answer intent invalidates fallback ownership even with the same key`() {
        post("com.whatsapp", 33, 90, incomingCallStyle("old-answer", "old-decline"))
        assertTrue(CallNotifListener.answerCall())
        val fallbackTime = android.os.SystemClock.elapsedRealtime() +
            CallNotifListener.ANSWER_TELECOM_FALLBACK_DELAY_MS
        post("com.whatsapp", 33, 91, incomingCallStyle("new-answer", "new-decline"))

        assertFalse(CallNotifListener.hasPendingCallAction())
        assertFalse(CallNotifListener.claimTelecomAnswerFallback(
            activeCallObserved = false, nowElapsedMs = fallbackTime,
        ))
        assertTrue(CallNotifListener.answerCall())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("old-answer", "new-answer"), receivedActions)
    }

    @Test fun `cancelled answer intent releases pending action and preserves decline`() {
        val notification = incomingCallStyle("cancelled-answer", "decline")
        notification.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)!!.cancel()
        post("com.viber.voip", 34, 100, notification)

        assertFalse(CallNotifListener.answerCall())
        assertFalse(CallNotifListener.hasPendingCallAction())
        assertTrue(CallNotifListener.declineCall())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("decline"), receivedActions)
    }

    @Test fun `listener disconnect drops all call actions and pending fallback ownership`() {
        post("com.viber.voip", 35, 110, incomingCallStyle("answer", "decline"))
        assertTrue(CallNotifListener.answerCall())
        listener.onListenerDisconnected()

        assertFalse(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.isVoipNotifActive)
        assertFalse(CallNotifListener.hasPendingCallAction())
        assertFalse(CallNotifListener.answerCall())
        assertFalse(CallNotifListener.declineCall())
        assertFalse(CallNotifListener.hangUp())
    }

    @Test fun `aggregate stage callbacks preserve a second ringing owner and ignore duplicate updates`() {
        val changes = mutableListOf<Pair<Boolean, Boolean>>()
        CallNotifListener.onCallStateChanged = { ringing, active -> changes += ringing to active }
        val first = post("com.viber.voip", 36, 120, incomingCallStyle("viber-answer", "viber-decline"))
        val secondNotification = incomingCallStyle("whatsapp-answer", "whatsapp-decline")
        post("com.whatsapp", 37, 130, secondNotification)
        post("com.whatsapp", 37, 131, secondNotification)
        listener.onNotificationRemoved(first)
        assertEquals(listOf(true to false), changes)
        val ongoing = post("com.whatsapp", 37, 132, ongoingCallStyle("whatsapp-hangup"))
        assertEquals(listOf(true to false, false to true), changes)
        listener.onNotificationRemoved(ongoing)
        assertEquals(listOf(true to false, false to true, false to false), changes)
    }

    @Test fun `Ukrainian and Vietnamese vendor labels resolve without invented translations`() {
        // Values from the installed Viber APK's CallStyle resource variants.
        val labels = listOf("Відповісти" to "Відхилити", "Trả lời" to "Từ chối")
        labels.forEachIndexed { index, (answer, decline) ->
            val incoming = post("com.viber.voip", 40 + index, 200L + index,
                notification(action(answer, "answer-$index"), action(decline, "decline-$index")))
            assertTrue(CallNotifListener.isVoipNotifRinging)
            assertFalse(CallNotifListener.isVoipNotifActive)
            assertTrue(CallNotifListener.answerCall())
            assertTrue(CallNotifListener.declineCall())
            shadowOf(Looper.getMainLooper()).idle()
            listener.onNotificationRemoved(incoming)
        }
        assertEquals(listOf("answer-0", "decline-0", "answer-1", "decline-1"), receivedActions)
    }

    @Test fun `incoming CallStyle is not inferred active when it exposes no recognizable actions`() {
        post("com.viber.voip", 28, 40, callTypeNotification(
            Notification.CallStyle.CALL_TYPE_INCOMING,
            action("Custom action", "custom"),
        ))
        assertTrue(CallNotifListener.isVoipNotifRinging)
        assertFalse(CallNotifListener.isVoipNotifActive)
        assertFalse(CallNotifListener.answerCall())
        assertFalse(CallNotifListener.declineCall())
    }
}
