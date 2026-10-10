package app.kadans.startup

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An installed desktop app opens with the session from its first run; the person's later choice is kept. */
class StartAtLoginTests {
    private class FakeSystem(override val available: Boolean = true, var entry: Boolean = false) : StartAtLogin {
        val writes = mutableListOf<Boolean>()

        override fun isEnabled() = entry

        override fun setEnabled(enabled: Boolean) {
            writes += enabled
            entry = enabled
        }
    }

    @Test
    fun the_first_launch_turns_it_on_once() {
        val settings = MapSettings()
        val system = FakeSystem()

        system.onLaunch(settings)

        assertTrue(system.entry)
        assertEquals(listOf(true), system.writes)
    }

    @Test
    fun later_launches_rewrite_an_entry_still_there() {
        val settings = MapSettings()
        val system = FakeSystem()
        system.onLaunch(settings)

        system.onLaunch(settings) // the app may have moved: the entry is written again with its path

        assertEquals(listOf(true, true), system.writes)
    }

    @Test
    fun an_entry_the_person_removed_stays_removed() {
        val settings = MapSettings()
        val system = FakeSystem()
        system.onLaunch(settings)
        system.entry = false // turned off in Settings, or in the system's own

        system.onLaunch(settings)

        assertFalse(system.entry)
        assertEquals(listOf(true), system.writes)
    }

    @Test
    fun a_phone_or_a_dev_run_is_left_alone() {
        val settings = MapSettings()
        val system = FakeSystem(available = false)

        system.onLaunch(settings)
        NoStartAtLogin.onLaunch(settings)

        assertEquals(emptyList(), system.writes)
        assertFalse(settings.hasKey(StartAtLogin.DECIDED_KEY), "a later installed run still gets its first launch")
    }
}
