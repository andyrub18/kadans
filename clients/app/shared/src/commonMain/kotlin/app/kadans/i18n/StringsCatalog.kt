package app.kadans.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import app.kadans.api.model.Frequency
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TaskStatus

/**
 * Strings of one feature area. The flat [StringsCatalog] is nearly full: the JVM allows 255
 * parameter slots per method and a data class spends them on its constructor *and* on the wider
 * synthetic `copy$default`. Past ~245 keys the class still compiles but fails to load
 * (ClassFormatError) — on desktop and Android alike. New areas therefore get their own group,
 * which keeps the guarantee that matters: a missing translation is a compile error.
 */
data class FocusStatsStrings(
    val homeButton: String,
    val title: String,
    val last7Days: String,
    val last30Days: String,
    val last90Days: String,
    val focusTime: String,
    val breakTime: String,
    val sessionsCompleted: String,
    val sessionsCancelled: String,
    val dailyAverage: String,
    val bestDay: String,
    val focusPerDay: String,
    val noFocusYet: String,
    val focusSessions: String,
    val runActive: String,
    val runPaused: String,
    val runCompleted: String,
    val runCancelled: String,
) {
    fun rangeName(range: app.kadans.ui.stats.StatsRange): String = when (range) {
        app.kadans.ui.stats.StatsRange.Week -> last7Days
        app.kadans.ui.stats.StatsRange.Month -> last30Days
        app.kadans.ui.stats.StatsRange.Quarter -> last90Days
    }

    fun runStatusName(status: app.kadans.api.model.PomodoroRunStatus): String = when (status) {
        app.kadans.api.model.PomodoroRunStatus.Active -> runActive
        app.kadans.api.model.PomodoroRunStatus.Paused -> runPaused
        app.kadans.api.model.PomodoroRunStatus.Completed -> runCompleted
        app.kadans.api.model.PomodoroRunStatus.Cancelled -> runCancelled
    }
}

/**
 * Every user-visible string. A data class so the compiler forces each language to provide
 * every key — a missing translation is a build error, not a runtime English leak.
 */
/** Settings → email and devices. A feature group, like [FocusStatsStrings] (the flat catalog is full). */
data class AccountStrings(
    val emailSection: String,
    val emailConfirmed: String,
    val emailNotConfirmed: String,
    val noEmailYet: String,
    val resendConfirmation: String,
    val confirmationSent: String,
    val newEmailLabel: String,
    val emailChangeHint: String,
    val sendEmailChangeLink: String,
    /** `%s` = the address the link went to. */
    val emailChangeSentFormat: String,
    val devicesSection: String,
    val devicesHint: String,
    val noDevices: String,
    val thisDevice: String,
    val pushOn: String,
    val pushOff: String,
    val lastSeen: String,
    val removeDevice: String,
    /** On the sign-in screen after the server ended this device's session. */
    val sessionEndedNotice: String,
) {
    fun emailChangeSent(address: String): String = emailChangeSentFormat.replace("%s", address)
}

/** Settings → time zone: followed from the device, or picked from a list. A feature group (the flat catalog is full). */
data class TimeZoneStrings(
    val section: String,
    val followDevice: String,
    val followDeviceHint: String,
    val manualHint: String,
    val deviceZoneUnusable: String,
    val choose: String,
    val searchHint: String,
    val noMatch: String,
    val saved: String,
)

/** Create and edit todo: how long before the start to remind, and the rule limits. A feature group. */
data class TodoFormStrings(
    val reminderWhen: String,
    val atStart: String,
    /** `%d` = minutes. */
    val minutesBeforeFormat: String,
    /** `%d` = hours. */
    val hoursBeforeFormat: String,
    val oneDayBefore: String,
    /** `%d` = days. */
    val daysBeforeFormat: String,
) {
    /** "At the start", "15 min before", "1 h before", "1 day before", "2 days before". */
    fun reminderLead(minutes: Int): String = when {
        minutes <= 0 -> atStart
        minutes % (24 * 60) == 0 -> (minutes / (24 * 60)).let { if (it == 1) oneDayBefore else daysBeforeFormat.replace("%d", "$it") }
        minutes % 60 == 0 -> hoursBeforeFormat.replace("%d", "${minutes / 60}")
        else -> minutesBeforeFormat.replace("%d", "$minutes")
    }
}

