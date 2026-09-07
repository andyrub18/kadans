package app.kadans.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.ResetPasswordRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ForgotPasswordUiState(
    val email: String = "",
    val sent: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
)

class ForgotPasswordViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    fun update(transform: (ForgotPasswordUiState) -> ForgotPasswordUiState) {
        _state.value = transform(_state.value)
    }

    fun submit() {
        val email = _state.value.email.trim()
        if (email.isBlank()) return
        _state.value = _state.value.copy(isLoading = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                api.auth.forgotPassword(email)
                _state.value = _state.value.copy(isLoading = false, sent = true)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, errorCode = "network")
            }
        }
    }
}

data class ResetPasswordUiState(
    val newPassword: String = "",
    val done: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
)

class ResetPasswordViewModel(
    private val api: KadansApi,
    private val email: String,
    private val token: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ResetPasswordUiState())
    val state: StateFlow<ResetPasswordUiState> = _state.asStateFlow()

    fun update(transform: (ResetPasswordUiState) -> ResetPasswordUiState) {
        _state.value = transform(_state.value)
    }

    fun submit() {
        val password = _state.value.newPassword
        if (password.isBlank()) return
        _state.value = _state.value.copy(isLoading = true, error = null, errorCode = null)
        viewModelScope.launch {
            try {
                api.auth.resetPassword(ResetPasswordRequest(email, token, password))
                _state.value = _state.value.copy(isLoading = false, done = true)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, errorCode = "network")
            }
        }
    }
}
