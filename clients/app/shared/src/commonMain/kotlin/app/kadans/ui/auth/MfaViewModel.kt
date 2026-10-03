package app.kadans.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MfaUiState(
    val code: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val canSubmit: Boolean get() = code.isNotBlank() && !isLoading
}

class MfaViewModel(private val api: KadansApi, private val mfaToken: String) : ViewModel() {
    private val _state = MutableStateFlow(MfaUiState())
    val state: StateFlow<MfaUiState> = _state.asStateFlow()

    /** The verified sign-in: a session, or (an account awaiting erasure) the offer to keep it. */
    private val _verified = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 1)
    val verified: SharedFlow<LoginEvent> = _verified.asSharedFlow()

    fun onCodeChange(value: String) = _state.update { it.copy(code = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val login = api.auth.verifyMfa(mfaToken, current.code.trim())
                _state.update { it.copy(isLoading = false) }
                _verified.emit(LoginViewModel.outcomeOf(login))
            } catch (e: KadansApiException) {
                _state.update { it.copy(isLoading = false, error = e.message, errorCode = e.errorCode) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = null, errorCode = "network") }
            }
        }
    }
}
