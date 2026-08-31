package app.kadans.auth

import app.kadans.api.AuthTokens
import app.kadans.api.TokenStore
import com.russhwolf.settings.Settings

/**
 * Persists the session across launches. Plain settings for now (SharedPreferences /
 * NSUserDefaults / java prefs); move to Keystore/Keychain-backed storage before release.
 */
class SettingsTokenStore(private val settings: Settings) : TokenStore {
    override suspend fun load(): AuthTokens? {
        val access = settings.getStringOrNull(ACCESS_KEY) ?: return null
        val refresh = settings.getStringOrNull(REFRESH_KEY) ?: return null
        return AuthTokens(access, refresh)
    }

    override suspend fun save(tokens: AuthTokens?) {
        if (tokens == null) {
            settings.remove(ACCESS_KEY)
            settings.remove(REFRESH_KEY)
        } else {
            settings.putString(ACCESS_KEY, tokens.accessToken)
            settings.putString(REFRESH_KEY, tokens.refreshToken)
        }
    }

    private companion object {
        const val ACCESS_KEY = "kadans.session.access"
        const val REFRESH_KEY = "kadans.session.refresh"
    }
}
