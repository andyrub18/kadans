package app.kadans.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import app.kadans.api.model.Frequency
import app.kadans.api.model.OccurrenceStatus
import app.kadans.api.model.TaskStatus

/**
 * Every user-visible string. A data class so the compiler forces each language to provide
 * every key — a missing translation is a build error, not a runtime English leak.
 */
data class StringsCatalog(
    // auth
    val signInTitle: String,
    val usernameOrEmail: String,
    val password: String,
    val signIn: String,
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
    val remindMe: String,
    val create: String,
    val cancel: String,
    val ok: String,
    // every N unit
    val everySingularPrefix: String,
    val everyPluralPrefix: String,
    val unitMinute: String, val unitMinutes: String,
    val unitHour: String, val unitHours: String,
    val unitDay: String, val unitDays: String,
    val unitWeek: String, val unitWeeks: String,
    val unitMonth: String, val unitMonths: String,
    val unitYear: String, val unitYears: String,
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
    // errors
    val errNetwork: String,
    val errInvalidCredentials: String,
    val errUserInactive: String,
    val errInvalidToken: String,
    val errMfaCode: String,
    val errAlreadyCompleted: String,
    val errAlreadyCancelled: String,
    val errNotFound: String,
    val errGeneric: String,
) {
    fun every(frequency: Frequency, interval: Int): String {
        val (singular, plural) = when (frequency) {
            Frequency.Minutely -> unitMinute to unitMinutes
            Frequency.Hourly -> unitHour to unitHours
            Frequency.Daily -> unitDay to unitDays
            Frequency.Weekly -> unitWeek to unitWeeks
            Frequency.Monthly -> unitMonth to unitMonths
            Frequency.Yearly -> unitYear to unitYears
        }
        return if (interval == 1) "$everySingularPrefix $singular" else "$everyPluralPrefix $interval $plural"
    }

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

    /** Server errors by Kadans errorCode, falling back to the server's own detail text. */
    fun errorFor(code: String?, fallback: String?): String = when (code) {
        "network" -> errNetwork
        "10001", "10024" -> errInvalidCredentials
        "10025" -> errUserInactive
        "10033" -> errInvalidToken
        "10034" -> errMfaCode
        "10005" -> errAlreadyCompleted
        "10020" -> errAlreadyCancelled
        "10019", "10021", "10028" -> errNotFound
        else -> fallback ?: errGeneric
    }
}

val LocalStrings = staticCompositionLocalOf<StringsCatalog> { EnglishStrings }
