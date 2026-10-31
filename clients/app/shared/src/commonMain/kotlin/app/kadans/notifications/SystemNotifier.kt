package app.kadans.notifications

import app.kadans.pomodoro.CycleNotice

/**
 * Shows an OS-level notification so the user hears about phase changes and reminders without
 * looking at the app. Best-effort: platforms without a wired channel (Android/iOS until FCM and
 * APNs land, per the owner checklist) no-op and the in-app snackbar still covers the foreground.
 */
expect fun showSystemNotification(title: String, body: String)

/**
 * A looping focus session's cycle done, with "Another cycle" and "Done" where the platform has buttons (the Linux
 * desktop). Android shows it from its push (`PomodoroCycleNotices`); elsewhere it is [showSystemNotification].
 */
expect fun showCycleNotice(notice: CycleNotice)
