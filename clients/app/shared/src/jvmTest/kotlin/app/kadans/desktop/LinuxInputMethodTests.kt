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
        assertEquals(LinuxInputMethod.BUILT_IN, LinuxInputMethod.choose("@im=ibus") { false })
        assertNull(LinuxInputMethod.choose("@im=ibus") { true })
        // Already Xlib's own: nothing to look for.
        assertNull(LinuxInputMethod.choose("@im=none") { error("not asked") })
    }

    /** On a desktop with X (not CI): Xlib's own always opens, a server nobody runs never does. */
    @Test
    fun on_this_display_xlibs_own_opens_and_a_missing_server_does_not() {
        if (System.getenv("DISPLAY").isNullOrBlank() || "linux" !in System.getProperty("os.name").lowercase()) return
        assertTrue(LinuxInputMethod.opens(LinuxInputMethod.BUILT_IN))
        assertFalse(LinuxInputMethod.opens("@im=kadans-test-no-such-server"))
    }
}
