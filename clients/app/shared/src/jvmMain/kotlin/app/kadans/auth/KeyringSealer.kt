package app.kadans.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.types.Variant

/**
 * The session sealed on a Linux desktop (docs/ARCHITECTURE.md → "The session, sealed on the device"). The keyring
 * (GNOME Keyring, KWallet, KeePassXC: the freedesktop Secret Service) keeps one random key for Kadans, encrypted with
 * the person's login password like everything it holds; Kadans seals the session with that key (AES-256-GCM) and
 * keeps the result in its own settings. The key is read once a run: a new pair of tokens, every few minutes, asks the
 * keyring nothing. It is the way browsers keep their own secrets on Linux.
 *
 * - No keyring on this desktop (no session bus, nobody behind `org.freedesktop.secrets`): [Sealing.None], the session
 *   stays in the settings file, which only the person's account reads, as it did.
 * - A keyring that is locked asks the person for its password itself (the desktop's own window; Kadans never sees
 *   what is typed). Closed without an answer, or no answer in [patience], or a keyring that fails: [Sealing.Refused]
 *   for this run. Kadans does not ask twice in a run.
 *
 * The key stays in the keyring when the person signs out, and when Kadans is removed ("Kadans" under Passwords and
 * Keys): it opens nothing by then, the sealed session is gone with the sign-out.
 */
