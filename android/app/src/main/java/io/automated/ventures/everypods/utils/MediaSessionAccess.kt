/* Copyright (C) 2026 EveryPods contributors. GPL-3.0. */
package io.automated.ventures.everypods.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.automated.ventures.everypods.services.CallNotifListener
import io.automated.ventures.everypods.services.NotificationAnnouncementService

/** Notification access belongs to a component in this install, including applicationId suffixes. */
object MediaSessionAccess {
    internal fun selectListener(packageName: String, enabled: String?): ComponentName? {
        val granted = enabled.orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }.toSet()
        return listOf(CallNotifListener::class.java.name, NotificationAnnouncementService::class.java.name)
            .map { ComponentName(packageName, it) }.firstOrNull { it in granted }
    }

    fun enabledListener(context: Context): ComponentName? = selectListener(
        context.packageName,
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners"),
    )

    fun openSettings(context: Context) {
        // Call/mute sync can provide media access without enabling notification announcements.
        val component = ComponentName(context, CallNotifListener::class.java)
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