/**
 * How a rule repeats, in words: the forms' "Every 2 weeks" and a todo's rule. Whole phrases, not a prefix and a unit:
 * French agrees with the unit ("Tous les 2 jours", "Toutes les 2 semaines"). A feature group.
 */
data class RepeatStrings(
    val everyMinute: String,
    val everyHour: String,
    val everyDay: String,
    val everyWeek: String,
    val everyMonth: String,
    val everyYear: String,
    /** `%d` = how many. */
    val everyMinutesFormat: String,
    val everyHoursFormat: String,
    val everyDaysFormat: String,
    val everyWeeksFormat: String,
    val everyMonthsFormat: String,
    val everyYearsFormat: String,
    /** `%d` = the day of the month a monthly rule falls on. */
    val monthDayFormat: String,
    /** `%d` = how many times in all. */
    val countFormat: String,
    /** `%s` = the last date. */
    val untilFormat: String,
    /** `%s` = the date a weekly rule first falls on, when it is not the date picked. */
    val firstTimeFormat: String,
    /** Monthly and yearly: the day picked by its date ("the 15th") or by its day of the week ("the second Tuesday"). */
    val byDate: String,
    val byWeekday: String,
    /** The day grid's last cell (BYMONTHDAY -1). */
    val lastDay: String,
    /** The rule's words for the last day alone. */
    val onLastDay: String,
    /** `%s` = several days of the month, "1, 15, last". */
    val monthDaysFormat: String,
    /** First to fifth, then last (BYSETPOS 1–5, -1), as written inside a sentence. */
    val ordinals: List<String>,
    /** Monday first, as written inside a sentence. */
    val weekdayNames: List<String>,
    /** What "the second …" counts besides a day of the week (BYDAY): any day, Monday to Friday, Saturday and Sunday. */
    val kindDay: String,
    val kindWeekday: String,
    val kindWeekendDay: String,
    /** `%1` = an ordinal, `%2` = a day: "the second Tuesday", "the last weekday". */
    val onTheFormat: String,
    val monthsLabel: String,
    /** Under the interval: "the second Sunday" in several months can only repeat every year (see buildRecurring). */
    val severalMonthsEveryYear: String,
    /** The rule picked falls on no date the server would keep ("the 30th of February"). */
    val neverFalls: String,
    /** Under "How many times": the server's limit. */
    val countLimit: String,
    /** Under the date of a repeating budget movement: the server's limit. */
    val startWithinAYear: String,
) {
    fun every(frequency: Frequency, interval: Int): String {
        if (interval == 1) {
            return when (frequency) {
                Frequency.Minutely -> everyMinute
                Frequency.Hourly -> everyHour
                Frequency.Daily -> everyDay
                Frequency.Weekly -> everyWeek
                Frequency.Monthly -> everyMonth
                Frequency.Yearly -> everyYear
            }
        }
        val format = when (frequency) {
            Frequency.Minutely -> everyMinutesFormat
            Frequency.Hourly -> everyHoursFormat
            Frequency.Daily -> everyDaysFormat
            Frequency.Weekly -> everyWeeksFormat
            Frequency.Monthly -> everyMonthsFormat
            Frequency.Yearly -> everyYearsFormat
        }
        return format.replace("%d", "$interval")
    }

    fun monthDay(day: Int): String = monthDayFormat.replace("%d", "$day")

    fun count(times: Int): String = countFormat.replace("%d", "$times")

    fun until(date: String): String = untilFormat.replace("%s", date)

    fun firstTime(date: String): String = firstTimeFormat.replace("%s", date)

    fun monthDays(days: String): String = monthDaysFormat.replace("%s", days)

    fun onThe(ordinal: String, day: String): String = onTheFormat.replace("%1", ordinal).replace("%2", day)
}

/** Pomodoro: when a session ends by itself, and the cycle builder. A feature group (the flat catalog is full). */
data class PomodoroStrings(
    /** `%s` = a clock time. */
    val endsAtFormat: String,
    /** `%s` = a clock time. */
    val endsTomorrowAtFormat: String,
    val endHint: String,
    val chooseEnd: String,
    val builderTitle: String,
    val builderFocus: String,
    val builderShortBreak: String,
    val builderRounds: String,
    val builderLongBreak: String,
    val builderFill: String,
    val cycleLimits: String,
) {
    fun endsAt(time: String): String = endsAtFormat.replace("%s", time)

    fun endsTomorrowAt(time: String): String = endsTomorrowAtFormat.replace("%s", time)
}

