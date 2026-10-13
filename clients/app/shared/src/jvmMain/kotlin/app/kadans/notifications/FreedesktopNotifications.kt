@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.notifications

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** The desktop's notification server (https://specifications.freedesktop.org/notification-spec/latest/). */
@DBusInterfaceName("org.freedesktop.Notifications")
internal interface Notifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    fun GetCapabilities(): List<String>

    fun CloseNotification(id: UInt32)

    /** A button, or the notification itself ("default"), was clicked. */
    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)

    /** Gone: expired, dismissed, or closed by an app. Its buttons went with it. */
    class NotificationClosed(path: String, val id: UInt32, val reason: UInt32) : DBusSignal(path, id, reason)
}

/**
 * Notifications with buttons on the Linux desktop, over D-Bus: [show] gives the notification's id, and a click on one of
 * its buttons, or on the notification ([DEFAULT]), reaches its handler, off D-Bus's own thread. [connect] answers null
 * when there is no session bus or no notification server: the caller then uses `notify-send`, without buttons.
 */
internal class FreedesktopNotifications private constructor(
    private val connection: DBusConnection,
    private val server: Notifications,
    capabilities: List<String>,
) : AutoCloseable {
    /** The server shows buttons and reports clicks (COSMIC, GNOME, KDE do). */
    val clickable = "actions" in capabilities

    /** The server reads the body as markup: a todo's "<" or "&" must reach it escaped. */
    private val markup = "body-markup" in capabilities

    private val handlers = ConcurrentHashMap<Long, (String) -> Unit>()
    private val calls = Executors.newSingleThreadExecutor { Thread(it, "kadans-notifications").apply { isDaemon = true } }

    init {
        connection.addSigHandler(Notifications.ActionInvoked::class.java) { signal ->
            val handler = handlers.remove(signal.id.toLong()) ?: return@addSigHandler
            // Not on the signal's thread: a handler that calls the server back would wait on the thread bringing its reply.
            calls.execute { runCatching { handler(signal.actionKey) } }
        }
        connection.addSigHandler(Notifications.NotificationClosed::class.java) { signal -> handlers.remove(signal.id.toLong()) }
    }

    /**
     * Shows a notification with [buttons] (key to label) where the server has them; [onClick] gets the key clicked, or
     * [DEFAULT] for the notification itself. [replaces]: one of this app's it takes the place of. Null when refused.
     */
    fun show(
        title: String,
        body: String,
        icon: String,
        buttons: List<Pair<String, String>> = emptyList(),
        replaces: Long = 0,
        onClick: ((String) -> Unit)? = null,
    ): Long? = runCatching {
        val actions = buildList {
            if (onClick != null && clickable) {
                add(DEFAULT)
                add("")
                buttons.forEach { (key, label) ->
                    add(key)
                    add(label)
                }
            }
        }
        val hints = mapOf("desktop-entry" to Variant(DESKTOP_ENTRY), "urgency" to Variant(NORMAL))
        val id = server.Notify(APP_NAME, UInt32(replaces), icon, title, if (markup) escape(body) else body, actions, hints, SERVER_DEFAULT).toLong()
        if (onClick != null && clickable) handlers[id] = onClick
        id
    }.getOrNull()

    fun close(id: Long) {
        handlers.remove(id)
        runCatching { server.CloseNotification(UInt32(id)) }
    }

    override fun close() {
        calls.shutdown()
        runCatching { connection.close() }
    }

    companion object {
        /** The key of a click on the notification itself. */
        const val DEFAULT = "default"

        private const val PATH = "/org/freedesktop/Notifications"
        private const val NAME = "org.freedesktop.Notifications"
        private const val APP_NAME = "Kadans"

        /** The installed launcher (kadans/Kadans.kt): the server shows the app's own name and icon with the notification. */
        private const val DESKTOP_ENTRY = "kadans-Kadans"
        private const val NORMAL: Byte = 1
        private const val SERVER_DEFAULT = -1

        /** The session's notification server, or null: no bus, no server, any failure. [busAddress] is for tests. */
        fun connect(busAddress: String? = null): FreedesktopNotifications? {
            val connection = runCatching {
                val builder = if (busAddress == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(busAddress)
                builder.withShared(false).build()
            }.getOrNull() ?: return null
            return try {
                val server = connection.getRemoteObject(NAME, PATH, Notifications::class.java)
                FreedesktopNotifications(connection, server, server.GetCapabilities())
            } catch (e: Exception) {
                runCatching { connection.close() }
                null
            }
        }

        /** The body as markup reads it: the person's own words, never tags. */
        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
