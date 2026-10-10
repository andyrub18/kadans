@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.tray

import java.awt.image.BufferedImage
import kotlin.concurrent.thread
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/** What the person asked of the tray icon. */
enum class TrayAction { Open, Quit }

/**
 * The tray icon of today's Linux desktops: a freedesktop StatusNotifierItem on the session bus, its menu over
 * com.canonical.dbusmenu (https://www.freedesktop.org/wiki/Specifications/StatusNotifierItem/). COSMIC, KDE Plasma and
 * GNOME with the AppIndicator extension (Ubuntu's default) host these. AWT's SystemTray speaks only the older XEmbed
 * protocol, which COSMIC does not host at all: there AWT finds no tray, and Kadans used to quit with its window.
 *
 * [start] answers null when no desktop host listens; the caller then tries AWT's tray, and without one the window's
 * close button quits. Actions arrive on D-Bus threads.
 */
class StatusNotifierTray private constructor(
    private val connection: DBusConnection,
    private val busName: String,
    private val menu: TrayMenu,
) : AutoCloseable {
    /** The menu's words follow the app's language. */
    fun setLabels(open: String, quit: String) = menu.setLabels(open, quit)

    override fun close() {
        runCatching { connection.releaseBusName(busName) }
        runCatching { connection.close() }
    }

    /** The watcher lists items it was told about: told again whenever it starts over (the panel restarted). */
    private fun register() {
        connection.getRemoteObject(WATCHER, WATCHER_PATH, StatusNotifierWatcher::class.java).RegisterStatusNotifierItem(busName)
    }

    private fun registerAgainWhenTheWatcherRestarts() {
        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { changed ->
            if (changed.name == WATCHER && changed.newOwner.isNotEmpty()) {
                // Not on the signal's own thread: a call that waits for its reply could block the very thread that brings it.
                thread(isDaemon = true, name = "kadans-tray-register") { runCatching { register() } }
            }
        }
    }

    companion object {
        internal const val WATCHER = "org.kde.StatusNotifierWatcher"
        internal const val WATCHER_PATH = "/StatusNotifierWatcher"

        /**
         * Shows the icon: [icons] from small to large (the host picks the size it draws), [title] as its name and tooltip.
         * Null when there is no session bus, no StatusNotifierWatcher on it, or no host showing items, and on any
         * failure: a desktop without this tray must never keep Kadans from starting. [busAddress] is for tests.
         */
        fun start(
            title: String,
            icons: List<BufferedImage>,
            openLabel: String,
            quitLabel: String,
            onAction: (TrayAction) -> Unit,
            busAddress: String? = null,
        ): StatusNotifierTray? {
            val connection = runCatching {
                val builder = if (busAddress == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(busAddress)
                builder.withShared(false).build()
            }.getOrNull() ?: return null
            return try {
                if (!hostListens(connection)) {
                    connection.close()
                    return null
                }
                val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
                val menu = TrayMenu(openLabel, quitLabel, onAction, signal = { runCatching { connection.sendMessage(it) } })
                connection.exportObject(TrayMenu.PATH, menu)
                connection.exportObject(TrayItem.PATH, TrayItem(title, icons, onAction))
                connection.requestBusName(busName)
                StatusNotifierTray(connection, busName, menu).apply {
                    registerAgainWhenTheWatcherRestarts()
                    register()
                }
            } catch (e: Exception) {
                runCatching { connection.close() }
                null
            }
        }

        private fun hostListens(connection: DBusConnection): Boolean {
            val bus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            if (!bus.NameHasOwner(WATCHER)) return false
            val watcher = connection.getRemoteObject(WATCHER, WATCHER_PATH, Properties::class.java)
            return watcher.Get<Boolean>(WATCHER, "IsStatusNotifierHostRegistered") == true
        }
    }
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
internal interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)

    fun RegisterStatusNotifierHost(service: String)
}

@DBusInterfaceName("org.kde.StatusNotifierItem")
internal interface StatusNotifierItem : DBusInterface {
    fun ContextMenu(x: Int, y: Int)

    fun Activate(x: Int, y: Int)

    fun SecondaryActivate(x: Int, y: Int)

    fun Scroll(delta: Int, orientation: String)
}

/** `(iiay)`: one size of the icon, ARGB32 in network byte order. */
internal class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val argb: ByteArray,
) : Struct() {
    companion object {
        fun of(image: BufferedImage): IconPixmap {
            val bytes = ByteArray(image.width * image.height * 4)
            var i = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    bytes[i++] = (pixel ushr 24).toByte()
                    bytes[i++] = (pixel ushr 16).toByte()
                    bytes[i++] = (pixel ushr 8).toByte()
                    bytes[i++] = pixel.toByte()
                }
            }
            return IconPixmap(image.width, image.height, bytes)
        }
    }
}

/** `(sa(iiay)ss)`: an icon name, its pictures, a title and a description. */
internal class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val iconPixmaps: List<IconPixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val description: String,
) : Struct()

/** The icon itself: a left click opens the window (some hosts show the menu instead; it opens the window too). */
internal class TrayItem(
    title: String,
    icons: List<BufferedImage>,
    private val onAction: (TrayAction) -> Unit,
) : StatusNotifierItem, Properties {
    private val properties: Map<String, Variant<*>> = mapOf(
        "Category" to Variant("ApplicationStatus"),
        "Id" to Variant("kadans"),
        "Title" to Variant(title),
        "Status" to Variant("Active"),
        "WindowId" to Variant(0),
        "IconThemePath" to Variant(""),
        "IconName" to Variant(""),
        "IconPixmap" to Variant(icons.map(IconPixmap::of), PIXMAPS),
        "OverlayIconName" to Variant(""),
        "OverlayIconPixmap" to Variant(emptyList<IconPixmap>(), PIXMAPS),
        "AttentionIconName" to Variant(""),
        "AttentionIconPixmap" to Variant(emptyList<IconPixmap>(), PIXMAPS),
        "AttentionMovieName" to Variant(""),
        "ToolTip" to Variant(ToolTip("", emptyList(), title, ""), "(sa(iiay)ss)"),
        "ItemIsMenu" to Variant(false),
        "Menu" to Variant(DBusPath(TrayMenu.PATH)),
    )

    override fun getObjectPath(): String = PATH

    override fun Activate(x: Int, y: Int) = onAction(TrayAction.Open)

    override fun SecondaryActivate(x: Int, y: Int) = onAction(TrayAction.Open)

    /** The host draws the menu from [TrayMenu] itself. */
    override fun ContextMenu(x: Int, y: Int) = Unit

    override fun Scroll(delta: Int, orientation: String) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName)[propertyName] as A? ?: throw UnknownProperty(propertyName)

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        if (interfaceName == INTERFACE) properties else throw UnknownInterface(interfaceName)

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A): Unit = throw PropertyReadOnly(propertyName)

    companion object {
        const val PATH = "/StatusNotifierItem"
        const val INTERFACE = "org.kde.StatusNotifierItem"
        private const val PIXMAPS = "a(iiay)"
    }
}
