package app.kadans.reminders

// Desktop: a timer in the app comes after Android (ARCHITECTURE → "Reminders ring on the phone"). Until then the
// server sends it every reminder live, as before.
actual fun platformReminderScheduler(channelName: () -> String): ReminderScheduler = NoReminderScheduler
