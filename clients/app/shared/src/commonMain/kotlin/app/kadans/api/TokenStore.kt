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
