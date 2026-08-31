@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class LoginRequest(val username: String, val password: String)

/** Either a token pair, or an MFA challenge (`mfaRequired` + `mfaToken`). */
@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val expiresAt: Instant? = null,
    val refreshToken: String? = null,
    val refreshTokenExpireAt: Instant? = null,
    val mfaRequired: Boolean = false,
    val mfaToken: String? = null,
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class RevokeRefreshTokenRequest(val refreshToken: String)

@Serializable
data class MfaVerifyRequest(val mfaToken: String, val code: String)

@Serializable
data class ExternalLoginRequest(val provider: String, val idToken: String)

@Serializable
data class RegisterUserRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val displayName: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class UpdateSelfUserRequest(
    val username: String? = null,
    val displayName: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val email: String, val token: String, val newPassword: String)

@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val email: String? = null,
    val emailConfirmed: Boolean = false,
    val displayName: String? = null,
    val timeZone: String,
    val twoFactorEnabled: Boolean = false,
    val isActive: Boolean = true,
    val roles: List<String> = emptyList(),
)

@Serializable
enum class DevicePlatform { Android, Ios, Windows, MacOs, Linux, Web }

@Serializable
data class RegisterDeviceRequest(
    val platform: DevicePlatform,
    val name: String,
    val pushToken: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class DeviceResponse(
    val installationId: String,
    val platform: DevicePlatform,
    val name: String,
    val hasPushToken: Boolean,
    val appVersion: String? = null,
    val registeredAt: Instant,
    val lastSeenAt: Instant,
)
