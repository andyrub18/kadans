package app.kadans.startup

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import org.koin.core.context.GlobalContext

/** jpackage's launcher names itself in `jpackage.app-path`; a dev run (Gradle's java) has no launcher to start. */
actual fun platformStartAtLogin(): StartAtLogin {
    val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: return NoStartAtLogin
    val os = System.getProperty("os.name").lowercase()
    return when {
        "linux" in os -> XdgAutostart(launcher, xdgConfigHome().resolve("autostart"))
        "windows" in os -> WindowsRunKey(launcher)
        else -> NoStartAtLogin // macOS: a login item comes with the Mac build
    }
}

/** The desktop app starts: an installed one opens with the session from its first run on ([onLaunch]). */
fun applyStartAtLogin() {
    val koin = GlobalContext.getOrNull() ?: return
    koin.get<StartAtLogin>().onLaunch(koin.get())
}

private fun xdgConfigHome(): Path =
    System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")

/**
 * Linux: an XDG autostart entry, which COSMIC, GNOME, KDE and Xfce sessions start at sign-in (JetBrains Toolbox does the
 * same). Off is no entry; an entry the person turned off in their desktop's settings (`Hidden=true`, or GNOME's
 * `X-GNOME-Autostart-enabled=false`) counts as off. `TryExec`: once Kadans is uninstalled, the session skips it.
 */
internal class XdgAutostart(private val launcher: Path, private val folder: Path) : StartAtLogin {
    private val entry: Path = folder.resolve("kadans.desktop")

    override val available = true

    override fun isEnabled(): Boolean = runCatching {
        Files.isRegularFile(entry) && Files.readAllLines(entry).none { it.trim() in TURNED_OFF }
    }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) {
                Files.createDirectories(folder)
                // Whole or not at all: a session never reads half an entry.
                val draft = Files.createTempFile(folder, "kadans", ".tmp")
                Files.writeString(draft, contents())
                Files.move(draft, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } else {
                Files.deleteIfExists(entry)
            }
        }
    }

    internal fun contents(): String {
        // jpackage installs the icon beside the launcher's folder: /opt/kadans/bin/Kadans, /opt/kadans/lib/Kadans.png.
        val icon = launcher.parent?.parent?.resolve("lib/Kadans.png")?.takeIf { Files.isRegularFile(it) }
        return buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=Kadans")
            appendLine("Exec=${execArgument(launcher.toString())} ${StartAtLogin.BACKGROUND}")
            appendLine("TryExec=${stringValue(launcher.toString())}")
            icon?.let { appendLine("Icon=${stringValue(it.toString())}") }
            appendLine("Terminal=false")
            appendLine("X-GNOME-Autostart-enabled=true")
        }
    }

    internal companion object {
        private val TURNED_OFF = setOf("Hidden=true", "X-GNOME-Autostart-enabled=false")

        /**
         * One argument of an `Exec` line, by the Desktop Entry spec: in double quotes, `"`, `` ` ``, `$` and `\` take a
         * backslash, `%` is doubled (field codes), then the value's own escaping doubles every backslash again.
         */
        fun execArgument(argument: String): String {
            val quoted = buildString {
                append('"')
                argument.forEach { if (it in "\"`$\\") append('\\').append(it) else append(it) }
                append('"')
            }
            return stringValue(quoted).replace("%", "%%")
        }

        /** A string value: backslashes doubled, line breaks escaped. */
        fun stringValue(value: String): String =
            value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }
}

/**
 * Windows: a value under HKCU\…\CurrentVersion\Run, which Windows starts at sign-in (Settings → Apps → Startup lists
 * it and can turn it off, which Windows records under StartupApproved: read as off). Written by importing a .reg file
 * so the quoted path, with its spaces, reaches the registry intact; a quoted argument passed through ProcessBuilder to
 * reg.exe may not. Read and removed with reg.exe. Not run on Windows yet.
 */
internal class WindowsRunKey(
    private val launcher: Path,
    private val reg: (List<String>) -> Pair<Int, String> = ::runReg,
) : StartAtLogin {
    override val available = true

    override fun isEnabled(): Boolean {
        if (reg(listOf("query", RUN, "/v", VALUE)).first != 0) return false
        // Turned off in Windows' own settings: a binary whose first byte is odd (03 off, 02 on).
        val approved = reg(listOf("query", APPROVED, "/v", VALUE))
        val data = approved.second.lineSequence().firstOrNull { "REG_BINARY" in it }?.substringAfter("REG_BINARY")?.trim()
        return approved.first != 0 || data == null || (data.take(2).toIntOrNull(16) ?: 2) % 2 == 0
    }

    override fun setEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) {
                val file = Files.createTempFile("kadans-startup", ".reg")
                try {
                    Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + regFile().toByteArray(Charsets.UTF_16LE))
                    reg(listOf("import", file.toString()))
                } finally {
                    Files.deleteIfExists(file)
                }
                // Turned on here after being turned off in Windows' settings: the person's latest choice wins.
                reg(listOf("delete", APPROVED, "/v", VALUE, "/f"))
            } else {
                reg(listOf("delete", RUN, "/v", VALUE, "/f"))
            }
        }
    }

    /** UTF-16 like the files regedit writes; a .reg string escapes `\` and `"` with a backslash. */
    internal fun regFile(): String {
        val command = "\"$launcher\" ${StartAtLogin.BACKGROUND}"
        val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
        return "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\$RUN_PATH]\r\n\"$VALUE\"=\"$escaped\"\r\n"
    }

    internal companion object {
        const val VALUE = "Kadans"
        private const val RUN_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val RUN = "HKCU\\$RUN_PATH"
        const val APPROVED = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

        private fun runReg(arguments: List<String>): Pair<Int, String> {
            val process = ProcessBuilder(listOf("reg") + arguments).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy()
            return (if (process.isAlive) -1 else process.exitValue()) to output
        }
    }
}
