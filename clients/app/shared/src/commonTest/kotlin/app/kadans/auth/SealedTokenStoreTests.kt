package app.kadans.auth

import app.kadans.api.AuthTokens
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The session as the app's settings hold it (ARCHITECTURE → "The session, sealed on the device"): sealed, never the
 * tokens; an earlier version's clear copy sealed and removed; what no longer opens dropped like a sign-out; nothing in
 * the clear where the device has a protection that did not answer.
 */
class SealedTokenStoreTests {
    /** A device's protection, as simple as a test needs: each byte turned by [key]. Another key opens nothing. */
    private class FakeSealer(var key: Int = 7) : Sealer {
        var sealing: Sealing? = null
        var opening: Opening? = null
        var seals = 0
        var opens = 0
        var askedAgain = 0

        override suspend fun again() {
            askedAgain++
        }

        override suspend fun seal(plain: ByteArray): Sealing {
            seals++
            return sealing ?: Sealing.Done(byteArrayOf(key.toByte()) + plain.map { (it + key).toByte() })
        }

        override suspend fun open(sealed: ByteArray): Opening {
            opens++
            opening?.let { return it }
            if (sealed.isEmpty() || sealed[0] != key.toByte()) return Opening.Lost
            return Opening.Done(sealed.drop(1).map { (it - key).toByte() }.toByteArray())
        }
    }

    private val settings = MapSettings()
    private val sealer = FakeSealer()
    private var lost = 0
    private val tokens = AuthTokens("access.jwt.one", "refresh-one")

    private fun store() = SealedTokenStore(settings, sealer, onLost = { lost++ })

    private fun clear() = settings.getStringOrNull(SealedTokenStore.ACCESS_KEY) to settings.getStringOrNull(SealedTokenStore.REFRESH_KEY)

    private fun sealed() = settings.getStringOrNull(SealedTokenStore.SEALED)

    /** No value the settings hold has a token in it. */
    private fun assertNothingReadable() {
        for (key in settings.keys) {
            val value = settings.getStringOrNull(key).orEmpty()
            assertFalse("access" in value || "refresh" in value || "jwt" in value, "$key holds a token in the clear")
        }
    }

    @Test
    fun a_session_is_kept_sealed_and_comes_back_at_the_next_start() = runTest {
        store().save(tokens)

        assertNotNull(sealed())
        assertTrue(sealed()!!.startsWith(SealedTokenStore.PREFIX))
        assertEquals(null to null, clear())
        assertNothingReadable()
        assertEquals(tokens, store().load())
    }

    @Test
    fun the_devices_protection_is_asked_once_a_run() = runTest {
        store().save(tokens)
        val next = store()

        repeat(5) { assertEquals(tokens, next.load()) }

        assertEquals(1, sealer.opens)
    }

    @Test
    fun an_earlier_versions_clear_session_is_sealed_and_the_clear_copy_removed() = runTest {
        settings.putString(SealedTokenStore.ACCESS_KEY, tokens.accessToken)
        settings.putString(SealedTokenStore.REFRESH_KEY, tokens.refreshToken)

        assertEquals(tokens, store().load())

        assertEquals(null to null, clear())
        assertNothingReadable()
        assertEquals(tokens, store().load())
        assertEquals(0, lost)
    }

    @Test
    fun signing_out_leaves_nothing() = runTest {
        val store = store()
        store.save(tokens)

        store.save(null)

        assertNull(sealed())
        assertEquals(null to null, clear())
        assertNull(store.load())
        assertNull(store().load())
    }

    @Test
    fun a_new_pair_replaces_the_old_one() = runTest {
        val store = store()
        store.save(tokens)
        val rotated = AuthTokens("access.jwt.two", "refresh-two")

        store.save(rotated)

        assertEquals(rotated, store.load())
        assertEquals(rotated, store().load())
    }

    @Test
    fun a_session_sealed_elsewhere_is_dropped_like_a_sign_out() = runTest {
        store().save(tokens)
        sealer.key = 9 // another device's key: a copy of the settings, a key replaced

        assertNull(store().load())

        assertNull(sealed())
        assertEquals(1, lost)
    }

    @Test
    fun a_value_that_is_not_a_sealed_session_is_dropped() = runTest {
        for (junk in listOf("", "garbage", "2:AAAA", "1:not base64 !", "1:")) {
            settings.putString(SealedTokenStore.SEALED, junk)

            assertNull(store().load(), junk)
            assertNull(sealed(), junk)
        }
    }

    @Test
    fun a_sealed_session_waits_when_the_protection_does_not_answer() = runTest {
        store().save(tokens)
        val kept = sealed()
        sealer.opening = Opening.Refused // the keyring left locked

        val store = store()
        assertNull(store.load())
        assertNull(store.load())

        assertEquals(kept, sealed())
        assertEquals(1, sealer.opens) // asked once: the person is not asked again at every look
        assertEquals(0, lost)
        // The next start, the keyring open: signed in as before.
        sealer.opening = null
        assertEquals(tokens, store().load())
    }

