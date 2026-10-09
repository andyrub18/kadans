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
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import app.kadans.api.model.LoginResponse
import app.kadans.api.model.RefreshTokenRequest
import app.kadans.profile.deviceTimeZone

/**
 * Typed client for the Kadans API. Bearer tokens come from [tokenStore]; on a 401 the client
 * rotates the refresh token at `/auth/refresh` and retries once. Only a refused refresh ends the
 * session ([sessionEnded]): the server said it is over (signed out elsewhere, password changed,
 * deactivated). A server that cannot answer (a deploy restart, no network) keeps it, and the call
 * fails with [ServerUnavailableException] or the network error.
 */
class KadansApi internal constructor(
    internal val http: HttpClient,
    internal val tokenStore: TokenStore,
    private val baseUrlProvider: () -> String,
    private val languageProvider: (() -> String)? = null,
    private val timeZoneProvider: () -> String? = ::deviceTimeZone,
) {
    internal val baseUrl: String get() = baseUrlProvider().trimEnd('/')

    /** The in-app language and the device's time zone: what a new account starts with (sign-up, Google). */
    internal fun newAccountLanguage(): String? = languageProvider?.invoke()

    internal fun newAccountTimeZone(): String? = timeZoneProvider()

    val auth: AuthApi = AuthApi(this)
    val account: AccountApi = AccountApi(this)
    val todos: TodosApi = TodosApi(this)
    val pomodoro: PomodoroApi = PomodoroApi(this)
    val budget: BudgetApi = BudgetApi(this)
    val notifications: NotificationsApi = NotificationsApi(this)
    val billing: BillingApi = BillingApi(this)
    val reminders: RemindersApi = RemindersApi(this)

    private val _sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The server refused this device's session: the local one is gone, and the person signs in again. */
    val sessionEnded: SharedFlow<Unit> = _sessionEnded.asSharedFlow()

    private val _profileChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** This device changed the account's language or time zone: what the server words for it reads differently now. */
    val profileChanged: SharedFlow<Unit> = _profileChanged.asSharedFlow()

    internal fun profileChanged() {
        _profileChanged.tryEmit(Unit)
    }

    internal suspend fun endSession() {
        tokenStore.save(null)
        _sessionEnded.tryEmit(Unit)
    }

    /** Drop Ktor's cached bearer so the next request re-reads [tokenStore]. */
    internal fun invalidateTokenCache() {
        http.authProvider<BearerAuthProvider>()?.clearToken()
    }

    companion object {
        /** The answers that mean "this session is over"; anything else is the server failing to answer. */
        internal val REFRESH_REFUSED = setOf(400, 401, 403)

        fun create(
            baseUrl: String = "",
            tokenStore: TokenStore = InMemoryTokenStore(),
            engine: HttpClientEngine? = null,
            baseUrlProvider: (() -> String)? = null,
            languageProvider: (() -> String)? = null,
            timeZoneProvider: () -> String? = ::deviceTimeZone,
        ): KadansApi {
            // The provider is consulted per request, so a settings change applies immediately.
            val provider = baseUrlProvider ?: { baseUrl }
            lateinit var api: KadansApi
            val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                expectSuccess = false
                install(ContentNegotiation) { json(KadansJson) }
                install(WebSockets)
                defaultRequest {
                    url.takeFrom(provider().trimEnd('/') + "/")
                    contentType(ContentType.Application.Json)
                    // The server words its error details and validation messages in this language
                    // (en / fr / ht). Read per request, so switching language applies at once — and it
                    // works before sign-in, where the server knows no user to take a language from.
                    languageProvider?.let { headers.append(io.ktor.http.HttpHeaders.AcceptLanguage, it()) }
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
                                if (response.status.value !in REFRESH_REFUSED) throw ServerUnavailableException(response.status.value)
                                api.endSession()
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
            api = KadansApi(http, tokenStore, provider, languageProvider, timeZoneProvider)
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

/** Success with nothing to read (204 No Content); failure → [KadansApiException] as [orThrow]. */
internal suspend fun HttpResponse.orThrowNoContent() {
    if (!status.isSuccess()) orThrow<Success>()
}
