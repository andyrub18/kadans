package app.kadans.config

import app.kadans.api.model.DevicePlatform

expect fun devicePlatform(): DevicePlatform

expect fun deviceName(): String

/** The push token (FCM), where the platform has push wired; null elsewhere. */
expect suspend fun currentPushToken(): String?
