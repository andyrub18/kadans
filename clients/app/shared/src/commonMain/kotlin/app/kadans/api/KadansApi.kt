package app.kadans.api

import app.kadans.api.model.ApiProblem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProvider
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import app.kadans.api.model.LoginResponse
import app.kadans.api.model.RefreshTokenRequest

/**
 * Typed client for the Kadans API. Bearer tokens come from [tokenStore]; on a 401 the client
 * rotates the refresh token at `/auth/refresh` and retries once. A refresh failure clears the
 * session (the family was revoked server-side; the user signs in again).
 */
class KadansApi internal constructor(
    internal val http: HttpClient,
    internal val tokenStore: TokenStore,
) {
    val auth: AuthApi = AuthApi(this)
    val account: AccountApi = AccountApi(this)
    val todos: TodosApi = TodosApi(this)
    val pomodoro: PomodoroApi = PomodoroApi(this)
    val notifications: NotificationsApi = NotificationsApi(this)

    /** Drop Ktor's cached bearer so the next request re-reads [tokenStore]. */
    internal fun invalidateTokenCache() {
        http.authProvider<BearerAuthProvider>()?.clearToken()
    }

    companion object {
        fun create(
            baseUrl: String,
            tokenStore: TokenStore = InMemoryTokenStore(),
            engine: HttpClientEngine? = null,
        ): KadansApi {
            lateinit var api: KadansApi
            val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                expectSuccess = false
                install(ContentNegotiation) { json(KadansJson) }
                defaultRequest {
                    url.takeFrom(baseUrl.trimEnd('/') + "/")
                    contentType(ContentType.Application.Json)
                }
                install(Auth) {
                    bearer {
                        loadTokens {
                            tokenStore.load()?.let { BearerTokens(it.accessToken, it.refreshToken) }
                        }
                        refreshTokens {
                            val current = tokenStore.load() ?: return@refreshTokens null
                            val response = client.post("auth/refresh") {
                                markAsRefreshTokenRequest()
                                contentType(ContentType.Application.Json)
                                setBody(RefreshTokenRequest(current.refreshToken))
                            }
                            if (!response.status.isSuccess()) {
                                tokenStore.save(null)
                                return@refreshTokens null
                            }
                            val login = response.body<LoginResponse>()
                            val rotated = AuthTokens(login.accessToken!!, login.refreshToken!!)
                            tokenStore.save(rotated)
                            BearerTokens(rotated.accessToken, rotated.refreshToken)
                        }
                    }
                }
            }
            val http = if (engine is HttpClientEngine) HttpClient(engine, { configure() }) else HttpClient { configure() }
            api = KadansApi(http, tokenStore)
            return api
        }
    }
}

/** Success → typed body; failure → [KadansApiException] carrying the ProblemDetails. */
internal suspend inline fun <reified T> HttpResponse.orThrow(): T {
    if (status.isSuccess()) return body()

    val problem = try {
        KadansJson.decodeFromString(ApiProblem.serializer(), bodyAsText())
    } catch (_: Exception) {
        null
    }
    throw KadansApiException(status.value, problem)
}
