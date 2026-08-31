package app.kadans.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.RegisterUserRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RegisterUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = username.isNotBlank() && email.isNotBlank() && password.isNotBlank() && !isLoading
}

class RegisterViewModel(private val api: KadansApi) : ViewModel() {
    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    private val _registered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val registered: SharedFlow<Unit> = _registered.asSharedFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                api.auth.register(
                    RegisterUserRequest(
                        username = current.username.trim(),
                        password = current.password,
                        email = current.email.trim(),
                        displayName = current.displayName.trim().ifBlank { null },
                    )
                )
                _state.update { it.copy(isLoading = false) }
                _registered.emit(Unit)
            } catch (e: KadansApiException) {
                val details = e.problem?.errors?.mapNotNull { it.message }?.joinToString("\n")
                _state.update { it.copy(isLoading = false, error = details?.ifBlank { null } ?: e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Could not reach the server.") }
            }
        }
    }
}
