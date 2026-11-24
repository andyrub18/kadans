package app.kadans.reminders

// iOS: the 64 soonest local notifications come with the iPhone app (ARCHITECTURE → "Reminders ring on the phone").
actual fun platformReminderScheduler(strings: () -> app.kadans.i18n.ReminderStrings, ring: () -> app.kadans.sound.RingChoice, onDue: suspend () -> Unit): ReminderScheduler = NoReminderScheduler
