@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.tray

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * The Linux tray against a private bus (dbus-java's own daemon) and a fake panel playing the StatusNotifierWatcher:
 * no desktop needed, as on CI. Checked by hand against COSMIC's panel: ARCHITECTURE → "The desktop app: in the tray, once".
 */
class StatusNotifierTrayTests {
    private val folder = Files.createTempDirectory("kadans-bus")
    private val address = "unix:path=${folder.resolve("bus")}"
    private lateinit var daemon: EmbeddedDBusDaemon
    private val panels = mutableListOf<DBusConnection>()
    private val trays = mutableListOf<StatusNotifierTray>()
    private val actions = LinkedBlockingQueue<TrayAction>()

    /** The panel's side: the watcher items register with, which also reads them as a host does. */
    private class FakeWatcher(private val hostRegistered: Boolean) : StatusNotifierWatcher, Properties {
        val registered = LinkedBlockingQueue<String>()

        override fun getObjectPath() = StatusNotifierTray.WATCHER_PATH

        override fun RegisterStatusNotifierItem(service: String) {
            registered.add(service)
        }

        override fun RegisterStatusNotifierHost(service: String) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            GetAll(interfaceName)[propertyName] as A? ?: throw UnknownProperty(propertyName)

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
            mapOf("IsStatusNotifierHostRegistered" to Variant(hostRegistered))

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A): Unit = throw PropertyReadOnly(propertyName)
    }

    @BeforeTest
    fun startBus() {
        daemon = EmbeddedDBusDaemon("$address,listen=true")
        daemon.startInBackgroundAndWait(10_000)
    }

    @AfterTest
    fun stopBus() {
        trays.forEach { it.close() }
        panels.forEach { runCatching { it.close() } }
        daemon.close()
        folder.toFile().deleteRecursively()
    }

    private fun connect(): DBusConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { panels += it }

    private fun panel(hostRegistered: Boolean = true): Pair<DBusConnection, FakeWatcher> {
        val connection = connect()
        val watcher = FakeWatcher(hostRegistered)
        connection.exportObject(StatusNotifierTray.WATCHER_PATH, watcher)
        connection.requestBusName(StatusNotifierTray.WATCHER)
        return connection to watcher
    }

    private fun icon(size: Int, argb: Int) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        for (x in 0 until size) for (y in 0 until size) setRGB(x, y, argb)
    }

    private fun start(open: String = "Open Kadans", quit: String = "Quit"): StatusNotifierTray? =
        StatusNotifierTray.start("Kadans", listOf(icon(16, 0xFF6750A4.toInt()), icon(32, 0xFF6750A4.toInt())), open, quit, { actions.add(it) }, address)
            ?.also { trays += it }

    private fun <T> LinkedBlockingQueue<T>.next(): T = assertNotNull(poll(5, TimeUnit.SECONDS), "nothing arrived within 5 s")

    private fun menuOf(connection: DBusConnection, item: String) = connection.getRemoteObject(item, TrayMenu.PATH, DbusMenu::class.java)

    private fun labels(menu: DbusMenu) =
        menu.GetGroupProperties(TrayMenu.ITEMS, emptyList()).map { it.properties["label"]?.value ?: it.properties["type"]?.value }

    @Test
    fun the_icon_registers_with_the_panel_and_reads_as_kadans() {
        val (connection, watcher) = panel()
        assertNotNull(start())

        val item = watcher.registered.next()
        assertEquals("org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1", item)
        val properties = connection.getRemoteObject(item, TrayItem.PATH, Properties::class.java).GetAll(TrayItem.INTERFACE)
        assertEquals("kadans", properties["Id"]?.value)
        assertEquals("Kadans", properties["Title"]?.value)
        assertEquals("Active", properties["Status"]?.value)
        assertEquals(false, properties["ItemIsMenu"]?.value)
        assertEquals(TrayMenu.PATH, (properties["Menu"]?.value as DBusPath).path)
        assertEquals("a(iiay)", properties["IconPixmap"]?.sig)
        assertEquals(2, (properties["IconPixmap"]?.value as List<*>).size)
    }

    @Test
    fun icons_travel_as_argb_in_network_byte_order() {
        val pixmap = IconPixmap.of(BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFF6750A4.toInt())
            setRGB(1, 0, 0x80FFFFFF.toInt())
        })

        assertEquals(2, pixmap.width)
        assertEquals(1, pixmap.height)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x67, 0x50, 0xA4.toByte(), 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), pixmap.argb)
    }

    @Test
    fun a_click_opens_the_window_and_the_menu_opens_or_quits() {
        val (connection, watcher) = panel()
        assertNotNull(start(open = "Ouvri Kadans", quit = "Kite"))
        val item = watcher.registered.next()

        connection.getRemoteObject(item, TrayItem.PATH, StatusNotifierItem::class.java).Activate(0, 0)
        assertEquals(TrayAction.Open, actions.next())

        val menu = menuOf(connection, item)
        assertEquals(listOf("Ouvri Kadans", "separator", "Kite"), labels(menu))
        assertEquals(3, menu.GetLayout(TrayMenu.ROOT, -1, emptyList()).second.children.size)
        menu.Event(TrayMenu.OPEN, "clicked", Variant(""), UInt32(0))
        assertEquals(TrayAction.Open, actions.next())
        menu.Event(TrayMenu.QUIT, "hovered", Variant(""), UInt32(0)) // only a click acts
        menu.EventGroup(listOf(MenuEvent(TrayMenu.QUIT, "clicked", Variant(""), UInt32(0))))
        assertEquals(TrayAction.Quit, actions.next())
        assertNull(actions.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun a_language_change_relabels_the_menu_and_tells_the_panel() {
        val (connection, watcher) = panel()
        val tray = assertNotNull(start())
        val menu = menuOf(connection, watcher.registered.next())
        val updates = LinkedBlockingQueue<DbusMenu.LayoutUpdated>()
        connection.addSigHandler(DbusMenu.LayoutUpdated::class.java) { updates.add(it) }
        val before = menu.GetLayout(TrayMenu.ROOT, 0, emptyList()).first

        tray.setLabels("Ouvrir Kadans", "Quitter")

        val update = updates.next()
        assertEquals(TrayMenu.ROOT, update.parent)
        assertEquals(before.toLong() + 1, update.revision.toLong())
        assertEquals(listOf("Ouvrir Kadans", "separator", "Quitter"), labels(menu))
    }

    @Test
    fun a_restarted_panel_finds_the_icon_again() {
        val (first, watcher) = panel()
        assertNotNull(start())
        val item = watcher.registered.next()

        first.releaseBusName(StatusNotifierTray.WATCHER)
        val (_, restarted) = panel()

        assertEquals(item, restarted.registered.next())
    }

    @Test
    fun without_a_panel_showing_items_there_is_no_tray() {
        assertNull(start(), "no watcher on the bus")

        panel(hostRegistered = false)
        assertNull(start(), "a watcher but no host draws items")

        assertNull(
            StatusNotifierTray.start("Kadans", emptyList(), "Open", "Quit", {}, "unix:path=${folder.resolve("nobody")}"),
            "no bus at all",
        )
    }

    @Test
    fun closing_takes_the_icon_away() {
        val (connection, watcher) = panel()
        val tray = assertNotNull(start())
        val item = watcher.registered.next()
        val bus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        assertEquals(true, bus.NameHasOwner(item))

        tray.close()

        assertEquals(false, bus.NameHasOwner(item))
    }
}
