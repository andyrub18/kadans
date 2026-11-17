@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.auth

import app.kadans.startPrivateBus
import app.kadans.tray.Reply2
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * The Linux keyring's part in sealing the session, against a private bus (dbus-java's own daemon) and a stand-in for
 * the keyring that speaks the Secret Service as its specification writes it: the key made once and found again, both
 * ways a secret may travel, a locked keyring opened, left locked, never answered, and a desktop without one.
 * [RealKeyringTests] says the same to GNOME Keyring itself.
 */
class KeyringSealerTests {
    private val folder = Files.createTempDirectory("kadans-bus")
    private val address = "unix:path=${folder.resolve("bus")}"
    private lateinit var daemon: EmbeddedDBusDaemon
    private val connections = mutableListOf<AutoCloseable>()

    /** What the person does with the keyring's question. */
    private enum class Person { Opens, Closes, LeavesIt }

    /** The keyring's side: one keyring, the default one, with what Kadans calls of it. */
    private class FakeKeyring(private val connection: DBusConnection, private val knowsExchange: Boolean = true) {
        var locked = false
        var person = Person.Opens
        val questions = AtomicInteger()
        val dismissed = AtomicInteger()

        /** Path → attributes and the secret as the keyring holds it. */
        val items = LinkedHashMap<String, Pair<Map<String, String>, ByteArray>>()
        val labels = mutableListOf<String>()

        /** Session → its key (null: secrets travel as they are). */
        private val sessions = HashMap<String, ByteArray?>()
        private var next = 0

        /** Whether any secret crossed the bus as it is. */
        var sawPlain = false

        fun export() {
            connection.requestBusName(KeyringSealer.NAME)
            connection.exportObject(KeyringSealer.PATH, Service())
            connection.exportObject(COLLECTION, Collection())
        }

        private fun ask(then: () -> Variant<*>): DBusPath {
            val path = "/org/freedesktop/secrets/prompt/p${++next}"
            connection.exportObject(path, object : SecretPrompt {
                override fun getObjectPath() = path

                override fun Prompt(windowId: String) {
                    questions.incrementAndGet()
                    // The answer comes after the call returned, as a person's does.
                    thread(isDaemon = true) {
                        Thread.sleep(50)
                        when (person) {
                            Person.Opens -> {
                                locked = false
                                connection.sendMessage(SecretPrompt.Completed(path, false, then()))
                            }
                            Person.Closes -> connection.sendMessage(SecretPrompt.Completed(path, true, Variant("")))
                            Person.LeavesIt -> Unit
                        }
                    }
                }

                override fun Dismiss() {
                    dismissed.incrementAndGet()
                }
            })
            return DBusPath(path)
        }

        private fun store(attributes: Map<String, String>, value: ByteArray): String {
            val path = "$COLLECTION/${++next}"
            items.entries.removeIf { it.value.first == attributes } // replace
            items[path] = attributes to value
            connection.exportObject(path, Item(path))
            return path
        }

        inner class Service : SecretService {
            override fun getObjectPath() = KeyringSealer.PATH

            override fun OpenSession(algorithm: String, input: Variant<*>): Reply2<Variant<*>, DBusPath> {
                val path = "/org/freedesktop/secrets/session/s${++next}"
                connection.exportObject(path, object : SecretSessionObject {
                    override fun getObjectPath() = path

                    override fun Close() {
                        sessions.remove(path)
                    }
                })
                return when {
                    algorithm == SecretSession.DH && knowsExchange -> {
                        val exchange = SecretSession.Exchange()
                        sessions[path] = exchange.key(SecretSession.bytes(input.value))
                        Reply2(Variant(exchange.public, "ay"), DBusPath(path))
                    }
                    algorithm == SecretSession.PLAIN -> {
                        sessions[path] = null
                        Reply2(Variant(""), DBusPath(path))
                    }
                    else -> throw DBusExecutionException("org.freedesktop.DBus.Error.NotSupported")
                }
            }

            override fun SearchItems(attributes: Map<String, String>): Reply2<List<DBusPath>, List<DBusPath>> {
                val found = items.filter { (_, item) -> attributes.all { item.first[it.key] == it.value } }.keys.map { DBusPath(it) }
                return if (locked) Reply2(emptyList(), found) else Reply2(found, emptyList())
            }

            override fun Unlock(objects: List<DBusPath>): Reply2<List<DBusPath>, DBusPath> {
                if (!locked) return Reply2(objects, DBusPath("/"))
                return Reply2(emptyList(), ask { Variant(objects, "ao") })
            }

            override fun ReadAlias(name: String) = DBusPath(if (name == "default") COLLECTION else "/")
        }

        inner class Collection : SecretCollection {
            override fun getObjectPath() = COLLECTION

            override fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): Reply2<DBusPath, DBusPath> {
                @Suppress("UNCHECKED_CAST")
                val attributes = properties["org.freedesktop.Secret.Item.Attributes"]!!.value as Map<String, String>
                labels += properties["org.freedesktop.Secret.Item.Label"]!!.value as String
                val value = unwrap(secret)
                if (!locked) return Reply2(DBusPath(store(attributes, value)), DBusPath("/"))
                return Reply2(DBusPath("/"), ask { Variant(DBusPath(store(attributes, value)), "o") })
            }
        }

