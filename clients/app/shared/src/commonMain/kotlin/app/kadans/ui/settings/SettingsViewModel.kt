package app.kadans.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.DeviceResponse
import app.kadans.api.model.MfaEnrollResponse
import app.kadans.api.model.UpdateSelfUserRequest
import app.kadans.api.model.UserResponse
import app.kadans.profile.TimeZoneCatalog
import app.kadans.profile.TimeZoneEntry
import app.kadans.profile.TimeZonePreference
import app.kadans.profile.deviceTimeZone
import app.kadans.push.DeviceRegistrar
import app.kadans.realtime.KadansRealtime
import kotlin.time.Clock
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
    val profileSaved: Boolean = false,
    // time zone: followed from this device, or picked
    val followDevice: Boolean = true,
    /** This device's zone when usable (see deviceTimeZone); null: it reports none, so the person picks. */
    val deviceZone: String? = null,
    val zonePickerOpen: Boolean = false,
    val timeZoneSaved: Boolean = false,
    // email
    val newEmail: String = "",
    /** The password, asked again for an email change (not for accounts that only use Google). */
    val emailPassword: String = "",
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
        get() = !isBusy && newEmail.trim().let { it.contains('@') && !it.equals(user?.email, ignoreCase = true) } &&
            (user?.hasPassword != true || emailPassword.isNotEmpty())

    /** Following needs a device zone worth following; otherwise the list is the only way. */
    val followingDevice: Boolean get() = followDevice && deviceZone != null
}

class SettingsViewModel(
    private val api: KadansApi,
    private val realtime: KadansRealtime,
    private val deviceRegistrar: DeviceRegistrar,
    private val timeZones: TimeZonePreference,
    private val deviceZone: () -> String? = ::deviceTimeZone,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** The picker's list, built once: about 400 zones with their current offset. */
    val timeZoneChoices: List<TimeZoneEntry> by lazy { TimeZoneCatalog.entries(Clock.System.now()) }

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
                    followDevice = timeZones.followsDevice,
                    deviceZone = deviceZone(),
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
            ),
        )
        _state.value = _state.value.copy(
            user = user,
            username = user.username,
            displayName = user.displayName ?: "",
            profileSaved = true,
        )
    }

    /** Remembered for this install. Turning it on applies the device's zone right away. */
    fun setFollowDevice(follow: Boolean) {
        timeZones.followsDevice = follow
        _state.value = _state.value.copy(followDevice = follow, timeZoneSaved = false)
        val zone = _state.value.deviceZone
        if (follow && zone != null && zone != _state.value.user?.timeZone) saveTimeZone(zone)
    }

    fun openTimeZonePicker() {
        _state.value = _state.value.copy(zonePickerOpen = true, timeZoneSaved = false)
    }

    fun closeTimeZonePicker() {
        _state.value = _state.value.copy(zonePickerOpen = false)
    }

    /** A zone picked by hand: this install stops following the device, or the next start would undo the choice. */
    fun chooseTimeZone(id: String) {
        timeZones.followsDevice = false
        _state.value = _state.value.copy(followDevice = false, zonePickerOpen = false)
        if (id != _state.value.user?.timeZone) saveTimeZone(id)
    }

    private fun saveTimeZone(id: String) = busy {
        val user = api.account.update(UpdateSelfUserRequest(timeZone = id))
        _state.value = _state.value.copy(user = user, timeZoneSaved = true)
    }

    /** Nothing changes yet: the server mails a link to the new address and applies the change when it is opened. */
    fun requestEmailChange() = busy {
        val target = _state.value.newEmail.trim()
        val password = _state.value.emailPassword.takeIf { _state.value.user?.hasPassword == true }
        api.account.requestEmailChange(target, password)
        _state.value = _state.value.copy(newEmail = "", emailPassword = "", emailChangeSentTo = target, confirmationResent = false)
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
            _state.value = _state.value.copy(isBusy = true, error = null, errorCode = null, profileSaved = false, timeZoneSaved = false)
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
