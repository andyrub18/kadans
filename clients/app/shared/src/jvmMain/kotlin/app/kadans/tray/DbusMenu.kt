@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.tray

import java.util.concurrent.atomic.AtomicLong
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** The tray's menu as the desktop reads it: libdbusmenu's protocol (the hosts render it themselves). */
@DBusInterfaceName("com.canonical.dbusmenu")
internal interface DbusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply2<UInt32, MenuLayout>

    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>

    fun GetProperty(id: Int, name: String): Variant<*>

    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    fun EventGroup(events: List<MenuEvent>): List<Int>

    fun AboutToShow(id: Int): Boolean

    fun AboutToShowGroup(ids: List<Int>): Reply2<List<Int>, List<Int>>

    /** The menu changed: hosts read its layout again. */
    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/** `(ia{sv}av)`: an item, its properties and its children (each a variant holding the same structure). */
internal class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

/**
 * A method's two out arguments. Generic on purpose: dbus-java reads their D-Bus types from the method's return type,
 * and from a plain class's fields it would see `List`, not `List<Int>`. (dbus-java 5.2.1 lists them twice in its
 * introspection XML; the replies themselves are right, and hosts call the protocol, not the XML.)
 */
internal class Reply2<A, B>(
    @field:Position(0) val first: A,
    @field:Position(1) val second: B,
) : Tuple()

internal class MenuItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

internal class MenuEvent(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32,
) : Struct()

/** "Open Kadans", a separator, "Quit": the same two choices AWT's tray menu offers. */
internal class TrayMenu(
    @Volatile private var openLabel: String,
    @Volatile private var quitLabel: String,
    private val onAction: (TrayAction) -> Unit,
    private val signal: (DBusSignal) -> Unit,
) : DbusMenu, Properties {
    private val revision = AtomicLong(1)

    override fun getObjectPath(): String = PATH

    /** The labels follow the app's language: hosts are told to read the menu again. */
    fun setLabels(open: String, quit: String) {
        if (open == openLabel && quit == quitLabel) return
        openLabel = open
        quitLabel = quit
        signal(DbusMenu.LayoutUpdated(PATH, UInt32(revision.incrementAndGet()), ROOT))
    }

    private fun properties(id: Int): Map<String, Variant<*>> = when (id) {
        ROOT -> mapOf("children-display" to Variant("submenu"))
        OPEN -> mapOf("label" to Variant(openLabel))
        SEPARATOR -> mapOf("type" to Variant("separator"))
        QUIT -> mapOf("label" to Variant(quitLabel))
        else -> emptyMap()
    }

    /** [depth] -1 is every level, 0 the item alone. */
    private fun layout(id: Int, depth: Int): MenuLayout {
        val children = if (id == ROOT && depth != 0) ITEMS.map { Variant(layout(it, depth - 1), LAYOUT_SIGNATURE) } else emptyList()
        return MenuLayout(id, properties(id), children)
    }

    override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>) =
        Reply2(UInt32(revision.get()), layout(parentId, recursionDepth))

    override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>) = ids.map { MenuItemProperties(it, properties(it)) }

    override fun GetProperty(id: Int, name: String): Variant<*> = properties(id)[name] ?: throw UnknownProperty(name)

    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        if (eventId != "clicked") return
        when (id) {
            OPEN -> onAction(TrayAction.Open)
            QUIT -> onAction(TrayAction.Quit)
        }
    }

    override fun EventGroup(events: List<MenuEvent>): List<Int> {
        events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
        return emptyList()
    }

    override fun AboutToShow(id: Int) = false

    /** (ids whose menus need an update, ids not found): none of either. */
    override fun AboutToShowGroup(ids: List<Int>) = Reply2<List<Int>, List<Int>>(emptyList(), emptyList())

    private val menuProperties: Map<String, Variant<*>> = mapOf(
        "Version" to Variant(UInt32(3)),
        "TextDirection" to Variant("ltr"),
        "Status" to Variant("normal"),
        "IconThemePath" to Variant(emptyList<String>(), "as"),
    )

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName)[propertyName] as A? ?: throw UnknownProperty(propertyName)

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        if (interfaceName == INTERFACE) menuProperties else throw UnknownInterface(interfaceName)

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A): Unit = throw PropertyReadOnly(propertyName)

    companion object {
        const val PATH = "/MenuBar"
        const val INTERFACE = "com.canonical.dbusmenu"
        const val LAYOUT_SIGNATURE = "(ia{sv}av)"
        const val ROOT = 0
        const val OPEN = 1
        const val SEPARATOR = 2
        const val QUIT = 3
        val ITEMS = listOf(OPEN, SEPARATOR, QUIT)
    }
}
