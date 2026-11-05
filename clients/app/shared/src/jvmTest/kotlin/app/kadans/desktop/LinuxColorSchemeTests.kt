@file:Suppress("FunctionName")

package app.kadans.desktop

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * The Linux desktop's dark mode, against a private bus (dbus-java's own daemon) and a stand-in for the settings portal:
 * no desktop needed, as on CI. Checked by hand against COSMIC's portal (color-scheme 1 when dark).
 */
class LinuxColorSchemeTests {
    private val folder = Files.createTempDirectory("kadans-portal")
    private val address = "unix:path=${folder.resolve("bus")}"
    private lateinit var daemon: EmbeddedDBusDaemon
    private val connections = mutableListOf<AutoCloseable>()

    /** The desktop's side: answers [scheme]; [version1] answers only the older Read, its value wrapped once more. */
    private class FakePortal(private val connection: DBusConnection, var scheme: Int, private val version1: Boolean = false) : PortalSettings {
        override fun getObjectPath() = LinuxColorScheme.PATH

        override fun ReadOne(namespace: String, key: String): Variant<*> {
            if (version1) throw DBusExecutionException("No such method 'ReadOne'")
            return value(namespace, key)
        }

        override fun Read(namespace: String, key: String): Variant<*> = Variant(value(namespace, key))

        private fun value(namespace: String, key: String): Variant<*> {
            if (namespace != LinuxColorScheme.NAMESPACE || key != LinuxColorScheme.KEY) throw DBusExecutionException("Requested setting not found")
            return Variant(UInt32(scheme.toLong()))
        }

        /** The person switches the desktop to [to]. */
        fun change(to: Int, namespace: String = LinuxColorScheme.NAMESPACE, key: String = LinuxColorScheme.KEY) {
            scheme = to
            connection.sendMessage(PortalSettings.SettingChanged(LinuxColorScheme.PATH, namespace, key, Variant(UInt32(to.toLong()))))
        }
    }

    @BeforeTest
    fun startBus() {
        daemon = EmbeddedDBusDaemon("$address,listen=true")
        daemon.startInBackgroundAndWait(10_000)
    }

    @AfterTest
    fun stopBus() {
        connections.forEach { runCatching { it.close() } }
        daemon.close()
        folder.toFile().deleteRecursively()
    }

    private fun portal(scheme: Int, version1: Boolean = false): FakePortal {
        val connection = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }
        val portal = FakePortal(connection, scheme, version1)
        connection.exportObject(LinuxColorScheme.PATH, portal)
        connection.requestBusName("org.freedesktop.portal.Desktop")
        return portal
    }

    private fun scheme(): LinuxColorScheme = assertNotNull(LinuxColorScheme.connect(address)).also { connections += it }

    @Test
    fun a_dark_desktop_reads_dark_and_its_switches_are_followed() = runBlocking {
        val portal = portal(scheme = 1)
        val scheme = scheme()
        assertTrue(scheme.dark.value)

        portal.change(to = 2)
        withTimeout(5.seconds) { scheme.dark.first { !it } }
        portal.change(to = 1)
        withTimeout(5.seconds) { scheme.dark.first { it } }

        // Another setting changing is not the colour scheme.
        portal.change(to = 0, key = "accent-color")
        Thread.sleep(300)
        assertTrue(scheme.dark.value)
    }

    @Test
    fun no_preference_and_prefer_light_are_light() {
        portal(scheme = 0)
        assertFalse(scheme().dark.value)
    }

    @Test
    fun an_older_portal_answers_through_read() {
        portal(scheme = 1, version1 = true)
        assertTrue(scheme().dark.value)
    }

    @Test
    fun no_portal_is_no_answer() {
        assertNull(LinuxColorScheme.connect(address))
    }

    @Test
    fun the_value_is_a_number_maybe_wrapped() {
        assertEquals(true, LinuxColorScheme.prefersDark(Variant(UInt32(1))))
        assertEquals(true, LinuxColorScheme.prefersDark(Variant(Variant(UInt32(1)))))
        assertEquals(false, LinuxColorScheme.prefersDark(UInt32(2)))
        assertNull(LinuxColorScheme.prefersDark("dark"))
    }
}
