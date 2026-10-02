package app.kadans.profile

import app.kadans.api.KadansApi
import app.kadans.api.model.UpdateSelfUserRequest
import app.kadans.i18n.Language
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LanguageSync
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ProfileSyncTests {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun only_what_differs_is_sent() {
        assertNull(ProfileSync.changes("America/Port-au-Prince", "America/Port-au-Prince", null))
        // Not following the device (or a device without a usable zone): the account's zone stays.
        assertNull(ProfileSync.changes("Europe/Paris", null, null))
        assertEquals(
            UpdateSelfUserRequest(timeZone = "America/Port-au-Prince"),
            ProfileSync.changes("UTC", "America/Port-au-Prince", null),
        )
        assertEquals(UpdateSelfUserRequest(language = "ht"), ProfileSync.changes("UTC", "UTC", "ht"))
        assertEquals(
            UpdateSelfUserRequest(timeZone = "Europe/Paris", language = "fr"),
            ProfileSync.changes("America/Port-au-Prince", "Europe/Paris", "fr"),
        )
    }

    @Test
    fun the_latest_explicit_language_choice_wins() {
        // A fresh install takes the account's language instead of pushing the phone's.
        assertEquals(LanguageSync.Adopt("ht"), LanguageController.decide(chosen = null, synced = null, account = "ht"))
        // Chosen here while signed out (or the call failed): the account learns it.
        assertEquals(LanguageSync.Push("fr"), LanguageController.decide(chosen = "fr", synced = null, account = "en"))
        assertEquals(LanguageSync.Push("fr"), LanguageController.decide(chosen = "fr", synced = "ht", account = "ht"))
        // Already sent from here, then changed on another device: this one follows.
        assertEquals(LanguageSync.Adopt("en"), LanguageController.decide(chosen = "ht", synced = "ht", account = "en"))
        assertEquals(LanguageSync.None, LanguageController.decide(chosen = "ht", synced = "ht", account = "ht"))
    }

    /** A server whose account has [zone] and [language]; records each profile update it receives. */
    private class Server(val zone: String, val language: String) {
        val updates = mutableListOf<String>()
    }

    private fun Server.api(): KadansApi = KadansApi.create(
        "http://test",
        engine = MockEngine { request ->
            if (request.method == HttpMethod.Put) updates += request.body.toByteArray().decodeToString()
            respond(
                """{"id":"u1","username":"alice","timeZone":"$zone","language":"$language"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        },
    )

    @Test
    fun a_new_install_takes_the_accounts_language_and_gives_it_the_devices_zone() = runTest {
        val server = Server(zone = "UTC", language = "ht")
        val settings = MapSettings()
        val languages = LanguageController(settings, server.api())

        ProfileSync(server.api(), TimeZonePreference(settings), languages) { "America/Port-au-Prince" }.sync()

        assertEquals(Language.Ht, languages.language.value)
        assertEquals(listOf("""{"timeZone":"America/Port-au-Prince"}"""), server.updates)
        // Adopted, so the next start has nothing to push.
        assertEquals(LanguageSync.None, languages.syncWith("ht"))
    }

    @Test
    fun a_language_chosen_here_reaches_the_account_and_is_remembered_as_sent() = runTest {
        val server = Server(zone = "America/Port-au-Prince", language = "en")
        val settings = MapSettings("kadans.language" to "fr")
        val languages = LanguageController(settings, server.api())

        ProfileSync(server.api(), TimeZonePreference(settings), languages) { "America/Port-au-Prince" }.sync()

        assertEquals(listOf("""{"language":"fr"}"""), server.updates)
        assertEquals(LanguageSync.None, languages.syncWith("fr"))
    }

    @Test
    fun a_zone_picked_by_hand_is_left_alone() = runTest {
        val server = Server(zone = "Europe/Paris", language = "en")
        val settings = MapSettings("kadans.language" to "en", "kadans.language.synced" to "en")
        val timeZones = TimeZonePreference(settings).apply { followsDevice = false }

        ProfileSync(server.api(), timeZones, LanguageController(settings, server.api())) { "America/Port-au-Prince" }.sync()

        assertEquals(emptyList(), server.updates)
    }

    @Test
    fun a_device_without_a_usable_zone_changes_nothing() = runTest {
        val server = Server(zone = "Europe/Paris", language = "en")
        val settings = MapSettings("kadans.language" to "en", "kadans.language.synced" to "en")

        ProfileSync(server.api(), TimeZonePreference(settings), LanguageController(settings, server.api())) { null }.sync()

        assertEquals(emptyList(), server.updates)
        assertEquals(true, TimeZonePreference(MapSettings()).followsDevice, "following the device is the default")
    }
}
