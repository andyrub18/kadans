package app.kadans.reminders

import app.kadans.api.model.UpcomingReminder
import app.kadans.i18n.ReminderStrings
import app.kadans.sound.RingChoice
import app.kadans.sound.SoundFacts

/** Whether this device can ring reminders itself, and if not, what the person could turn on. */
enum class ReminderAccess {
    /** Notifications on, and exact alarms allowed where they need it. */
    Ready,

    /** Android's "Alarms & reminders" is off (the default for new installs since Android 14). */
    ExactAlarmsOff,

    /** Notifications are off for the app: nothing could show, rung here or pushed. */
    NotificationsOff,

    /** No scheduler on this platform yet (iOS): the server pushes every reminder, as before. */
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

    /**
     * Shows the reminder now: its alarm rang, or a push brought it. A tap opens its todo; its buttons ([ReminderActions])
     * start a focus todo's session or mark any other done, and put it off 10 minutes, where the platform shows buttons.
     */
    fun show(reminder: UpcomingReminder)

    /** A background refresh of the window twice a day, while this device rings reminders. */
    fun keepFresh(enabled: Boolean)

    /**
     * What the phone's own settings make of a reminder's sound (ARCHITECTURE → "Sounds"), read when Settings shows it.
     * Null where the app plays the sound itself and there is nothing of the system's to read (a computer).
     */
    fun soundFacts(): SoundFacts? = null

    /** The system's own page for the reminders' sound, where the person picks another one or turns it off. */
    fun openSoundSettings() = Unit

    /** Rings a sample reminder, as a real one would ring now: nobody should wait for a real one to find out. */
    fun ringTest() = Unit

    /** The person changed how reminders ring: the system's side follows (Android: the channel they go in). */
    fun ringChanged() = Unit
}

/** A platform that cannot ring reminders itself (iOS for now): the server's push and the hub stay its channels. */
object NoReminderScheduler : ReminderScheduler {
    override fun access() = ReminderAccess.Unsupported

    override fun openSettings(missing: ReminderAccess) = Unit

    override fun schedule(reminders: List<UpcomingReminder>) = Unit

    override fun show(reminder: UpcomingReminder) = Unit

    override fun keepFresh(enabled: Boolean) = Unit
}

/**
 * This platform's scheduler (Android: AlarmManager; desktop: a timer in the app). [strings]: the words in the app's
 * language (what the system lists reminders under, a reminder's buttons). [ring]: how the person wants reminders to
 * ring here, read each time one does. [onDue]: what a timer in the app calls when the next reminder is due
 * ([LocalReminders.ringDue]); Android's alarm reaches it through its receiver instead.
 */
expect fun platformReminderScheduler(strings: () -> ReminderStrings, ring: () -> RingChoice, onDue: suspend () -> Unit): ReminderScheduler
