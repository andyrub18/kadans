@file:Suppress("FunctionName") // D-Bus member names, as the protocol spells them

package app.kadans.auth

import app.kadans.tray.Reply2
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/*
 * The Linux desktop's keyring as every desktop offers it: the freedesktop Secret Service
 * (https://specifications.freedesktop.org/secret-service-spec/latest/), which GNOME Keyring, KWallet and KeePassXC
 * implement. Only what Kadans calls is declared here.
 */

@DBusInterfaceName("org.freedesktop.Secret.Service")
internal interface SecretService : DBusInterface {
    /** A session's secrets travel sealed for it ([SecretSession]). Answers the service's half of the exchange and the session. */
    fun OpenSession(algorithm: String, input: Variant<*>): Reply2<Variant<*>, DBusPath>

    /** The items with these attributes: those that can be read now, and those in a locked keyring. */
    fun SearchItems(attributes: Map<String, String>): Reply2<List<DBusPath>, List<DBusPath>>

    /** What unlocked without a question, and the question to put to the person ("/" when there is none). */
    fun Unlock(objects: List<@JvmSuppressWildcards DBusPath>): Reply2<List<DBusPath>, DBusPath>

    /** The keyring an alias names ("default": where a new item goes), "/" when there is none. */
    fun ReadAlias(name: String): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
internal interface SecretCollection : DBusInterface {
    /** The new item, and the question to put to the person first ("/" when there is none). */
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): Reply2<DBusPath, DBusPath>
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
internal interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
}

@DBusInterfaceName("org.freedesktop.Secret.Session")
internal interface SecretSessionObject : DBusInterface {
    fun Close()
}

/** A question the keyring puts to the person itself (its password, usually): Kadans never sees the answer. */
@DBusInterfaceName("org.freedesktop.Secret.Prompt")
internal interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)

    fun Dismiss()

    /** Answered, or closed without an answer ([dismissed]). */
    class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) : DBusSignal(path, dismissed, result)
}

/** `(oayays)`: a secret as it travels: the session it is sealed for, that sealing's parameters, the value, its type. */
internal class Secret(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

/**
 * One conversation with the keyring. Secrets cross the session bus sealed with a key the two ends agree on without
 * sending it (Diffie-Hellman, then AES-128-CBC: `dh-ietf1024-sha256-aes128-cbc-pkcs7`, what libsecret uses), so that
 * nothing listening on the bus reads them. A keyring that does not know that exchange gets them as they are
 * ([PLAIN]): the bus is the person's own, and every desktop's own tools do the same.
 */
internal class SecretSession private constructor(val path: DBusPath, private val key: ByteArray?) {
    val sealed get() = key != null

    fun wrap(value: ByteArray): Secret {
        if (key == null) return Secret(path, ByteArray(0), value, CONTENT_TYPE)
        val iv = ByteArray(BLOCK).also { SecureRandom().nextBytes(it) }
        return Secret(path, iv, cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(value), CONTENT_TYPE)
    }

    fun unwrap(secret: Secret): ByteArray {
        if (key == null) return secret.value
        return cipher(Cipher.DECRYPT_MODE, key, secret.parameters).doFinal(secret.value)
    }

    companion object {
        const val DH = "dh-ietf1024-sha256-aes128-cbc-pkcs7"
        const val PLAIN = "plain"
        private const val CONTENT_TYPE = "text/plain"
        private const val BLOCK = 16

        /** The 1024-bit group of RFC 2409 (section 6.2), generator 2: the one the algorithm's name fixes. */
        internal val PRIME = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381FFFFFFFFFFFFFFFF",
            16,
        )
        internal val GENERATOR: BigInteger = BigInteger.TWO
        private const val PRIME_BYTES = 128

        fun open(service: SecretService, algorithms: List<String> = listOf(DH, PLAIN)): SecretSession {
            if (DH in algorithms) {
                try {
                    val exchange = Exchange()
                    val reply = service.OpenSession(DH, Variant(exchange.public, "ay"))
                    return SecretSession(reply.second, exchange.key(bytes(reply.first.value)))
                } catch (e: DBusExecutionException) {
                    if (PLAIN !in algorithms) throw e
                }
            }
            val reply = service.OpenSession(PLAIN, Variant(""))
            return SecretSession(reply.second, null)
        }

        /** A D-Bus array of bytes, whichever way the library hands it over. */
        internal fun bytes(value: Any?): ByteArray = when (value) {
            is ByteArray -> value
            is List<*> -> ByteArray(value.size) { (value[it] as Number).toByte() }
            else -> throw IllegalArgumentException("not bytes")
        }

        private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    }

    /** One end of the exchange: a number of its own, never sent, and what it shows of it. */
    internal class Exchange(random: SecureRandom = SecureRandom()) {
        private val secret: BigInteger = generateSequence { BigInteger(PRIME.bitLength(), random) }
            .first { it > BigInteger.ONE && it < PRIME - BigInteger.ONE }

        val public: ByteArray = unsigned(GENERATOR.modPow(secret, PRIME))

        /** The key both ends reach, from what the other end showed of its number. */
        fun key(theirPublic: ByteArray): ByteArray {
            val theirs = BigInteger(1, theirPublic)
            // 0, 1 and p-1 would make a key anyone can work out: no honest end shows them.
            require(theirs > BigInteger.ONE && theirs < PRIME - BigInteger.ONE) { "not a public value" }
            val shared = unsigned(theirs.modPow(secret, PRIME))
            // As long as the prime, zeroes in front: both ends must hash the same bytes.
            val padded = ByteArray(PRIME_BYTES - shared.size) + shared
            return hkdf(padded)
        }

        private companion object {
            fun unsigned(number: BigInteger): ByteArray = number.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

            /** HKDF-SHA256 (RFC 5869) with no salt and no info, 16 bytes out: one block of it. */
            fun hkdf(input: ByteArray): ByteArray {
                val extracted = hmac(ByteArray(32), input)
                return hmac(extracted, byteArrayOf(1)).copyOf(BLOCK)
            }

            fun hmac(key: ByteArray, data: ByteArray): ByteArray =
                Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        }
    }
}
