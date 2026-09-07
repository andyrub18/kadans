package app.kadans.config

import app.kadans.api.model.DevicePlatform
import platform.UIKit.UIDevice

actual fun devicePlatform(): DevicePlatform = DevicePlatform.Ios

actual fun deviceName(): String = UIDevice.currentDevice.name

// APNs needs an Apple Developer account (owner checklist); until then no token.
actual suspend fun currentPushToken(): String? = null
