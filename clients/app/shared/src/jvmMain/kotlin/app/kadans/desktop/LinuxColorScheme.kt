@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/** The desktop's settings portal (https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Settings.html). */
@DBusInterfaceName("org.freedesktop.portal.Settings")
internal interface PortalSettings : DBusInterface {
    /** Version 2 of the interface: the value itself. */
    fun ReadOne(namespace: String, key: String): Variant<*>

    /** Version 1: the value inside one more variant. */
    fun Read(namespace: String, key: String): Variant<*>

    class SettingChanged(path: String, val namespace: String, val key: String, val value: Variant<*>) :
        DBusSignal(path, namespace, key, value)
}

/**
 * Whether a Linux desktop is in dark mode, which Compose cannot tell there (it always says light): the settings
 * portal's `org.freedesktop.appearance` `color-scheme`, 1 preferring dark (0, no preference, and 2 are light), read at
 * start and followed as it changes. COSMIC, GNOME and KDE answer it. [dark] is the answer; the connection stays open.
 */
internal class LinuxColorScheme private constructor(private val connection: DBusConnection, initial: Boolean) : AutoCloseable {
    private val state = MutableStateFlow(initial)
    val dark: StateFlow<Boolean> = state.asStateFlow()

    init {
        connection.addSigHandler(PortalSettings.SettingChanged::class.java) { signal ->
            if (signal.namespace == NAMESPACE && signal.key == KEY) prefersDark(signal.value)?.let { state.value = it }
        }
    }

    override fun close() = connection.close()

    companion object {
        private const val NAME = "org.freedesktop.portal.Desktop"
        const val PATH = "/org/freedesktop/portal/desktop"
        const val NAMESPACE = "org.freedesktop.appearance"
        const val KEY = "color-scheme"
        private const val PREFER_DARK = 1

        /** The session's, asked once, on Linux only: null elsewhere, or with no session bus or no portal. */
        val session: LinuxColorScheme? by lazy {
            if ("linux" in System.getProperty("os.name").lowercase()) connect() else null
        }

        /** 1 is dark, any other number light; anything else (a portal that answers oddly) is no answer. */
        internal fun prefersDark(value: Any?): Boolean? = when (value) {
            is Variant<*> -> prefersDark(value.value)
            is Number -> value.toInt() == PREFER_DARK // dbus-java's UInt32 is a Number
            else -> null
        }

        /** The portal on the session bus, or on [busAddress] (tests); null with no bus, no portal or no such setting. */
        fun connect(busAddress: String? = null): LinuxColorScheme? {
            val connection = runCatching {
                val builder = if (busAddress == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(busAddress)
                builder.withShared(false).build()
            }.getOrNull() ?: return null
            return try {
                val portal = connection.getRemoteObject(NAME, PATH, PortalSettings::class.java)
                val value = runCatching { portal.ReadOne(NAMESPACE, KEY) }.getOrElse { portal.Read(NAMESPACE, KEY) }
                LinuxColorScheme(connection, prefersDark(value) ?: false)
            } catch (e: Exception) {
                runCatching { connection.close() }
                null
            }
        }
    }
}
