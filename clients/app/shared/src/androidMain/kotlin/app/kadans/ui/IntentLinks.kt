package app.kadans.ui

import android.app.NotificationManager
import android.content.Intent
import android.service.notification.ConditionProviderService
import app.kadans.reminders.LocalReminders
import app.kadans.share.SharedText
import app.kadans.share.TodoDraft

/**
 * The link an intent that reached the app carries: a text another app shared ("Share to Kadans"), its data (a reminder
 * this app showed, the emailed password reset), the system's Do Not Disturb settings asking for Kadans' own, or,
 * for a push the system showed itself, the link it carries (a limit's alert) or, for a reminder (a phone that does not
 * ring reminders), the todo it is about.
 */
fun linkOf(intent: Intent?): String? {
    intent ?: return null
    // Another app shared a text, or the person chose "Add to Kadans" on a selection: the form for a new todo, filled in.
    sharedDraft(intent)?.let { return sharedTodoLink(it) }
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

/**
 * What another app handed over to make a todo of (ARCHITECTURE → "Share to Kadans"): a share's subject and text, or the
 * text selected when "Add to Kadans" was chosen. Text only: a share of anything else (a picture, a file) is not taken,
 * and the manifest does not offer Kadans for one. Nothing in it is trusted: [SharedText] cuts and cleans it.
 */
private fun sharedDraft(intent: Intent): TodoDraft? = when (intent.action) {
    Intent.ACTION_SEND ->
        if (intent.type != "text/plain") null
        else SharedText.draft(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    Intent.ACTION_PROCESS_TEXT -> SharedText.draft(null, intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
    else -> null
}
