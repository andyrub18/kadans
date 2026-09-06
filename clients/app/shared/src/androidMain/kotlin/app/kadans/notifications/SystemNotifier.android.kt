package app.kadans.notifications

// Local notifications on Android need a channel, a POST_NOTIFICATIONS grant, and are superseded
// by FCM push (owner checklist) — until that lands, the in-app snackbar covers the foreground.
actual fun showSystemNotification(title: String, body: String) = Unit
