package app.kadans.ui

import android.app.NotificationManager
import android.content.Intent
import android.service.notification.ConditionProviderService
import app.kadans.reminders.LocalReminders

/**
 * The link an intent that reached the app carries: its data (a reminder this app showed, the emailed password reset), the
 * system's Do Not Disturb settings asking for Kadans' own, or,
 * for a push the system showed itself, the link it carries (a limit's alert) or, for a reminder (a phone that does not
 * ring reminders), the todo it is about.
 */
fun linkOf(intent: Intent?): String? {
    intent ?: return null
    // Android's Do Not Disturb settings, on Kadans' mode ("Do Not Disturb during focus"): its switch is in Settings.
    // The documented action comes from the system's list of kinds of mode; the mode's own row starts the activity by
    // name, with the mode's id as an extra and no action (seen on Android 15).
    if (intent.action == NotificationManager.ACTION_AUTOMATIC_ZEN_RULE ||
        intent.hasExtra(NotificationManager.EXTRA_AUTOMATIC_RULE_ID) ||
        intent.hasExtra(ConditionProviderService.EXTRA_RULE_ID)
    ) {
        return SETTINGS_LINK
    }
    intent.dataString?.let { return it }
    intent.getStringExtra("link")?.takeIf { it.startsWith("kadans://") }?.let { return it }
    if (intent.getStringExtra("kind") != LocalReminders.DUE_KIND) return null
    return intent.getStringExtra("todoId")?.let(::todoLink)
}
