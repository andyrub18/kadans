package app.kadans.notifications

import app.kadans.pomodoro.CycleNotice
import app.kadans.sound.KadansSound

/**
 * Shows an OS-level notification so the user hears about phase changes and reminders without
 * looking at the app. Best-effort: platforms without a wired channel (Android/iOS until FCM and
 * APNs land, per the owner checklist) no-op and the in-app snackbar still covers the foreground.
 * [sound]: the one the notification asks for, played where the app plays its own (ARCHITECTURE → "Sounds"), again and
 * again until answered if the person asked. [link]: what a click on it opens in the app, where a click can be heard.
 */
expect fun showSystemNotification(title: String, body: String, sound: KadansSound? = null, link: String? = null)

/**
 * A looping focus session's cycle done, with "Another cycle" and "Done" where the platform has buttons (the Linux
 * desktop). Android shows it from its push (`SessionNotices`); elsewhere it is [showSystemNotification].
 */
expect fun showCycleNotice(notice: CycleNotice)
