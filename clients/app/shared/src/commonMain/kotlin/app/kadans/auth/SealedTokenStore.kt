package app.kadans.auth

import app.kadans.api.AuthTokens
import app.kadans.api.TokenStore
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session across launches, sealed by the device (docs/ARCHITECTURE.md → "The session, sealed on the device"): the
 * app's settings hold what [sealer] made of the two tokens, never the tokens. Read once a run, then kept in memory:
 * the device's protection is asked at the start and at each new pair, not at every look.
 *
 * - A session an earlier version left in the clear is sealed the first time it is read, and the clear copy removed:
 *   an update signs nobody out.
 * - What no longer opens (the device's key gone, the settings copied from another device) is removed and the app is
 *   signed out: [onLost] then forgets what the device kept for that account, as a sign-out does.
 * - Where the device has nothing to seal with ([Sealing.None]) the tokens stay in the settings as they always were.
 * - Where it has and does not answer ([Sealing.Refused], [Opening.Refused]: a keyring left locked) nothing new is
 *   written in the clear: the session lasts the run, and a sealed one waits for the next start.
 */
class SealedTokenStore(
    private val settings: Settings,
    private val sealer: Sealer,
    private val onLost: suspend () -> Unit = {},
) : TokenStore {
    private val lock = Mutex()
    private var read = false
    private var current: AuthTokens? = null

    /** A sealed session is there and the device's protection did not answer: whose it is, nobody can tell. */
    private var unopened = false
        set(value) {
            field = value
            _waiting.value = value
        }

    private val _waiting = MutableStateFlow(false)

    /**
     * A session is kept here and did not open this time (a keyring left locked): the sign-in screen says so and offers
     * to ask again ([retry]), rather than leave the person wondering why they are signed out.
     */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    /** Asks the device's protection again for the session that waits; the session when it opened. */
    suspend fun retry(): AuthTokens? {
        lock.withLock {
            if (!unopened) return current
            sealer.again()
            unopened = false
            read = false
        }
        return load()
    }

    override suspend fun load(): AuthTokens? {
        var lost = false
        val tokens = lock.withLock {
            if (!read) {
                current = stored { lost = true }
                read = true
            }
            current
        }
        // Outside the lock: what forgets an account may look at the session itself.
        if (lost) onLost()
        return tokens
    }

    override suspend fun save(tokens: AuthTokens?) {
        val replacesUnknown = lock.withLock {
            val unknown = unopened && tokens != null
            unopened = false
            current = tokens
            read = true
            if (tokens == null) {
                remove(SEALED)
                removeClear()
            } else {
                write(tokens)
            }
            unknown
        }
        // Signing in over a session that never opened: it may have been someone else's.
        if (replacesUnknown) onLost()
    }

    private suspend fun stored(lost: () -> Unit): AuthTokens? {
        var gone = false
        val kept = settings.getStringOrNull(SEALED)
        if (kept != null) {
            when (val opened = decode(kept)?.let { sealer.open(it) } ?: Opening.Lost) {
                is Opening.Done -> parse(opened.bytes)?.let {
                    removeClear()
                    return it
                }
                Opening.Lost -> Unit
                Opening.Refused -> {
                    unopened = true
                    return null
                }
            }
            remove(SEALED)
            gone = true
        }
        // What an earlier version kept in the clear (and what a device with nothing to seal with still does).
        val clear = clear()
        if (clear == null) {
            if (gone) lost()
            return null
        }
        write(clear)
        return clear
    }

    private suspend fun write(tokens: AuthTokens) {
        when (val sealed = sealer.seal(format(tokens))) {
            is Sealing.Done ->
                if (put(SEALED, PREFIX + Base64.encode(sealed.bytes))) {
                    removeClear()
                } else {
                    // The settings refused it (their own limit on a value's length): this run only.
                    remove(SEALED)
                    removeClear()
                }
            Sealing.None -> {
                put(ACCESS_KEY, tokens.accessToken)
                put(REFRESH_KEY, tokens.refreshToken)
                remove(SEALED)
            }
            Sealing.Refused -> {
                remove(SEALED)
                // A clear copy from an earlier version follows the session until the device seals it, so that the
                // person is not signed out at the next start; none is ever made here.
                if (clear() != null) {
                    put(ACCESS_KEY, tokens.accessToken)
                    put(REFRESH_KEY, tokens.refreshToken)
                }
            }
        }
    }

    private fun clear(): AuthTokens? {
        val access = settings.getStringOrNull(ACCESS_KEY) ?: return null
        val refresh = settings.getStringOrNull(REFRESH_KEY) ?: return null
        return AuthTokens(access, refresh)
    }

    private fun removeClear() {
        remove(ACCESS_KEY)
        remove(REFRESH_KEY)
    }

    private fun put(key: String, value: String): Boolean = runCatching { settings.putString(key, value) }.isSuccess

    private fun remove(key: String) {
        runCatching { settings.remove(key) }
    }

    internal companion object {
        /** What the device made of the session: [PREFIX], then Base64. */
        const val SEALED = "kadans.session.sealed"

        /** Where versions before 1.2 kept the two tokens, in the clear. */
        const val ACCESS_KEY = "kadans.session.access"
        const val REFRESH_KEY = "kadans.session.refresh"

        /** The layout of what is sealed, should it ever change. */
        const val PREFIX = "1:"

        /** A token is one line of letters, digits and a few signs: a line break never belongs to one. */
        private const val BETWEEN = '\n'

        fun format(tokens: AuthTokens): ByteArray = (tokens.accessToken + BETWEEN + tokens.refreshToken).encodeToByteArray()

        fun parse(bytes: ByteArray): AuthTokens? {
            val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
            val at = text.indexOf(BETWEEN)
            if (at <= 0 || at == text.lastIndex || text.indexOf(BETWEEN, at + 1) >= 0) return null
            return AuthTokens(text.substring(0, at), text.substring(at + 1))
        }

        fun decode(kept: String): ByteArray? {
            if (!kept.startsWith(PREFIX)) return null
            return runCatching { Base64.decode(kept.substring(PREFIX.length)) }.getOrNull()
        }
    }
}