        inner class Item(private val path: String) : SecretItem {
            override fun getObjectPath() = path

            override fun GetSecret(session: DBusPath): Secret {
                if (locked) throw DBusExecutionException("org.freedesktop.Secret.Error.IsLocked")
                val value = items.getValue(path).second
                val key = sessions.getValue(session.path)
                if (key == null) {
                    sawPlain = true
                    return Secret(session, ByteArray(0), value, "text/plain")
                }
                val iv = ByteArray(16) { it.toByte() }
                return Secret(session, iv, aes(Cipher.ENCRYPT_MODE, key, iv).doFinal(value), "text/plain")
            }
        }

        private fun unwrap(secret: Secret): ByteArray {
            val key = sessions.getValue(secret.session.path)
            if (key == null) {
                sawPlain = true
                return secret.value
            }
            return aes(Cipher.DECRYPT_MODE, key, secret.parameters).doFinal(secret.value)
        }

        private fun aes(mode: Int, key: ByteArray, iv: ByteArray) =
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }

        companion object {
            const val COLLECTION = "/org/freedesktop/secrets/collection/login"
        }
    }

    @BeforeTest
    fun startBus() {
        daemon = startPrivateBus(address)
    }

    @AfterTest
    fun stopBus() {
        connections.forEach { runCatching { it.close() } }
        runCatching { daemon.close() }
        folder.toFile().deleteRecursively()
    }

    private fun keyring(knowsExchange: Boolean = true): FakeKeyring {
        val connection = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }
        return FakeKeyring(connection, knowsExchange).also { it.export() }
    }

    private fun sealer(patience: kotlin.time.Duration = 5.seconds) = KeyringSealer(address, patience)

    private val session = "access.jwt\nrefresh".toByteArray()

    private fun sealed(sealer: Sealer): ByteArray = runBlocking { assertIs<Sealing.Done>(sealer.seal(session)).bytes }

    private fun opened(sealer: Sealer, sealed: ByteArray): Opening = runBlocking { sealer.open(sealed) }

    @Test
    fun the_key_is_made_once_and_found_again_at_the_next_start() {
        val keyring = keyring()

        val sealed = sealed(sealer())

        // One item, named for the person who looks at their keyring, found again by what Kadans is.
        assertEquals(listOf("Kadans"), keyring.labels)
        assertEquals(KeyringSealer.ATTRIBUTES, keyring.items.values.single().first)
        assertNotNull(KeyringSealer.decode(keyring.items.values.single().second))
        // Another run: the same key from the keyring opens it, and no second key is made.
        assertContentEquals(session, assertIs<Opening.Done>(opened(sealer(), sealed)).bytes)
        assertEquals(1, keyring.items.size)
        assertEquals(0, keyring.questions.get())
    }

    @Test
    fun the_key_crosses_the_bus_sealed() {
        val keyring = keyring()

        val sealed = sealed(sealer())
        assertIs<Opening.Done>(opened(sealer(), sealed))

        assertFalse(keyring.sawPlain)
    }

    @Test
    fun a_keyring_that_knows_no_exchange_gets_the_key_as_it_is() {
        val keyring = keyring(knowsExchange = false)

        val sealed = sealed(sealer())

        assertTrue(keyring.sawPlain)
        assertContentEquals(session, assertIs<Opening.Done>(opened(sealer(), sealed)).bytes)
    }

    @Test
    fun the_keyring_is_asked_once_a_run() {
        val keyring = keyring()
        val sealer = sealer()

        val first = sealed(sealer)
        connections.forEach { it.close() } // the keyring gone meanwhile: the key is in hand
        val second = sealed(sealer)

        assertIs<Opening.Done>(opened(sealer, first))
        assertIs<Opening.Done>(opened(sealer, second))
        assertEquals(1, keyring.items.size)
    }

    @Test
    fun what_is_sealed_never_reads_as_the_session_and_differs_each_time() {
        keyring()
        val sealer = sealer()

        val one = sealed(sealer)
        val two = sealed(sealer)

        assertFalse(one.contentEquals(two))
        assertFalse(String(one, Charsets.ISO_8859_1).contains("refresh"))
    }

    @Test
    fun what_changed_since_it_was_sealed_does_not_open() {
        keyring()
        val sealer = sealer()
        val sealed = sealed(sealer)

        for (at in listOf(0, 1, 13, sealed.lastIndex)) {
            val changed = sealed.copyOf().also { it[at] = (it[at] + 1).toByte() }
            assertEquals(Opening.Lost, opened(sealer, changed), "byte $at")
        }
        assertEquals(Opening.Lost, opened(sealer, sealed.copyOf(sealed.size - 1)))
        assertEquals(Opening.Lost, opened(sealer, ByteArray(0)))
    }

    @Test
    fun a_key_replaced_in_the_keyring_ends_the_session_sealed_with_the_old_one() {
        val keyring = keyring()
        val sealed = sealed(sealer())

        keyring.items.clear() // the person deleted "Kadans" from their keyring

        assertEquals(Opening.Lost, opened(sealer(), sealed))
        assertEquals(1, keyring.items.size) // a new key, for the next session
    }

    @Test
    fun a_locked_keyring_asks_the_person_and_then_hands_the_key() {
        val keyring = keyring()
        val sealed = sealed(sealer())
        keyring.locked = true

        val opened = opened(sealer(), sealed)

        assertContentEquals(session, assertIs<Opening.Done>(opened).bytes)
        assertEquals(1, keyring.questions.get())
    }

    @Test
    fun a_locked_keyring_takes_a_first_key_once_the_person_opened_it() {
        val keyring = keyring()
        keyring.locked = true

        val sealed = sealed(sealer())

        assertEquals(1, keyring.questions.get())
        assertEquals(1, keyring.items.size)
        assertIs<Opening.Done>(opened(sealer(), sealed))
    }

    @Test
    fun a_keyring_left_locked_refuses_and_is_not_asked_again() {
        val keyring = keyring()
        val sealed = sealed(sealer())
        keyring.locked = true
        keyring.person = Person.Closes
        val sealer = sealer()

        assertEquals(Opening.Refused, opened(sealer, sealed))
        assertEquals(Sealing.Refused, runBlocking { sealer.seal(session) })
        assertEquals(Opening.Refused, opened(sealer, sealed))

        assertEquals(1, keyring.questions.get())
    }

    @Test
    fun asked_again_a_keyring_the_person_now_opens_hands_the_key() {
        val keyring = keyring()
        val sealed = sealed(sealer())
        keyring.locked = true
        keyring.person = Person.Closes
        val sealer = sealer()
        assertEquals(Opening.Refused, opened(sealer, sealed))

        keyring.person = Person.Opens
        runBlocking { sealer.again() }

        assertContentEquals(session, assertIs<Opening.Done>(opened(sealer, sealed)).bytes)
        assertEquals(2, keyring.questions.get())
    }

    @Test
    fun a_first_key_refused_by_a_keyring_left_locked_is_not_used() {
        val keyring = keyring()
        keyring.locked = true
        keyring.person = Person.Closes

        assertEquals(Sealing.Refused, runBlocking { sealer().seal(session) })

        assertTrue(keyring.items.isEmpty())
    }

    @Test
    fun a_question_nobody_answers_is_taken_back() {
        val keyring = keyring()
        val sealed = sealed(sealer())
        keyring.locked = true
        keyring.person = Person.LeavesIt

        assertEquals(Opening.Refused, opened(sealer(patience = 300.milliseconds), sealed))

        assertEquals(1, keyring.dismissed.get())
    }

    @Test
    fun a_desktop_without_a_keyring_seals_nothing() {
        // The bus is there, nobody answers to the keyring's name.
        val sealer = sealer()

        assertEquals(Sealing.None, runBlocking { sealer.seal(session) })
        assertEquals(Opening.Lost, opened(sealer, ByteArray(40)))
    }

    @Test
    fun no_session_bus_seals_nothing() {
        val sealer = KeyringSealer("unix:path=${folder.resolve("nothing-here")}")

        assertEquals(Sealing.None, runBlocking { sealer.seal(session) })
    }

    @Test
    fun the_two_ends_of_an_exchange_reach_the_same_key_without_sending_it() {
        val here = SecretSession.Exchange()
        val there = SecretSession.Exchange()

        val key = here.key(there.public)

        assertContentEquals(key, there.key(here.public))
        assertEquals(16, key.size)
        assertFalse(here.public.contentEquals(there.public))
        // What makes a key anyone can work out is refused.
        for (weak in listOf(byteArrayOf(0), byteArrayOf(1), (SecretSession.PRIME - java.math.BigInteger.ONE).toByteArray())) {
            assertNull(runCatching { here.key(weak) }.getOrNull())
        }
    }

    @Test
    fun the_exchange_is_the_one_every_keyring_speaks() {
        // RFC 2409's second group: 1024 bits, ending and starting in 64 one-bits, generator 2.
        assertEquals(1024, SecretSession.PRIME.bitLength())
        assertTrue(SecretSession.PRIME.isProbablePrime(64))
        assertTrue(SecretSession.PRIME.toString(16).uppercase().let { it.startsWith("FFFFFFFFFFFFFFFFC90FDAA2") && it.endsWith("ECE65381FFFFFFFFFFFFFFFF") })
        assertEquals(java.math.BigInteger.TWO, SecretSession.GENERATOR)
    }
}

/** What only a test asks of the keyring: to lock itself. */
@org.freedesktop.dbus.annotations.DBusInterfaceName("org.freedesktop.Secret.Service")
internal interface SecretServiceLocking : DBusInterface {
    fun Lock(objects: List<@JvmSuppressWildcards DBusPath>): Reply2<List<DBusPath>, DBusPath>
}
