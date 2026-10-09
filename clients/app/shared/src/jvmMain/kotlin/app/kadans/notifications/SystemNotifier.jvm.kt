package app.kadans.notifications

import java.awt.Image
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

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
    val icon = iconFile ?: "appointment-soon"
    try {
        ProcessBuilder("notify-send", "--app-name=Kadans", "--icon=$icon", title, body).start()
    } catch (_: java.io.IOException) {
        // No libnotify-bin: talk to org.freedesktop.Notifications directly (gdbus ships with GLib).
        ProcessBuilder(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.Notifications",
            "--object-path", "/org/freedesktop/Notifications",
            "--method", "org.freedesktop.Notifications.Notify",
            "Kadans", "0", icon, title, body, "[]", "{}", "8000",
        ).start()
    }
}

/** The app icon (branding/generate.py), as the image the AWT tray and the balloons show. */
private val iconImage: BufferedImage? by lazy {
    runCatching { object {}.javaClass.getResourceAsStream("/kadans-icon.png")?.use(ImageIO::read) }.getOrNull()
}

/** The icon as a file, for Linux notifications, which take a path: written once per run to the temporary folder. */
private val iconFile: String? by lazy {
    runCatching {
        val image = iconImage ?: return@runCatching null
        File.createTempFile("kadans-icon", ".png").apply {
            deleteOnExit()
            ImageIO.write(image, "png", this)
        }.absolutePath
    }.getOrNull()
}

private val trayIcon: TrayIcon? by lazy {
    runCatching {
        if (!SystemTray.isSupported()) return@runCatching null
        val size = SystemTray.getSystemTray().trayIconSize
        val image = iconImage?.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH)
            ?: BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        TrayIcon(image, "Kadans").also { SystemTray.getSystemTray().add(it) }
    }.getOrNull()
}
