package app.kadans.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual fun platformSealer(): Sealer = KeystoreSealer()

/**
 * The session sealed with a key that lives in Android's Keystore (docs/ARCHITECTURE.md → "The session, sealed on the
 * device"): AES-256-GCM, made on the phone, used inside the Keystore (the phone's secure hardware where it has one) and
 * never handed to the app, so it can be neither read nor copied. What it seals opens on this phone, in this
 * installation, and nowhere else.
 *
 * The key asks for no unlock of its own: the alarm that rings a reminder, the widget and a push read the session with
 * the screen off. The phone's storage is encrypted until its first unlock anyway, and Kadans does not run before it.
 *
 * - A key that stopped working (a system update can do that) is replaced when the next session is sealed.
 * - A phone whose Keystore makes no key at all keeps the session in the app's private settings, as before: out of
 *   reach of other apps and, now, of the phone's backup. An app that signs out at every start serves nobody.
 */
internal class KeystoreSealer(private val alias: String = ALIAS) : Sealer {
    private val lock = Mutex()

    override suspend fun seal(plain: ByteArray): Sealing = withContext(Dispatchers.IO) {
        lock.withLock {
            val sealed = runCatching { encrypt(plain) }.getOrNull()
                ?: runCatching {
                    drop()
                    encrypt(plain)
                }.onFailure { Log.w(TAG, "The Keystore sealed nothing: ${it.javaClass.simpleName}") }.getOrNull()
            if (sealed == null) Sealing.None else Sealing.Done(sealed)
        }
    }

    override suspend fun open(sealed: ByteArray): Opening = withContext(Dispatchers.IO) {
        lock.withLock {
            var answer = decrypt(sealed)
            if (answer == Opening.Refused) {
                // The Keystore is a service of the system's: just after a start it may not answer the first time.
                delay(RETRY_AFTER_MS)
                answer = decrypt(sealed)
            }
            answer
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: create())
        cipher.updateAAD(PURPOSE)
        val nonce = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(LAYOUT, nonce.size.toByte()) + nonce + body
    }

    private fun decrypt(sealed: ByteArray): Opening {
        if (sealed.size < 2 || sealed[0] != LAYOUT) return Opening.Lost
        val nonceSize = sealed[1].toInt()
        if (nonceSize !in NONCE_SIZES || sealed.size < 2 + nonceSize + TAG_BITS / 8) return Opening.Lost
        return try {
            val key = key() ?: return Opening.Lost
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 2, nonceSize))
            cipher.updateAAD(PURPOSE)
            Opening.Done(cipher.doFinal(sealed, 2 + nonceSize, sealed.size - 2 - nonceSize))
        } catch (_: AEADBadTagException) {
            Opening.Lost // sealed with another key, or changed
        } catch (_: KeyPermanentlyInvalidatedException) {
            Opening.Lost
        } catch (e: Exception) {
            Log.w(TAG, "The Keystore opened nothing: ${e.javaClass.simpleName}")
            Opening.Refused
        }
    }

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey? = store().getKey(alias, null) as? SecretKey

    private fun create(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build(),
            )
        }.generateKey()

    private fun drop() {
        runCatching { store().deleteEntry(alias) }
    }

    internal companion object {
        const val ALIAS = "kadans.session"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val LAYOUT: Byte = 1
        private val NONCE_SIZES = 12..16
        private const val RETRY_AFTER_MS = 300L
        private const val TAG = "Kadans"

        /** Bound to what is sealed: something sealed for another use never opens as a session. */
        private val PURPOSE = "app.kadans.session".toByteArray()
    }
}
