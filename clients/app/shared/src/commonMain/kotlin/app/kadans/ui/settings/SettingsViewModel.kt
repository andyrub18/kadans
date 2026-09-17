package app.kadans.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.DeviceResponse
import app.kadans.api.model.MfaEnrollResponse
import app.kadans.api.model.UpdateSelfUserRequest
import app.kadans.api.model.UserResponse
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.KadansRealtime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val user: UserResponse? = null,
    // profile form
    val username: String = "",
    val displayName: String = "",
    val timeZone: String = "",
    val profileSaved: Boolean = false,
    // email
    val newEmail: String = "",
    /** The address a change link was just sent to; the change itself happens when that link is opened. */
    val emailChangeSentTo: String? = null,
    val confirmationResent: Boolean = false,
    // devices
    val devices: List<DeviceResponse> = emptyList(),
    val thisInstallationId: String? = null,
    // password form
    val currentPassword: String = "",
    val newPassword: String = "",
    // MFA flow
    val enrollment: MfaEnrollResponse? = null,
    val mfaCode: String = "",
    val recoveryCodes: List<String>? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val canRequestEmailChange: Boolean
        get() = !isBusy && newEmail.trim().let { it.contains('@') && !it.equals(user?.email, ignoreCase = true) }
}

class SettingsViewModel(
    private val api: KadansApi,
    private val realtime: KadansRealtime,
    private val deviceRegistrar: DeviceRegistrar,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** The local session ended (sign-out, or password change revoked it) — go to Login. */
    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val user = api.account.me()
                _state.value = _state.value.copy(
                    user = user,
                    username = user.username,
                    displayName = user.displayName ?: "",
                    timeZone = user.timeZone,
                    isLoading = false,
                    error = null,
                    errorCode = null,
                    thisInstallationId = deviceRegistrar.installationId(),
                )
                refreshDevicesQuietly()
            } catch (e: KadansApiException) {
                if (e.httpStatus == 401) _loggedOut.emit(Unit)
                else _state.value = _state.value.copy(isLoading = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, errorCode = "network")
            }
        }
    }

    fun update(transform: (SettingsUiState) -> SettingsUiState) {
        _state.value = transform(_state.value).copy(profileSaved = false)
    }

    fun saveProfile() = busy {
        val current = _state.value
        val user = api.account.update(
            UpdateSelfUserRequest(
                username = current.username.trim().takeIf { it.isNotBlank() && it != current.user?.username },
                displayName = current.displayName.trim().takeIf { it != (current.user?.displayName ?: "") },
                timeZone = current.timeZone.trim().takeIf { it.isNotBlank() && it != current.user?.timeZone },
            ),
        )
        _state.value = _state.value.copy(
            user = user,
            username = user.username,
            displayName = user.displayName ?: "",
            timeZone = user.timeZone,
            profileSaved = true,
        )
    }

    /** Nothing changes yet: the server mails a link to the new address and applies the change when it is opened. */
    fun requestEmailChange() = busy {
        val target = _state.value.newEmail.trim()
        api.account.requestEmailChange(target)
        _state.value = _state.value.copy(newEmail = "", emailChangeSentTo = target, confirmationResent = false)
    }

    fun resendConfirmation() = busy {
        val email = _state.value.user?.email ?: return@busy
        api.account.resendConfirmation(email)
        _state.value = _state.value.copy(confirmationResent = true)
    }

    /** Signs that device out of push; it comes back by itself the next time someone signs in on it. */
    fun removeDevice(installationId: String) = busy {
        api.account.removeDevice(installationId)
        _state.value = _state.value.copy(devices = _state.value.devices.filterNot { it.installationId == installationId })
    }

    /** The server revokes every session on success; the client session is gone too. */
    fun changePassword() = busy {
        val current = _state.value
        api.account.changePassword(current.currentPassword, current.newPassword)
        realtime.stop()
        _loggedOut.emit(Unit)
    }

    fun startMfaEnrollment() = busy {
        val enrollment = api.account.mfaEnroll()
        _state.value = _state.value.copy(enrollment = enrollment, mfaCode = "", recoveryCodes = null)
    }

    fun confirmMfa() = busy {
        val codes = api.account.mfaEnable(_state.value.mfaCode.trim())
        _state.value = _state.value.copy(enrollment = null, mfaCode = "", recoveryCodes = codes.codes)
        refreshUserQuietly()
    }

    fun disableMfa() = busy {
        api.account.mfaDisable(_state.value.mfaCode.trim())
        _state.value = _state.value.copy(mfaCode = "", recoveryCodes = null)
        refreshUserQuietly()
    }

    fun regenerateRecoveryCodes() = busy {
        val codes = api.account.regenerateRecoveryCodes(_state.value.mfaCode.trim())
        _state.value = _state.value.copy(mfaCode = "", recoveryCodes = codes.codes)
    }

    fun signOutEverywhere() = busy {
        api.account.revokeAllSessions()
        realtime.stop()
        runCatching { api.auth.logout() }
        _loggedOut.emit(Unit)
    }

    fun signOut() {
        viewModelScope.launch {
            realtime.stop()
            runCatching { api.auth.logout() }
            _loggedOut.emit(Unit)
        }
    }

    /** The list is a convenience: failing to load it must not turn Settings into an error screen. */
    private suspend fun refreshDevicesQuietly() {
        runCatching { api.account.devices() }.onSuccess { devices ->
            _state.value = _state.value.copy(devices = sortDevices(devices, _state.value.thisInstallationId))
        }
    }

    private suspend fun refreshUserQuietly() {
        runCatching { api.account.me() }.onSuccess { _state.value = _state.value.copy(user = it) }
    }

    private fun busy(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null, errorCode = null, profileSaved = false)
            try {
                action()
                _state.value = _state.value.copy(isBusy = false)
            } catch (e: KadansApiException) {
                _state.value = _state.value.copy(isBusy = false, error = e.message, errorCode = e.errorCode)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isBusy = false, errorCode = "network")
            }
        }
    }

    internal companion object {
        /** This device first, then the most recently seen. */
        fun sortDevices(devices: List<DeviceResponse>, thisInstallationId: String?): List<DeviceResponse> =
            devices.sortedWith(compareByDescending<DeviceResponse> { it.installationId == thisInstallationId }.thenByDescending { it.lastSeenAt })
    }
}