/** Deleting the account (closed now, erased after 7 days unless kept) and deleting one todo. A feature group. */
data class DeletionStrings(
    val section: String,
    val explanation: String,
    val subscriptionNote: String,
    val deleteAccount: String,
    val confirmTitle: String,
    val confirmText: String,
    /** `%s` = the address the link went to. */
    val linkSentFormat: String,
    /** `%s` = the erasure date. */
    val closedNoticeFormat: String,
    val keepTitle: String,
    /** `%s` = the erasure date. */
    val keepTextFormat: String,
    val keepButton: String,
    val notNow: String,
    val deleteTodo: String,
    val deleteTodoTitle: String,
    val deleteTodoText: String,
    /** January … December. */
    val months: List<String>,
    /** `{d}` day, `{m}` month name, `{y}` year: "October 9, 2026", "9 octobre 2026". */
    val datePattern: String,
) {
    fun date(day: kotlinx.datetime.LocalDate): String =
        datePattern.replace("{d}", "${day.day}").replace("{m}", months[day.month.ordinal]).replace("{y}", "${day.year}")

    fun linkSent(address: String): String = linkSentFormat.replace("%s", address)

    fun closedNotice(date: String): String = closedNoticeFormat.replace("%s", date)

    fun keepText(date: String): String = keepTextFormat.replace("%s", date)
}

/** The paywall on phones, and the subscription in Settings. A feature group. Placeholders: {days}, {price}, {date}. */
data class PaywallStrings(
    val title: String,
    val intro: String,
    val benefitReminders: String,
    val benefitFocus: String,
    val benefitBudget: String,
    val trialPriceFormat: String,
    val priceFormat: String,
    val renewalTerms: String,
    val startTrial: String,
    val subscribe: String,
    val restore: String,
    val manage: String,
    val terms: String,
    val privacy: String,
    val pending: String,
    val nothingToRestore: String,
    val unavailable: String,
    val devFakeTrial: String,
    val settingsSection: String,
    val trialUntilFormat: String,
    val renewsOnFormat: String,
    val endsOnFormat: String,
    val paymentProblem: String,
    val paused: String,
    val notSubscribed: String,
    val freeAccess: String,
    val storeFailed: String,
) {
    fun trialPrice(days: Int, price: String): String = trialPriceFormat.replace("{days}", "$days").replace("{price}", price)

    fun price(price: String): String = priceFormat.replace("{price}", price)

    fun trialUntil(date: String): String = trialUntilFormat.replace("{date}", date)

    fun renewsOn(date: String): String = renewsOnFormat.replace("{date}", date)

    fun endsOn(date: String): String = endsOnFormat.replace("{date}", date)
}

/** The notification centre. A feature group (moved out of the flat catalog to keep it clear of the JVM limit). */
data class NotificationStrings(
    val title: String,
    val markAllRead: String,
    val empty: String,
    val loadMore: String,
)

/**
 * Reminders this phone rings itself (ARCHITECTURE → "Reminders ring on the phone"): asking for "Alarms & reminders",
 * the Settings section, and the name the system lists reminders under. A feature group.
 */
data class ReminderStrings(
    val permissionTitle: String,
    val permissionText: String,
    val allow: String,
    val notNow: String,
    val section: String,
    val ringsHere: String,
    val exactAlarmsOff: String,
    val notificationsOff: String,
    val openSettings: String,
    val channelName: String,
)

