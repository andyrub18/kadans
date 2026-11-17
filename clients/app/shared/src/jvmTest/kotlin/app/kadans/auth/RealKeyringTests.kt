package app.kadans.auth

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus

/**
 * The session's key kept by GNOME Keyring itself: a daemon of its own on a bus of its own, in a folder of its own, so
 * neither the keyring of whoever runs the tests nor their screen is touched. It says what a stand-in cannot: that
 * Kadans and a real keyring understand each other (the exchange that seals what travels, an item made, found, read).
 *
 * Skipped where `gnome-keyring-daemon` or `dbus-daemon` is not installed; CI installs them and sets
 * KADANS_REQUIRE_KEYRING, so that there a missing daemon fails instead.
 */
class RealKeyringTests {
    private var keyring: PrivateKeyring? = null

    /** A session bus and GNOME Keyring on it, its login keyring made and open, nothing shared with the real session. */
    private class PrivateKeyring(private val folder: Path, private val bus: Process, private val daemon: Process, val address: String) : AutoCloseable {
        fun lock() {
            DBusConnectionBuilder.forAddress(address).withShared(false).build().use { connection ->
                val service = connection.getRemoteObject(KeyringSealer.NAME, KeyringSealer.PATH, SecretServiceLocking::class.java)
                service.Lock(listOf(DBusPath(KeyringSealer.LOGIN_COLLECTION)))
            }
        }

        override fun close() {
            daemon.destroy()
            bus.destroy()
            if (!daemon.waitFor(5, TimeUnit.SECONDS)) daemon.destroyForcibly()
            if (!bus.waitFor(5, TimeUnit.SECONDS)) bus.destroyForcibly()
            folder.toFile().deleteRecursively()
        }

        companion object {
            fun start(): PrivateKeyring? {
                val tools = listOf("dbus-daemon", "gnome-keyring-daemon").map { name ->
                    System.getenv("PATH").orEmpty().split(':').map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }
                }
                if (tools.any { it == null }) {
                    if (System.getenv("KADANS_REQUIRE_KEYRING") != null) fail("gnome-keyring-daemon and dbus-daemon are needed here")
                    return null
                }
                // A short path: a Unix socket's holds about a hundred characters.
                val folder = Files.createTempDirectory("kadans-kr")
                val socket = folder.resolve("bus")
                // No service folders: the bus starts nothing by itself, so no password window can open anywhere.
                val config = folder.resolve("bus.conf")
                Files.writeString(
                    config,
                    """
                    <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                     "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                    <busconfig>
                      <type>session</type>
                      <listen>unix:path=$socket</listen>
                      <auth>EXTERNAL</auth>
                      <policy context="default">
                        <allow send_destination="*" eavesdrop="true"/>
                        <allow eavesdrop="true"/>
                        <allow own="*"/>
                      </policy>
                    </busconfig>
                    """.trimIndent(),
                )
                val address = "unix:path=$socket"
                fun process(vararg command: String) = ProcessBuilder(*command).apply {
                    environment().clear()
                    environment() += mapOf(
                        "PATH" to "/usr/bin:/bin",
                        "HOME" to folder.toString(),
                        "XDG_RUNTIME_DIR" to folder.toString(),
                        "XDG_DATA_HOME" to folder.resolve("data").toString(),
                        "DBUS_SESSION_BUS_ADDRESS" to address,
                    )
                    redirectErrorStream(true)
                    redirectOutput(folder.resolve("${Path.of(command[0]).fileName}.log").toFile())
                }.start()

                val bus = process(tools[0].toString(), "--config-file=$config", "--nofork")
                waitFor("the bus") { Files.exists(socket) }
                // --unlock: the password of the login keyring on its input, which makes that keyring and opens it.
                val daemon = process(tools[1].toString(), "--foreground", "--unlock", "--components=secrets")
                daemon.outputStream.use { it.write("a test's password".toByteArray()) }
                val keyring = PrivateKeyring(folder, bus, daemon, address)
                try {
                    DBusConnectionBuilder.forAddress(address).withShared(false).build().use { connection ->
                        val names = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                        waitFor("the keyring") { names.NameHasOwner(KeyringSealer.NAME) }
                    }
                } catch (e: Throwable) {
                    keyring.close()
                    throw e
                }
                return keyring
            }

            private fun waitFor(what: String, ready: () -> Boolean) {
                repeat(100) {
                    if (runCatching(ready).getOrDefault(false)) return
                    Thread.sleep(100)
                }
                fail("$what did not start")
            }
        }
    }

    private fun keyring(): PrivateKeyring? = PrivateKeyring.start().also { keyring = it }

    @AfterTest
    fun stop() {
        keyring?.close()
    }

    private val session = "access.jwt\nrefresh".toByteArray()

    @Test
    fun gnome_keyring_keeps_the_key_and_hands_it_back() = runBlocking {
        val keyring = keyring() ?: return@runBlocking

        val sealed = assertIs<Sealing.Done>(KeyringSealer(keyring.address).seal(session)).bytes

        // Another run reads the key back from the keyring, through an exchange of its own.
        assertContentEquals(session, assertIs<Opening.Done>(KeyringSealer(keyring.address).open(sealed)).bytes)
        // And so does one that sends secrets as they are: the key in the keyring is the same.
        val plain = KeyringSealer(keyring.address, algorithms = listOf(SecretSession.PLAIN))
        assertContentEquals(session, assertIs<Opening.Done>(plain.open(sealed)).bytes)
    }

    @Test
    fun gnome_keyring_speaks_the_sealed_exchange() = runBlocking {
        val keyring = keyring() ?: return@runBlocking

        // With no other way offered, a keyring that did not know the exchange would refuse.
        val sealer = KeyringSealer(keyring.address, algorithms = listOf(SecretSession.DH))
        val sealed = assertIs<Sealing.Done>(sealer.seal(session)).bytes

        val again = KeyringSealer(keyring.address, algorithms = listOf(SecretSession.DH))
        assertContentEquals(session, assertIs<Opening.Done>(again.open(sealed)).bytes)
    }

    @Test
    fun a_locked_gnome_keyring_nobody_opens_refuses() = runBlocking {
        val keyring = keyring() ?: return@runBlocking
        val sealed = assertIs<Sealing.Done>(KeyringSealer(keyring.address).seal(session)).bytes

        keyring.lock()
        // Nothing on this bus shows the keyring's question: it stays unanswered.
        val sealer = KeyringSealer(keyring.address, patience = 3.seconds)

        assertEquals(Opening.Refused, sealer.open(sealed))
        assertEquals(Sealing.Refused, sealer.seal(session))
    }
}
