package app.kadans.api

data class AuthTokens(val accessToken: String, val refreshToken: String)

/** Where the session lives. Platform implementations persist it (Keychain/Keystore/file). */
interface TokenStore {
    suspend fun load(): AuthTokens?

    suspend fun save(tokens: AuthTokens?)
}

class InMemoryTokenStore(private var tokens: AuthTokens? = null) : TokenStore {
    override suspend fun load(): AuthTokens? = tokens

    override suspend fun save(tokens: AuthTokens?) {
        this.tokens = tokens
    }
}

/**
 * [inner], plus [onCleared] whenever the session is forgotten. Every way out of a session ends in `save(null)`: signing
 * out, signing out everywhere, a password change, an account deletion, a refresh the server refused.
 */
class ClearAwareTokenStore(private val inner: TokenStore, private val onCleared: suspend () -> Unit) : TokenStore {
    override suspend fun load(): AuthTokens? = inner.load()

    override suspend fun save(tokens: AuthTokens?) {
        inner.save(tokens)
        if (tokens == null) onCleared()
    }
}
