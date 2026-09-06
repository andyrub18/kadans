package app.kadans.di

import app.kadans.api.KadansApi
import app.kadans.api.TokenStore
import app.kadans.auth.SettingsTokenStore
import app.kadans.config.defaultApiBaseUrl
import app.kadans.i18n.LanguageController
import app.kadans.realtime.KadansRealtime
import app.kadans.ui.auth.LoginViewModel
import app.kadans.ui.auth.MfaViewModel
import app.kadans.ui.auth.RegisterViewModel
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
    single { KadansApi.create(baseUrl = defaultApiBaseUrl(), tokenStore = get()) }
    single { KadansRealtime(get()) }
    single { LanguageController(get(), get()) }

    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::TemplatesViewModel)
    viewModelOf(::CalendarViewModel)
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
