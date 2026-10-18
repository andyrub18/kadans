package app.kadans.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accents on Linux: the input method the session names, or Xlib's own when that one is not running. */
class LinuxInputMethodTests {
    @Test
    fun only_a_named_server_is_one_to_look_for() {
        assertTrue(LinuxInputMethod.namesServer("@im=ibus"))
        assertTrue(LinuxInputMethod.namesServer("@im=fcitx"))
        assertFalse(LinuxInputMethod.namesServer("@im=none"))
        assertFalse(LinuxInputMethod.namesServer("@im=local"))
        assertFalse(LinuxInputMethod.namesServer("@im="))
        assertFalse(LinuxInputMethod.namesServer(""))
    }

    @Test
    fun a_server_that_is_not_running_gives_way_to_xlibs_own_and_one_that_runs_stays() {
        assertEquals(LinuxInputMethod.BUILT_IN, LinuxInputMethod.choose("@im=ibus", opens = { false }))
        assertNull(LinuxInputMethod.choose("@im=ibus", opens = { true }))
        // Already Xlib's own: nothing to look for.
        assertNull(LinuxInputMethod.choose("@im=none", opens = { error("not asked") }))
    }

    @Test
    fun at_sign_in_an_input_method_that_comes_up_late_is_waited_for_and_kept() {
        var tries = 0
        var pauses = 0
        // IBus registers on the third look: kept, after two pauses.
        assertNull(LinuxInputMethod.choose("@im=ibus", { ++tries >= 3 }, attempts = 31) { pauses++ })
        assertEquals(3, tries)
        assertEquals(2, pauses)

        // One that never comes: Xlib's own, after every try, with no pause after the last.
        tries = 0
        pauses = 0
        assertEquals(LinuxInputMethod.BUILT_IN, LinuxInputMethod.choose("@im=ibus", { tries++; false }, attempts = 31) { pauses++ })
        assertEquals(31, tries)
        assertEquals(30, pauses)
    }

    /** On a desktop with X (not CI): Xlib's own always opens, a server nobody runs never does. */
    @Test
    fun on_this_display_xlibs_own_opens_and_a_missing_server_does_not() {
        if (System.getenv("DISPLAY").isNullOrBlank() || "linux" !in System.getProperty("os.name").lowercase()) return
        assertTrue(LinuxInputMethod.opens(LinuxInputMethod.BUILT_IN))
        assertFalse(LinuxInputMethod.opens("@im=kadans-test-no-such-server"))
    }
}
