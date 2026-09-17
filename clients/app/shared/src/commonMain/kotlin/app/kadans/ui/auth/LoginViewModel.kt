package app.kadans.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.GoogleProviderResponse
import app.kadans.api.model.LoginResponse
import app.kadans.auth.GoogleCredential
import app.kadans.auth.GoogleSignIn
import app.kadans.auth.GoogleSignInException
import app.kadans.auth.NoGoogleSignIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    /** The server published the client id this platform's Google flow needs. */
    val googleAvailable: Boolean = false,
    /** The user is in Google's UI (browser on desktop, system sheet on Android). */
    val isGoogleLoading: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotBlank() && !isLoading && !isGoogleLoading
}

sealed interface LoginEvent {
    data object LoggedIn : LoginEvent

    data class MfaRequired(val mfaToken: String) : LoginEvent
}

class LoginViewModel(
    private val api: KadansApi,
    private val googleSignIn: GoogleSignIn = NoGoogleSignIn,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LoginEvent> = _events.asSharedFlow()

    private var googleConfig: GoogleProviderResponse? = null
    private var googleJob: Job? = null

    init {
        refreshProviders()
    }

    /**
     * Ask the server what it can complete; the button only exists when the answer fits this
     * platform. Best effort — an unreachable server simply means no button. Called again when
     * the server address changes on the Login screen.
     */
    fun refreshProviders() {
        if (!googleSignIn.isSupported) return
        viewModelScope.launch {
            val google = runCatching { api.auth.providers().google }.getOrNull()
            googleConfig = google
            _state.update { it.copy(googleAvailable = google != null && googleSignIn.canSignIn(google)) }
        }
    }

    fun signInWithGoogle() {
        val config = googleConfig ?: return
        if (_state.value.isGoogleLoading || _state.value.isLoading) return

        _state.update { it.copy(isGoogleLoading = true, error = null, errorCode = null) }
        googleJob = viewModelScope.launch {
            try {
                val login = when (val credential = googleSignIn.signIn(config)) {
                    null -> null // the user backed out: not an error
                    is GoogleCredential.IdToken -> api.auth.loginExternal("google", credential.idToken)
                    is GoogleCredential.AuthorizationCode ->
                        api.auth.loginGoogleCode(credential.code, credential.codeVerifier, credential.redirectUri)
                }
                _state.update { it.copy(isGoogleLoading = false) }
                if (login != null) emitOutcome(login)
            } catch (e: KadansApiException) {
                _state.update { it.copy(isGoogleLoading = false, error = e.message, errorCode = e.errorCode) }
            } catch (e: GoogleSignInException) {
                _state.update { it.copy(isGoogleLoading = false, error = null, errorCode = "google") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(isGoogleLoading = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isGoogleLoading = false, error = null, errorCode = "network") }
            }
        }
    }

    /** Desktop: the user closed the browser tab instead of finishing — stop waiting for the redirect. */
    fun cancelGoogle() {
        googleJob?.cancel()
    }

    private suspend fun emitOutcome(login: LoginResponse) {
        if (login.mfaRequired && login.mfaToken != null) _events.emit(LoginEvent.MfaRequired(login.mfaToken))
        else _events.emit(LoginEvent.LoggedIn)
    }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val login = api.auth.login(current.username.trim(), current.password)
                _state.update { it.copy(isLoading = false, password = "") }
                emitOutcome(login)
            } catch (e: KadansApiException) {
                _state.update { it.copy(isLoading = false, error = e.message, errorCode = e.errorCode) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = null, errorCode = "network") }
            }
        }
    }
}
