package app.kadans.reminders

import app.kadans.api.model.UpcomingReminder

/** Whether this device can ring reminders itself, and if not, what the person could turn on. */
enum class ReminderAccess {
    /** Notifications on, and exact alarms allowed where they need it. */
    Ready,

    /** Android's "Alarms & reminders" is off (the default for new installs since Android 14). */
    ExactAlarmsOff,

    /** Notifications are off for the app: nothing could show, rung here or pushed. */
    NotificationsOff,

    /** No scheduler on this platform yet (desktop, iOS): the server pushes every reminder, as before. */
    Unsupported,
}

/**
 * The operating system's side of the reminders a device rings itself (ARCHITECTURE → "Reminders ring on the phone").
 * [LocalReminders] decides what rings and when; this only talks to the OS.
 */
interface ReminderScheduler {
    fun access(): ReminderAccess

    /** Opens the system's screen that turns on what [access] says is missing. */
    fun openSettings(missing: ReminderAccess)

    /**
     * From now on, [reminders] ring (sorted by notify time, none rung yet; empty: nothing rings), replacing whatever
     * was scheduled before. On Android that is one exact alarm, at the first: when it rings,
     * [LocalReminders.ringDue] shows what is due and schedules the rest. A notify time already past rings at once.
     */
    fun schedule(reminders: List<UpcomingReminder>)

    /** Shows the reminder now: its alarm rang, or a push brought it. A tap opens its todo. */
    fun show(reminder: UpcomingReminder)

    /** A background refresh of the window twice a day, while this device rings reminders. */
    fun keepFresh(enabled: Boolean)
}

/** A platform that cannot ring reminders itself: the server's push and the hub stay its channels. */
object NoReminderScheduler : ReminderScheduler {
    override fun access() = ReminderAccess.Unsupported

    override fun openSettings(missing: ReminderAccess) = Unit

    override fun schedule(reminders: List<UpcomingReminder>) = Unit

    override fun show(reminder: UpcomingReminder) = Unit

    override fun keepFresh(enabled: Boolean) = Unit
}

/** This platform's scheduler (Android: AlarmManager). [channelName]: what the system lists reminders under. */
expect fun platformReminderScheduler(channelName: () -> String): ReminderScheduler