data class StringsCatalog(
    // auth
    val signInTitle: String,
    val usernameOrEmail: String,
    val password: String,
    val signIn: String,
    val focusStats: FocusStatsStrings,
    val account: AccountStrings,
    val timeZone: TimeZoneStrings,
    val todoForm: TodoFormStrings,
    val repeat: RepeatStrings,
    val pomodoro: PomodoroStrings,
    val deletion: DeletionStrings,
    val paywall: PaywallStrings,
    val reminders: ReminderStrings,
    val notifications: NotificationStrings,
    val continueWithGoogle: String,
    val googleWaiting: String,
    val googleReturnToApp: String,
    val createAccount: String,
    val twoFactorTitle: String,
    val mfaCodeLabel: String,
    val verify: String,
    val back: String,
    val registerTitle: String,
    val username: String,
    val email: String,
    val displayNameOptional: String,
    val register: String,
    val backToSignIn: String,
    // home
    val next7Days: String,
    val nothingScheduled: String,
    val allTodos: String,
    val noTodosYet: String,
    val refresh: String,
    val signOut: String,
    val cycles: String,
    val retry: String,
    // create todo
    val newTodo: String,
    val titleLabel: String,
    val descriptionOptional: String,
    val oneTime: String,
    val recurring: String,
    val dueDate: String,
    val firstOn: String,
    val timeLabel: String,
    val pick: String,
    val pickADate: String,
    val timesThatDay: String,
    val addTime: String,
    val sameMinuteError: String,
    val ends: String,
    val endNever: String,
    val endAfterCount: String,
    val endOnDate: String,
    val howManyTimes: String,
    val lastOccurrenceOn: String,
    val pickLastDay: String,
    val lastOccurrenceAt: String,
    val endOfDay: String,
    val remindMe: String,
    val create: String,
    val cancel: String,
    val ok: String,
    // frequencies + statuses
    val freqMinutely: String,
    val freqHourly: String,
    val freqDaily: String,
    val freqWeekly: String,
    val freqMonthly: String,
    val freqYearly: String,
    val statusScheduled: String,
    val statusStarted: String,
    val statusCompleted: String,
    val statusCancelled: String,
    val occPending: String,
    val occCompleted: String,
    val occCancelled: String,
    // todo detail
    val pendingOccurrences: String,
    val history: String,
    val showPending: String,
    val showHistory: String,
    val nothingHere: String,
    val complete: String,
    val skip: String,
    val moved: String,
    val cancelThisTodo: String,
    val cycleWord: String,
    val cycleDefault: String,
    val change: String,
    val cycleDialogTitle: String,
    val cycleNone: String,
    val close: String,
    val startFocus: String,
    val openFocus: String,
    val repeatUntilFinish: String,
    val handsFree: String,
    // pomodoro
    val focus: String,
    val breakWord: String,
    val lapWord: String,
    val phaseWord: String,
    val ofWord: String,
    val paused: String,
    val pause: String,
    val resume: String,
    val nextPhase: String,
    val finishSession: String,
    val discard: String,
    val endSession: String,
    val pomodoroComplete: String,
    val wellDoneMore: String,
    val startAnotherCycle: String,
    val backToTodo: String,
    val sessionEnded: String,
    val startNewSession: String,
    // templates
    val pomodoroCycles: String,
    val newWord: String,
    val newCycle: String,
    val editCycle: String,
    val nameLabel: String,
    val phasesInOrder: String,
    val minutes: String,
    val addFocus: String,
    val addBreak: String,
    val save: String,
    val deleteCycle: String,
    val noCyclesYet: String,
    // calendar
    val calendar: String,
    val previewLabel: String,
    val monthNames: List<String>,
    val weekdayShort: List<String>,
    // edit & reschedule
    val editTodo: String,
    val saveChanges: String,
    val move: String,
    val rescheduleTitle: String,
    val reasonOptional: String,
    // settings
    val settings: String,
    val profileSection: String,
    val languageLabel: String,
    val saveProfile: String,
    val profileSaved: String,
    val changePasswordTitle: String,
    val currentPassword: String,
    val newPassword: String,
    val changePasswordAction: String,
    val passwordChangedSignInAgain: String,
    val mfaSection: String,
    val mfaEnabledBadge: String,
    val mfaDisabledBadge: String,
    val enableMfa: String,
    val disableMfa: String,
    val mfaEnrollHint: String,
    val mfaManualKey: String,
    val recoveryCodesTitle: String,
    val recoveryCodesHint: String,
    val regenerateRecoveryCodes: String,
    val signOutEverywhere: String,
    // budget
    val budget: String,
    val accountsSection: String,
    val newAccount: String,
    val accountName: String,
    val currencyLabel: String,
    val initialBalanceLabel: String,
    val typeCash: String,
    val typeBank: String,
    val typeMobileMoney: String,
    val typeCard: String,
    val typeSavings: String,
    val typeOther: String,
    val income: String,
    val expense: String,
    val transferWord: String,
    val amountLabel: String,
    val categoryLabel: String,
    val noCategory: String,
    val addTransaction: String,
    val fromAccount: String,
    val toAccount: String,
    val receivedAmount: String,
    val exchangeRateTitle: String,
    val baseCurrencyLabel: String,
    val rateHint: String,
    val atYourRate: String,
    val missingRatesLabel: String,
    val totalBalanceLabel: String,
    val netLabel: String,
    val categoriesSection: String,
    val newCategory: String,
    val categoryName: String,
    val iconOptional: String,
    val limitLabel: String,
    val recurringSection: String,
    val repeatLabel: String,
    val editAccount: String,
    val archivedLabel: String,
    val transferNeedsTwoAccounts: String,
    val onDaysLabel: String,
    val recentTransactions: String,
    val noTransactionsYet: String,
    val deleteWord: String,
    // desktop tray
    val trayOpen: String,
    val trayQuit: String,
    // forgot / reset password
    val forgotPassword: String,
    val forgotTitle: String,
    val sendResetLink: String,
    val resetEmailSent: String,
    val resetPasswordTitle: String,
    val resetPasswordAction: String,
    val resetDone: String,
    // server address override
    val serverLabel: String,
    val serverHint: String,
    // errors
    val errNetwork: String,
    val errExternalLogin: String,
    val errExternalNotConfigured: String,
    val errInvalidTimeZone: String,
    val errInvalidCredentials: String,
    val errUserInactive: String,
    val errInvalidToken: String,
    val errMfaCode: String,
    val errAlreadyCompleted: String,
    val errAlreadyCancelled: String,
    val errNotFound: String,
    val errInvalidAmount: String,
    val errCurrencyMismatch: String,
    val errSameAccount: String,
    val errCategoryKind: String,
    val errAccountArchived: String,
    val errGeneric: String,
) {
    fun frequencyName(frequency: Frequency): String = when (frequency) {
        Frequency.Minutely -> freqMinutely
        Frequency.Hourly -> freqHourly
        Frequency.Daily -> freqDaily
        Frequency.Weekly -> freqWeekly
        Frequency.Monthly -> freqMonthly
        Frequency.Yearly -> freqYearly
    }

    fun statusName(status: TaskStatus): String = when (status) {
        TaskStatus.Scheduled -> statusScheduled
        TaskStatus.Started -> statusStarted
        TaskStatus.Completed -> statusCompleted
        TaskStatus.Cancelled -> statusCancelled
    }

    fun occurrenceStatusName(status: OccurrenceStatus): String = when (status) {
        OccurrenceStatus.Pending -> occPending
        OccurrenceStatus.Completed -> occCompleted
        OccurrenceStatus.Cancelled -> occCancelled
    }

    fun accountTypeName(type: app.kadans.api.model.AccountType): String = when (type) {
        app.kadans.api.model.AccountType.Cash -> typeCash
        app.kadans.api.model.AccountType.Bank -> typeBank
        app.kadans.api.model.AccountType.MobileMoney -> typeMobileMoney
        app.kadans.api.model.AccountType.Card -> typeCard
        app.kadans.api.model.AccountType.Savings -> typeSavings
        app.kadans.api.model.AccountType.Other -> typeOther
    }

    fun transactionKindName(kind: app.kadans.api.model.BudgetTransactionKind): String = when (kind) {
        app.kadans.api.model.BudgetTransactionKind.Income -> income
        app.kadans.api.model.BudgetTransactionKind.Expense -> expense
        app.kadans.api.model.BudgetTransactionKind.Transfer -> transferWord
    }

    /** Server errors by Kadans errorCode, falling back to the server's own detail text. */
    fun errorFor(code: String?, fallback: String?): String = when (code) {
        "network" -> errNetwork
        "10001", "10024" -> errInvalidCredentials
        "10025" -> errUserInactive
        "10032" -> errInvalidTimeZone
        "10033" -> errInvalidToken
        "10034" -> errMfaCode
        "10035", "google" -> errExternalLogin
        "10036" -> errExternalNotConfigured
        "10005" -> errAlreadyCompleted
        "10020" -> errAlreadyCancelled
        "10019", "10021", "10028", "10043", "10044", "10045", "10046" -> errNotFound
        "10047" -> errCurrencyMismatch
        "10048" -> errInvalidAmount
        "10049" -> errSameAccount
        "10050" -> errCategoryKind
        "10051" -> errAccountArchived
        else -> fallback ?: errGeneric
    }
}

val LocalStrings = staticCompositionLocalOf<StringsCatalog> { EnglishStrings }
