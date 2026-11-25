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
 * balloon does the job. Kadans' own [sound] flags the change when the user's eyes are off the screen (the desktop
 * shows a notification without one); a notification that asks for none gets the system's beep, as it always did.
 * One with a sound (a focus session's phase ending) may keep ringing until answered: where the desktop tells a click
 * from a dismissal, either is an answer, and the click opens [link] (the session).
 */
actual fun showSystemNotification(title: String, body: String, sound: app.kadans.sound.KadansSound?, link: String?) {
    if (sound == null) {
        showDesktopNotification(title, body)
        runCatching { Toolkit.getDefaultToolkit().beep() }
        return
    }
    app.kadans.sound.DesktopSound.notice(sound)
    val answered = { app.kadans.sound.DesktopSound.answered() }
    val linux = if ("linux" in System.getProperty("os.name").lowercase()) app.kadans.reminders.DesktopReminderNotices.session else null
    val shown = linux?.takeIf { it.clickable }?.show(title, body, notificationIcon, onDismissed = answered) { key ->
        answered()
        if (key == FreedesktopNotifications.DEFAULT) {
            val open = link?.takeIf { it.startsWith("kadans://") }
            if (open != null) app.kadans.desktop.DesktopWindow.open(open) else app.kadans.desktop.DesktopWindow.show()
        }
    }
    if (shown == null) showDesktopNotification(title, body)
}

/** The notification alone, with no sound of any kind: for what plays its own (a reminder, a cycle's notice). */
internal fun showDesktopNotification(title: String, body: String) {
    runCatching {
        val os = System.getProperty("os.name").lowercase()
        if ("linux" in os) {
            linuxNotify(title, body)
        } else {
            balloonIcon()?.displayMessage(title, body, TrayIcon.MessageType.INFO)
        }
    }
}

actual fun showCycleNotice(notice: app.kadans.pomodoro.CycleNotice) = app.kadans.pomodoro.DesktopCycleNotices.shared.show(notice)

/** The app's icon for a Linux notification (a file the server reads), or a themed stand-in. */
internal val notificationIcon: String get() = iconFile ?: "appointment-soon"

private fun linuxNotify(title: String, body: String) {
    val icon = notificationIcon
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

/** The app's own tray icon (the window's, with its menu) when it shows one; a second icon would sit next to it. */
private fun balloonIcon(): TrayIcon? = runCatching {
    if (!SystemTray.isSupported()) return null
    SystemTray.getSystemTray().trayIcons.firstOrNull() ?: ownTrayIcon
}.getOrNull()

/** For balloons when the app shows no tray icon of its own. */
private val ownTrayIcon: TrayIcon? by lazy {
    runCatching {
        val size = SystemTray.getSystemTray().trayIconSize
        val image = iconImage?.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH)
            ?: BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        TrayIcon(image, "Kadans").also { SystemTray.getSystemTray().add(it) }
    }.getOrNull()
}
