package app.kadans.di

import app.kadans.api.KadansApi
import app.kadans.api.TokenStore
import app.kadans.auth.SettingsTokenStore
import app.kadans.config.ServerAddress
import app.kadans.i18n.LanguageController
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.KadansRealtime
import app.kadans.realtime.SystemAlerts
import app.kadans.ui.auth.ForgotPasswordViewModel
import app.kadans.ui.auth.LoginViewModel
import app.kadans.ui.auth.MfaViewModel
import app.kadans.ui.auth.RegisterViewModel
import app.kadans.ui.auth.ResetPasswordViewModel
import app.kadans.ui.budget.BudgetAddViewModel
import app.kadans.ui.budget.BudgetViewModel
import app.kadans.ui.calendar.CalendarViewModel
import app.kadans.ui.home.HomeViewModel
import app.kadans.ui.settings.SettingsViewModel
import app.kadans.ui.templates.TemplatesViewModel
import app.kadans.ui.pomodoro.PomodoroViewModel
import app.kadans.ui.todos.CreateTodoViewModel
import app.kadans.ui.todos.EditTodoViewModel
import app.kadans.ui.todos.TodoDetailViewModel
import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = org.koin.dsl.module {
    single<Settings> { Settings() }
    single<TokenStore> { SettingsTokenStore(get()) }
    single {
        val settings = get<Settings>()
        KadansApi.create(tokenStore = get(), baseUrlProvider = { ServerAddress.current(settings) })
    }
    single { KadansRealtime(get()) }
    single { SystemAlerts(get()) }
    single { DeviceRegistrar(get(), get()) }
    single { LanguageController(get(), get()) }

    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    viewModelOf(::ForgotPasswordViewModel)
    factory { (email: String, token: String) -> ResetPasswordViewModel(get(), email, token) }
    viewModelOf(::HomeViewModel)
    viewModelOf(::TemplatesViewModel)
    viewModelOf(::CalendarViewModel)
    viewModelOf(::BudgetViewModel)
    viewModelOf(::BudgetAddViewModel)
    viewModelOf(::SettingsViewModel)
    factory { (mfaToken: String) -> MfaViewModel(get(), mfaToken) }
    viewModelOf(::CreateTodoViewModel)
    factory { (todoId: String) -> TodoDetailViewModel(get(), todoId) }
    factory { (todoId: String) -> EditTodoViewModel(get(), todoId) }
    factory { (todoId: String, loop: Boolean, handsFree: Boolean) -> PomodoroViewModel(get(), get(), todoId, loop, handsFree) }
}

private var started = false

/** Idempotent so every launcher (Activity recreation, iOS controller) can call it safely. */
fun initKoin() {
    if (started) return
    started = true
    startKoin { modules(appModule) }
}
