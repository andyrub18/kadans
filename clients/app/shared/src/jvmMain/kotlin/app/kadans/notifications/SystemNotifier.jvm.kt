package app.kadans.notifications

import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Linux desktops get a freedesktop notification (`notify-send`, or `gdbus` when libnotify-bin
 * isn't installed) — GNOME shows those even with no tray support. Elsewhere an AWT tray icon's
 * balloon does the job. A beep flags the change when the user's eyes are off the screen.
 */
actual fun showSystemNotification(title: String, body: String) {
    runCatching {
        val os = System.getProperty("os.name").lowercase()
        if ("linux" in os) {
            linuxNotify(title, body)
        } else {
            trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
        }
    }
    runCatching { Toolkit.getDefaultToolkit().beep() }
}

private fun linuxNotify(title: String, body: String) {
    try {
        ProcessBuilder("notify-send", "--app-name=Kadans", "--icon=appointment-soon", title, body).start()
    } catch (_: java.io.IOException) {
        // No libnotify-bin: talk to org.freedesktop.Notifications directly (gdbus ships with GLib).
        ProcessBuilder(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.Notify",
            "Kadans", "0", "appointment-soon", title, body, "[]", "{}", "8000",
        ).start()
    }
}

private val trayIcon: TrayIcon? by lazy {
    runCatching {
        if (!SystemTray.isSupported()) return@runCatching null
        val size = SystemTray.getSystemTray().trayIconSize
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = java.awt.Color(0x67, 0x50, 0xA4)
            g.fillOval(0, 0, size.width, size.height)
            g.dispose()
        }
        TrayIcon(image, "Kadans").also { SystemTray.getSystemTray().add(it) }
    }.getOrNull()
}
