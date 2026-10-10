package app.kadans.startup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The entries sessions start Kadans from: an XDG autostart file on Linux, a Run value on Windows (with a fake reg.exe). */
class DesktopStartAtLoginTests {
    private val root = Files.createTempDirectory("kadans-startup")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    /** As jpackage installs it: /opt/kadans/bin/Kadans and /opt/kadans/lib/Kadans.png. */
    private fun installed(folder: String = "opt/kadans"): Path {
        val app = root.resolve(folder)
        Files.createDirectories(app.resolve("bin"))
        Files.createDirectories(app.resolve("lib"))
        Files.writeString(app.resolve("lib/Kadans.png"), "png")
        return app.resolve("bin/Kadans").also { Files.writeString(it, "launcher") }
    }

    @Test
    fun linux_writes_an_autostart_entry_that_opens_kadans_in_the_background() {
        val launcher = installed()
        val autostart = XdgAutostart(launcher, root.resolve("config/autostart"))
        assertFalse(autostart.isEnabled())

        autostart.setEnabled(true)

        val entry = root.resolve("config/autostart/kadans.desktop")
        val lines = Files.readAllLines(entry)
        assertTrue(autostart.isEnabled())
        assertEquals("[Desktop Entry]", lines.first())
        assertTrue("Exec=\"$launcher\" --background" in lines, lines.joinToString("\n"))
        assertTrue("TryExec=$launcher" in lines)
        assertTrue("Icon=${launcher.parent.parent.resolve("lib/Kadans.png")}" in lines)
        assertTrue("X-GNOME-Autostart-enabled=true" in lines)

        autostart.setEnabled(false)
        assertFalse(Files.exists(entry))
        assertFalse(autostart.isEnabled())
    }

    @Test
    fun an_entry_turned_off_in_the_desktops_settings_reads_as_off() {
        val autostart = XdgAutostart(installed(), root.resolve("autostart"))
        autostart.setEnabled(true)
        val entry = root.resolve("autostart/kadans.desktop")

        Files.writeString(entry, Files.readString(entry) + "Hidden=true\n")
        assertFalse(autostart.isEnabled())

        Files.writeString(entry, Files.readString(entry).replace("Hidden=true\n", "").replace("X-GNOME-Autostart-enabled=true", "X-GNOME-Autostart-enabled=false"))
        assertFalse(autostart.isEnabled())
    }

    @Test
    fun exec_quotes_a_path_as_the_desktop_entry_spec_wants() {
        assertEquals("\"/opt/Kadans App/bin/Kadans\"", XdgAutostart.execArgument("/opt/Kadans App/bin/Kadans"))
        // $ and " take a backslash inside the quotes, which the value's own escaping doubles; % is doubled.
        assertEquals("\"/home/a\\\\\$b/100%%/x\\\\\"y\"", XdgAutostart.execArgument("/home/a\$b/100%/x\"y"))
        assertEquals("\"C:\\\\\\\\apps\"", XdgAutostart.execArgument("C:\\apps"))
    }

    @Test
    fun windows_imports_a_run_value_with_the_quoted_path() {
        val launcher = Path.of("C:\\Program Files\\Kadans\\Kadans.exe")
        val calls = mutableListOf<List<String>>()
        var imported: ByteArray? = null
        val runKey = WindowsRunKey(launcher) { arguments ->
            calls += arguments
            if (arguments.first() == "import") imported = Files.readAllBytes(Path.of(arguments[1]))
            0 to ""
        }

        runKey.setEnabled(true)

        assertEquals("import", calls[0][0])
        val bytes = imported!!
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), bytes.copyOf(2)) // UTF-16, as regedit writes
        val text = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        assertTrue("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]" in text, text)
        assertTrue("\"Kadans\"=\"\\\"C:\\\\Program Files\\\\Kadans\\\\Kadans.exe\\\" --background\"" in text, text)
        // Turned on here after Windows' settings turned it off: that record goes.
        assertEquals(listOf("delete", WindowsRunKey.APPROVED, "/v", "Kadans", "/f"), calls[1])
        assertFalse(Files.exists(Path.of(calls[0][1])), "the .reg file is removed")

        runKey.setEnabled(false)
        assertEquals(listOf("delete", WindowsRunKey.RUN, "/v", "Kadans", "/f"), calls.last())
    }

    @Test
    fun windows_reads_the_value_and_what_its_own_settings_say() {
        fun runKey(run: Int, approved: Pair<Int, String>) = WindowsRunKey(Path.of("C:\\Kadans.exe")) { arguments ->
            if (arguments[1] == WindowsRunKey.RUN) run to "" else approved
        }

        assertFalse(runKey(run = 1, approved = 1 to "").isEnabled(), "no value")
        assertTrue(runKey(run = 0, approved = 1 to "").isEnabled(), "a value, never turned off")
        val off = "\r\n    Kadans    REG_BINARY    030000006C4E5EB3D29ADC01\r\n"
        assertFalse(runKey(run = 0, approved = 0 to off).isEnabled(), "turned off in Settings → Apps → Startup")
        val on = "\r\n    Kadans    REG_BINARY    020000000000000000000000\r\n"
        assertTrue(runKey(run = 0, approved = 0 to on).isEnabled(), "turned back on there")
    }
}
