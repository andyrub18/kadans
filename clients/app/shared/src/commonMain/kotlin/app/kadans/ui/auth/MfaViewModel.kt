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
) {
    val canSubmit: Boolean get() = code.isNotBlank() && !isLoading
}

class MfaViewModel(private val api: KadansApi, private val mfaToken: String) : ViewModel() {
    private val _state = MutableStateFlow(MfaUiState())
    val state: StateFlow<MfaUiState> = _state.asStateFlow()

    private val _verified = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val verified: SharedFlow<Unit> = _verified.asSharedFlow()

    fun onCodeChange(value: String) = _state.update { it.copy(code = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                api.auth.verifyMfa(mfaToken, current.code.trim())
                _state.update { it.copy(isLoading = false) }
                _verified.emit(Unit)
            } catch (e: KadansApiException) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Could not reach the server.") }
            }
        }
    }
}
