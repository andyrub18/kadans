package app.kadans.di

import app.kadans.startup.StartAtLogin
import app.kadans.startup.platformStartAtLogin
import app.kadans.api.KadansApi
import app.kadans.api.platformHttpEngine
import app.kadans.api.ClearAwareTokenStore
import app.kadans.api.TokenStore
import app.kadans.auth.GoogleSignIn
import app.kadans.auth.SealedTokenStore
import app.kadans.auth.Sealer
import app.kadans.auth.platformGoogleSignIn
import app.kadans.auth.platformSealer
import app.kadans.billing.StoreBilling
import app.kadans.billing.SubscriptionGate
import app.kadans.billing.platformStoreBilling
import app.kadans.config.ServerAddress
import app.kadans.i18n.Language
import app.kadans.i18n.LanguageController
import app.kadans.push.DeviceRegistrar
import app.kadans.quiet.FocusQuiet
import app.kadans.quiet.FocusQuietStore
import app.kadans.quiet.QuietMode
import app.kadans.quiet.platformQuietMode
import app.kadans.reminders.LocalReminders
import app.kadans.reminders.ReminderScheduler
import app.kadans.reminders.ReminderStore
import app.kadans.reminders.ReminderActions
import app.kadans.reminders.platformReminderScheduler
import app.kadans.widget.TodayWidget
import app.kadans.widget.TodayWidgetStore
import app.kadans.widget.WidgetSurface
import app.kadans.widget.platformWidgetSurface
import app.kadans.realtime.KadansRealtime
import app.kadans.sync.DataChanges
import app.kadans.profile.ClockPreference
import app.kadans.profile.ProfileSync
import app.kadans.profile.TimeZonePreference
import app.kadans.ui.pomodoro.PomodoroPreference
import app.kadans.realtime.SystemAlerts
import app.kadans.ui.auth.ForgotPasswordViewModel
import app.kadans.ui.auth.LoginViewModel
import app.kadans.ui.auth.KeepAccountViewModel
import app.kadans.ui.auth.MfaViewModel
import app.kadans.ui.auth.RegisterViewModel
import app.kadans.ui.auth.ResetPasswordViewModel
import app.kadans.ui.budget.BudgetAddViewModel
import app.kadans.ui.budget.BudgetViewModel
import app.kadans.ui.calendar.CalendarViewModel
import app.kadans.ui.billing.PaywallViewModel
import app.kadans.ui.billing.SubscriptionSettingsViewModel
import app.kadans.ui.home.HomeViewModel
import app.kadans.ui.notifications.NotificationsViewModel
import app.kadans.ui.settings.CalendarFeedViewModel
import app.kadans.ui.settings.SettingsViewModel
import app.kadans.ui.stats.StatsViewModel
import app.kadans.ui.templates.TemplatesViewModel
import app.kadans.ui.pomodoro.PomodoroViewModel
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.EditTodoViewModel
import app.kadans.ui.todos.TodoDetailViewModel
import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = org.koin.dsl.module {
    single<Settings> { Settings() }
    // The session is sealed by the device (ARCHITECTURE → "The session, sealed on the device"); one that no longer
    // opens is a sign-out like any other.
    single<Sealer> { platformSealer() }
    single {
        val koin = getKoin()
        SealedTokenStore(get(), get(), onLost = { forgetAccount(koin) })
    }
    // Signed out by any path: the reminders this device rang itself stop with the session, and the home-screen
    // widget holds nothing of the account any more.
    single<TokenStore> {
        val koin = getKoin()
        ClearAwareTokenStore(get<SealedTokenStore>()) { forgetAccount(koin) }
    }
    single {
        val settings = get<Settings>()
        KadansApi.create(
            tokenStore = get(),
            engine = platformHttpEngine(),
            baseUrlProvider = { ServerAddress.current(settings) },
            languageProvider = { LanguageController.currentTag(settings) },
        ).also { api ->
            // Every session a request brings back settles "Do Not Disturb during focus" before its caller goes on.
            val koin = getKoin()
            api.runSeen = { run -> koin.get<FocusQuiet>().see(run) }
        }
    }
    single { KadansRealtime(get()) }
    single { SystemAlerts(get(), get()) }
    single {
        val koin = getKoin()
        DeviceRegistrar(get(), get(), quiet = { koin.get<FocusQuiet>().reportable })
    }
    // Do Not Disturb during focus (Android): the phone quiet while a focus phase counts down.
    single { FocusQuietStore(get()) }
    single<QuietMode> {
        val settings = get<Settings>()
        platformQuietMode(
            catalog = { Language.fromTag(LanguageController.currentTag(settings)).catalog },
            clock = { ClockPreference.current(settings) },
        )
    }
    single {
        val koin = getKoin()
        FocusQuiet(get(), get(), get(), get<KadansRealtime>().events, report = { koin.get<DeviceRegistrar>().register() })
    }
    single { ReminderStore(get()) }
    single<ReminderScheduler> {
        val settings = get<Settings>()
        val koin = getKoin()
        platformReminderScheduler(
            strings = { Language.fromTag(LanguageController.currentTag(settings)).catalog.reminders },
            onDue = { koin.get<LocalReminders>().ringDue() },
        )
    }
    single {
        val settings = get<Settings>()
        LocalReminders(
            get(), get(), get(), get(), get<KadansRealtime>().events,
            snoozedText = { reminder, now ->
                val strings = Language.fromTag(LanguageController.currentTag(settings)).catalog.reminders
                LocalReminders.snoozedBody(
                    reminder, now, strings, kotlinx.datetime.TimeZone.currentSystemDefault(), ClockPreference.current(settings),
                )
            },
        )
    }
    single { ReminderActions(get(), get(), get(), get()) }
    // The home-screen widget (Android): today's pending todos, fetched when they may have changed.
    single { TodayWidgetStore(get()) }
    single<WidgetSurface> {
        val settings = get<Settings>()
        platformWidgetSurface(
            catalog = { Language.fromTag(LanguageController.currentTag(settings)).catalog },
            clock = { ClockPreference.current(settings) },
        )
    }
    single {
        val koin = getKoin()
        TodayWidget(get(), get(), get(), get(), get<KadansRealtime>().events, get(), settled = { koin.get<LocalReminders>().settled(it) })
    }
    // The phone's own calendar beside the todos (Android): read on the phone when a screen shows it, never kept.
    single { app.kadans.calendar.PhoneCalendarStore(get()) }
    single<app.kadans.calendar.PhoneCalendarSource> { app.kadans.calendar.platformPhoneCalendar() }
    single { app.kadans.calendar.PhoneCalendar(get(), get()) }
    // One file in (a bank statement), one file out (a copy of the person's data), each through the system's own dialog.
    single<app.kadans.files.FilePorts> { app.kadans.files.platformFilePorts() }
    // The desktop app's update check fills it (startUpdateChecks); phones are updated by their stores and leave it empty.
    single { app.kadans.updates.AppUpdates() }
    // A looping session's cycle done: its notice's "Another cycle" and "Done", in the app's language.
    single {
        val settings = get<Settings>()
        app.kadans.pomodoro.CycleActions(get(), get()) { Language.fromTag(LanguageController.currentTag(settings)).catalog }
    }
    // When the screens load again what they show: the server's word, a notification's button here, back in front.
    single { DataChanges(get<KadansRealtime>().events) }
    // The bell's count, the same on every destination's bar (MainShell).
    single { app.kadans.ui.shell.UnreadNotifications(get()) }
    single { LanguageController(get(), get()) }
    single { TimeZonePreference(get()) }
    single { PomodoroPreference(get()) }
    single { ClockPreference(get()) }
    single { app.kadans.ui.AppearancePreference(get()) }
    single { ProfileSync(get(), get(), get(), get()) }
    single<StoreBilling> { platformStoreBilling() }
    single<StartAtLogin> { platformStartAtLogin() }
    single { SubscriptionGate(get(), get()) }
    single<GoogleSignIn> {
        val language = get<LanguageController>()
        // The desktop flow ends on a page in the user's browser; it speaks the app's language.
        platformGoogleSignIn(returnToAppText = { language.language.value.catalog.googleReturnToApp })
    }

    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    viewModelOf(::ForgotPasswordViewModel)
    factory { (email: String, token: String) -> ResetPasswordViewModel(get(), email, token) }
    viewModelOf(::HomeViewModel)
    viewModelOf(::PaywallViewModel)
    viewModelOf(::SubscriptionSettingsViewModel)
    viewModelOf(::TemplatesViewModel)
    viewModelOf(::StatsViewModel)
    factory { NotificationsViewModel(get(), get<KadansRealtime>().events) }
    viewModelOf(::CalendarViewModel)
    viewModelOf(::BudgetViewModel)
    // A new movement, or one to correct (its id).
    viewModel { params -> BudgetAddViewModel(get(), params.getOrNull<String>()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
    viewModelOf(::CalendarFeedViewModel)
    viewModel { app.kadans.ui.settings.DataExportViewModel(get(), get()) }
    // A bank statement brought into the budget. Dates whose numbers say no order are read the American way for an
    // account in English with a 12-hour clock, day first for everyone else; the person sees them and corrects.
    viewModel {
        val settings = get<Settings>()
        app.kadans.ui.budget.StatementImportViewModel(
            get(), get(),
            fallbackOrder = {
                val american = LanguageController.currentTag(settings).startsWith("en") &&
                    ClockPreference.current(settings) == app.kadans.api.model.ClockFormat.TwelveHour
                if (american) app.kadans.statement.StatementText.DateOrder.MonthDayYear else app.kadans.statement.StatementText.DateOrder.DayMonthYear
            },
        )
    }
    factory { (mfaToken: String) -> MfaViewModel(get(), mfaToken) }
    factory { (restoreToken: String) -> KeepAccountViewModel(get(), restoreToken) }
    // A new todo, from nothing or from a text another app shared (its draft).
    viewModel { params -> CreateTodoViewModel(get(), params.getOrNull<app.kadans.share.TodoDraft>()) }
    factory { (todoId: String) -> TodoDetailViewModel(get(), todoId, get()) }
    factory { (todoId: String) -> EditTodoViewModel(get(), todoId) }
    factory { (todoId: String, loop: Boolean, handsFree: Boolean, finishAtMillis: Long?, startsOne: Boolean) ->
        PomodoroViewModel(get(), get(), todoId, loop, handsFree, finishAtMillis?.let(kotlin.time.Instant::fromEpochMilliseconds), startsOne)
    }
}

/** What this device kept for the account that was signed in. */
private suspend fun forgetAccount(koin: org.koin.core.Koin) {
    koin.get<LocalReminders>().forget()
    koin.get<TodayWidget>().forget()
    // A phone gone quiet for a focus phase has its sound back.
    koin.get<FocusQuiet>().forget()
}

private var started = false

/** Idempotent so every launcher (Activity recreation, iOS controller) can call it safely. */
fun initKoin() {
    if (started) return
    started = true
    startKoin { modules(appModule) }
}
