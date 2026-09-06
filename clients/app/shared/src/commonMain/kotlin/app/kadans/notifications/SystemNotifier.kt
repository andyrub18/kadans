package app.kadans.notifications

/**
 * Shows an OS-level notification so the user hears about phase changes and reminders without
 * looking at the app. Best-effort: platforms without a wired channel (Android/iOS until FCM and
 * APNs land, per the owner checklist) no-op and the in-app snackbar still covers the foreground.
 */
expect fun showSystemNotification(title: String, body: String)
