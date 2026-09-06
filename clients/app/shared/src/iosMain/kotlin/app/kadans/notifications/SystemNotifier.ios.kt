package app.kadans.notifications

// UNUserNotificationCenter needs a permission prompt and is superseded by APNs push (owner
// checklist) — until that lands, the in-app snackbar covers the foreground.
actual fun showSystemNotification(title: String, body: String) = Unit