internal class KeyringSealer(
    private val busAddress: String? = null,
    private val patience: Duration = 2.minutes,
    private val algorithms: List<String> = listOf(SecretSession.DH, SecretSession.PLAIN),
) : Sealer {
    private sealed interface Key {
        class Ready(val bytes: ByteArray) : Key

        data object None : Key

        data object Refused : Key
    }

    private val lock = Mutex()
    private var key: Key? = null

    override suspend fun seal(plain: ByteArray): Sealing = withContext(Dispatchers.IO) {
        when (val key = key()) {
            is Key.Ready -> Sealing.Done(SessionCipher.seal(key.bytes, plain))
            Key.None -> Sealing.None
            Key.Refused -> Sealing.Refused
        }
    }

    override suspend fun open(sealed: ByteArray): Opening = withContext(Dispatchers.IO) {
        when (val key = key()) {
            is Key.Ready -> SessionCipher.open(key.bytes, sealed)?.let { Opening.Done(it) } ?: Opening.Lost
            Key.None -> Opening.Lost // sealed while there was a keyring; there is none any more
            Key.Refused -> Opening.Refused
        }
    }

    override suspend fun again() = lock.withLock {
        if (key == Key.Refused) key = null
    }

    private suspend fun key(): Key = lock.withLock {
        key ?: ask().also { key = it }
    }

    private fun ask(): Key {
        val connection = runCatching {
            val builder = if (busAddress == null) DBusConnectionBuilder.forSessionBus() else DBusConnectionBuilder.forAddress(busAddress)
            // A bus that is named and not there (a session half started) is given up on soon: the app waits on this.
            builder.transportConfig().withTimeout(CONNECT_WITHIN_MS).back().withShared(false).build()
        }.getOrNull() ?: return Key.None
        return connection.use {
            val there = runCatching {
                val bus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                bus.NameHasOwner(NAME) || NAME in bus.ListActivatableNames()
            }.getOrDefault(false)
            if (!there) return@use Key.None
            // A keyring starting with the session may not answer the first call.
            runCatching { fetch(connection) }
                .recoverCatching {
                    Thread.sleep(RETRY_AFTER_MS)
                    fetch(connection)
                }
                .getOrDefault(Key.Refused)
        }
    }

    /** Kadans' key from the keyring, made and put there the first time. */
    private fun fetch(connection: DBusConnection): Key {
        val service = connection.getRemoteObject(NAME, PATH, SecretService::class.java)
        val session = SecretSession.open(service, algorithms)
        try {
            val found = service.SearchItems(ATTRIBUTES)
            var items = found.first
            if (items.isEmpty() && found.second.isNotEmpty()) {
                items = unlock(connection, service, found.second) ?: return Key.Refused
            }
            for (item in items) {
                val secret = connection.getRemoteObject(NAME, item.path, SecretItem::class.java).GetSecret(session.path)
                decode(session.unwrap(secret))?.let { return Key.Ready(it) }
            }
            return create(connection, service, session)
        } finally {
            runCatching { connection.getRemoteObject(NAME, session.path.path, SecretSessionObject::class.java).Close() }
        }
    }

    private fun create(connection: DBusConnection, service: SecretService, session: SecretSession): Key {
        var collection = service.ReadAlias(DEFAULT_ALIAS)
        // A desktop whose keyring was never given a default: the login keyring, where there is one.
        if (collection.path == NOTHING) collection = DBusPath(LOGIN_COLLECTION)
        val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val properties = mapOf(
            "org.freedesktop.Secret.Item.Label" to Variant(LABEL),
            "org.freedesktop.Secret.Item.Attributes" to Variant(ATTRIBUTES, "a{ss}"),
        )
        val made = connection.getRemoteObject(NAME, collection.path, SecretCollection::class.java)
            .CreateItem(properties, session.wrap(encode(bytes)), true)
        if (made.second.path != NOTHING) {
            // The keyring is locked: it asks, and stores the item once it is open.
            answered(connection, made.second) ?: return Key.Refused
        }
        return Key.Ready(bytes)
    }

    /** The items once their keyring is open, or null when it stayed locked. */
    private fun unlock(connection: DBusConnection, service: SecretService, locked: List<DBusPath>): List<DBusPath>? {
        val reply = service.Unlock(locked)
        if (reply.second.path == NOTHING) return reply.first.takeIf { it.isNotEmpty() }
        answered(connection, reply.second) ?: return null
        // What the question's answer carries differs from one keyring to the next: ask again what can be read now.
        return service.SearchItems(ATTRIBUTES).first.takeIf { it.isNotEmpty() }
    }

    /** Puts the keyring's question to the person and waits. Null: closed without an answer, or none in [patience]. */
    private fun answered(connection: DBusConnection, prompt: DBusPath): Variant<*>? {
        val done = CompletableFuture<SecretPrompt.Completed>()
        val handler = DBusSigHandler<SecretPrompt.Completed> { if (it.path == prompt.path) done.complete(it) }
        connection.addSigHandler(SecretPrompt.Completed::class.java, handler)
        val question = connection.getRemoteObject(NAME, prompt.path, SecretPrompt::class.java)
        try {
            question.Prompt("")
            val completed = done.get(patience.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            return if (completed.dismissed) null else completed.result
        } catch (_: TimeoutException) {
            runCatching { question.Dismiss() }
            return null
        } finally {
            runCatching { connection.removeSigHandler(SecretPrompt.Completed::class.java, handler) }
        }
    }

    internal companion object {
        const val NAME = "org.freedesktop.secrets"
        const val PATH = "/org/freedesktop/secrets"
        const val DEFAULT_ALIAS = "default"
        const val LOGIN_COLLECTION = "/org/freedesktop/secrets/collection/login"

        /** D-Bus's "no object". */
        const val NOTHING = "/"

        /** What finds Kadans' key again: one key for the person's account on this computer. */
        val ATTRIBUTES = mapOf("application" to "app.kadans", "xdg:schema" to "app.kadans.SessionKey")

        /** The item's name where the person looks at their keyring (Passwords and Keys, KWalletManager). */
        const val LABEL = "Kadans"
        private const val KEY_BYTES = 32
        private const val RETRY_AFTER_MS = 1_000L
        private const val CONNECT_WITHIN_MS = 3_000

        /** The key as text: keyrings show a secret as a password. */
        fun encode(key: ByteArray): ByteArray = Base64.getEncoder().encode(key)

        fun decode(secret: ByteArray): ByteArray? =
            runCatching { Base64.getDecoder().decode(secret) }.getOrNull()?.takeIf { it.size == KEY_BYTES }
    }
}

/** AES-256-GCM with a key the caller holds: a fresh nonce each time, then the sealed bytes and their tag. */
internal object SessionCipher {
    private const val LAYOUT: Byte = 1
    private const val NONCE = 12
    private const val TAG_BITS = 128

    /** Bound to what is sealed: something sealed for another use never opens as a session. */
    private val PURPOSE = "app.kadans.session".toByteArray()

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce)
        return byteArrayOf(LAYOUT) + nonce + cipher.doFinal(plain)
    }

    /** Null: sealed with another key, changed since, or not sealed here at all. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < 1 + NONCE + TAG_BITS / 8 || sealed[0] != LAYOUT) return null
        return runCatching {
            cipher(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(1, 1 + NONCE)).doFinal(sealed, 1 + NONCE, sealed.size - 1 - NONCE)
        }.getOrNull()
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(PURPOSE)
        }
}
