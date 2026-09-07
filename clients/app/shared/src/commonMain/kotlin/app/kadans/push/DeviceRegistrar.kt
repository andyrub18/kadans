package app.kadans.push

import app.kadans.api.KadansApi
import app.kadans.api.model.RegisterDeviceRequest
import app.kadans.config.currentPushToken
import app.kadans.config.deviceName
import app.kadans.config.devicePlatform
import com.russhwolf.settings.Settings
import kotlin.uuid.Uuid

/**
 * Upserts this install in the user's device list (`PUT /users/me/devices/{installationId}`),
 * carrying the push token where the platform has one — that's what the backend's FCM sender
 * targets. Best-effort: registration failing must never affect the session.
 */
class DeviceRegistrar(private val api: KadansApi, private val settings: Settings) {
    suspend fun register() {
        runCatching {
            val installationId = settings.getStringOrNull(KEY)
                ?: Uuid.random().toString().also { settings.putString(KEY, it) }
            api.account.registerDevice(
                installationId,
                RegisterDeviceRequest(devicePlatform(), deviceName(), currentPushToken()),
            )
        }
    }

    private companion object {
        const val KEY = "kadans.installation"
    }
}
