package app.kadans.ui

import app.kadans.api.model.DevicePlatform
import app.kadans.api.model.DeviceResponse
import app.kadans.api.model.UserResponse
import app.kadans.i18n.CreoleStrings
import app.kadans.i18n.EnglishStrings
import app.kadans.i18n.FrenchStrings
import app.kadans.ui.settings.SettingsUiState
import app.kadans.ui.settings.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SettingsAccountTests {
    private fun device(id: String, lastSeen: String) =
        DeviceResponse(id, DevicePlatform.Android, "Pixel $id", hasPushToken = true, registeredAt = Instant.parse("2026-09-01T00:00:00Z"), lastSeenAt = Instant.parse(lastSeen))

    @Test
    fun this_device_comes_first_then_the_most_recently_seen() {
        val sorted = SettingsViewModel.sortDevices(
            listOf(device("old", "2026-09-01T10:00:00Z"), device("mine", "2026-09-10T10:00:00Z"), device("recent", "2026-09-17T10:00:00Z")),
            thisInstallationId = "mine",
        )

        assertEquals(listOf("mine", "recent", "old"), sorted.map { it.installationId })
    }

    @Test
    fun without_a_known_install_the_list_is_simply_newest_first() {
        val sorted = SettingsViewModel.sortDevices(listOf(device("a", "2026-09-01T10:00:00Z"), device("b", "2026-09-02T10:00:00Z")), null)

        assertEquals(listOf("b", "a"), sorted.map { it.installationId })
    }

    private val user = UserResponse(id = "u1", username = "alice", email = "alice@example.com", timeZone = "UTC")

    @Test
    fun an_email_change_needs_a_different_plausible_address() {
        val state = SettingsUiState(user = user, isLoading = false)

        assertEquals(false, state.canRequestEmailChange)
        assertEquals(false, state.copy(newEmail = "not-an-email").canRequestEmailChange)
        assertEquals(false, state.copy(newEmail = " ALICE@example.com ").canRequestEmailChange) // the address they already have
        assertEquals(true, state.copy(newEmail = "alice@new.example").canRequestEmailChange)
        assertEquals(false, state.copy(newEmail = "alice@new.example", isBusy = true).canRequestEmailChange)
    }

    @Test
    fun the_sent_message_names_the_address_in_every_language() {
        listOf(EnglishStrings, FrenchStrings, CreoleStrings).forEach { strings ->
            assertTrue("alice@new.example" in strings.account.emailChangeSent("alice@new.example"))
        }
    }
}
