package app.kadans.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerAddressTests {
    private val dev = "http://localhost:5199"
    private val production = "https://api.kadans.app"

    @Test
    fun a_dev_build_talks_to_the_platform_default() {
        assertEquals(dev, ServerAddress.resolve(override = null, builtFor = null, platformDefault = dev))
    }

    @Test
    fun a_release_build_talks_to_the_server_it_was_built_for() {
        assertEquals(production, ServerAddress.resolve(override = null, builtFor = production, platformDefault = dev))
    }

    @Test
    fun what_was_typed_on_the_login_screen_wins_in_a_dev_build() {
        assertEquals("http://192.168.1.10:5199", ServerAddress.resolve("http://192.168.1.10:5199", null, dev))
    }

    @Test
    fun a_build_made_for_a_server_ignores_any_address_saved_on_the_device() {
        // Typed into an earlier dev build, or planted: the sign-ins still go only where the build says.
        assertEquals(production, ServerAddress.resolve("https://elsewhere.example", production, dev))
    }

    @Test
    fun blanks_count_as_unset() {
        assertEquals(production, ServerAddress.resolve(override = " ", builtFor = production, platformDefault = dev))
        assertEquals(dev, ServerAddress.resolve(override = " ", builtFor = "", platformDefault = dev))
    }
}
