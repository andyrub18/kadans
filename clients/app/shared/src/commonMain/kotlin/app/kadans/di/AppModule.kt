package app.kadans.di

import app.kadans.api.KadansApi
import app.kadans.api.TokenStore
import app.kadans.auth.SettingsTokenStore
import app.kadans.config.defaultApiBaseUrl
import app.kadans.ui.auth.LoginViewModel
import app.kadans.ui.auth.MfaViewModel
import app.kadans.ui.auth.RegisterViewModel
import app.kadans.ui.home.HomeViewModel
import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = org.koin.dsl.module {
    single<Settings> { Settings() }
    single<TokenStore> { SettingsTokenStore(get()) }
    single { KadansApi.create(baseUrl = defaultApiBaseUrl(), tokenStore = get()) }

    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    viewModelOf(::HomeViewModel)
    factory { (mfaToken: String) -> MfaViewModel(get(), mfaToken) }
}

private var started = false

/** Idempotent so every launcher (Activity recreation, iOS controller) can call it safely. */
fun initKoin() {
    if (started) return
    started = true
    startKoin { modules(appModule) }
}