    @Test
    fun signing_in_over_a_session_that_never_opened_forgets_that_account() = runTest {
        store().save(tokens)
        sealer.opening = Opening.Refused
        sealer.sealing = Sealing.Refused
        val store = store()
        assertNull(store.load())
        val other = AuthTokens("access.jwt.other", "refresh-other")

        store.save(other)

        assertEquals(1, lost) // the reminders and the widget's list may have been someone else's
        assertEquals(other, store.load())
        // Nothing of it is written: it lasts the run.
        assertNull(sealed())
        assertEquals(null to null, clear())
        sealer.opening = null
        assertNull(store().load())
    }

    @Test
    fun nothing_is_written_in_the_clear_when_the_protection_does_not_answer() = runTest {
        sealer.sealing = Sealing.Refused
        val store = store()

        store.save(tokens)

        assertEquals(tokens, store.load())
        assertTrue(settings.keys.isEmpty())
    }

    @Test
    fun an_earlier_versions_clear_session_follows_until_the_protection_answers() = runTest {
        settings.putString(SealedTokenStore.ACCESS_KEY, tokens.accessToken)
        settings.putString(SealedTokenStore.REFRESH_KEY, tokens.refreshToken)
        sealer.sealing = Sealing.Refused
        val store = store()
        assertEquals(tokens, store.load())
        // The server hands out a new pair: the old refresh token is dead from then on.
        val rotated = AuthTokens("access.jwt.two", "refresh-two")

        store.save(rotated)

        assertEquals(rotated.accessToken to rotated.refreshToken, clear())
        // The next start, the keyring open: sealed, and the clear copy gone.
        sealer.sealing = null
        assertEquals(rotated, store().load())
        assertEquals(null to null, clear())
        assertNothingReadable()
    }

    @Test
    fun a_device_with_nothing_to_seal_with_keeps_the_session_as_before() = runTest {
        val plain = SealedTokenStore(settings, NoSealer, onLost = { lost++ })

        plain.save(tokens)

        assertEquals(tokens.accessToken to tokens.refreshToken, clear())
        assertNull(sealed())
        assertEquals(tokens, SealedTokenStore(settings, NoSealer).load())
        plain.save(null)
        assertEquals(null to null, clear())
    }

    @Test
    fun a_keyring_that_went_away_ends_the_sealed_session() = runTest {
        store().save(tokens)

        assertNull(SealedTokenStore(settings, NoSealer, onLost = { lost++ }).load())

        assertNull(sealed())
        assertEquals(1, lost)
    }

    @Test
    fun a_keyring_that_arrives_seals_the_clear_session() = runTest {
        SealedTokenStore(settings, NoSealer).save(tokens)

        assertEquals(tokens, store().load())

        assertNotNull(sealed())
        assertEquals(null to null, clear())
    }

    @Test
    fun what_forgets_an_account_may_look_at_the_session() = runTest {
        store().save(tokens)
        sealer.key = 9
        lateinit var store: SealedTokenStore
        var seen: AuthTokens? = tokens
        store = SealedTokenStore(settings, sealer, onLost = { seen = store.load() })

        assertNull(store.load())

        assertNull(seen)
    }

    @Test
    fun the_person_asks_again_and_the_waiting_session_opens() = runTest {
        store().save(tokens)
        sealer.opening = Opening.Refused
        val store = store()
        assertNull(store.load())
        assertTrue(store.waiting.value)

        // Still locked: asked, refused again, still waiting.
        assertNull(store.retry())
        assertTrue(store.waiting.value)
        assertEquals(1, sealer.askedAgain)

        sealer.opening = null // the keyring open now
        assertEquals(tokens, store.retry())

        assertFalse(store.waiting.value)
        assertEquals(tokens, store.load())
        assertEquals(0, lost)
        // Nothing waits any more: asking again asks nobody.
        assertEquals(tokens, store.retry())
        assertEquals(2, sealer.askedAgain)
    }

    @Test
    fun nothing_waits_where_no_session_was_kept_or_one_was_lost() = runTest {
        val empty = store()
        assertNull(empty.load())
        assertFalse(empty.waiting.value)

        store().save(tokens)
        sealer.key = 9
        val other = store()
        assertNull(other.load())
        assertFalse(other.waiting.value)
    }

    @Test
    fun signing_in_ends_the_wait() = runTest {
        store().save(tokens)
        sealer.opening = Opening.Refused
        val store = store()
        store.load()
        assertTrue(store.waiting.value)

        sealer.opening = null
        store.save(AuthTokens("access.jwt.new", "refresh-new"))

        assertFalse(store.waiting.value)
    }

    @Test
    fun the_two_tokens_come_back_as_they_went_in() {
        val pair = AuthTokens("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl", "kZ3+/8aQ==")

        assertEquals(pair, SealedTokenStore.parse(SealedTokenStore.format(pair)))
        assertNull(SealedTokenStore.parse("only-one".encodeToByteArray()))
        assertNull(SealedTokenStore.parse("\nrefresh".encodeToByteArray()))
        assertNull(SealedTokenStore.parse("access\n".encodeToByteArray()))
        assertNull(SealedTokenStore.parse("a\nb\nc".encodeToByteArray()))
        assertNull(SealedTokenStore.parse(byteArrayOf(0xC3.toByte(), 0x28)))
    }
}
