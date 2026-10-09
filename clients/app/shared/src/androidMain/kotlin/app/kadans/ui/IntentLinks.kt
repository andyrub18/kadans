package app.kadans.ui

import android.content.Intent
import app.kadans.reminders.LocalReminders

/**
 * The link an intent that reached the app carries: its data (a reminder this app showed, an email's landing page), or,
 * for a reminder push the system showed itself (a phone that does not ring reminders), the todo it is about.
 */
fun linkOf(intent: Intent?): String? {
    intent ?: return null
    intent.dataString?.let { return it }
    if (intent.getStringExtra("kind") != LocalReminders.DUE_KIND) return null
    return intent.getStringExtra("todoId")?.let(::todoLink)
}
