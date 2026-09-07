package app.kadans.config

import app.kadans.api.model.DevicePlatform

actual fun devicePlatform(): DevicePlatform {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> DevicePlatform.MacOs
        "win" in os -> DevicePlatform.Windows
        else -> DevicePlatform.Linux
    }
}

actual fun deviceName(): String =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?: System.getProperty("os.name")

// Desktop has no push channel; the SignalR connection is its live feed.
actual suspend fun currentPushToken(): String? = null
