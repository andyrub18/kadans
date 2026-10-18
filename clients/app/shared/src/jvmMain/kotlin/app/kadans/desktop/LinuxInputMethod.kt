package app.kadans.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * Accents on Linux. Java combines a keyboard's dead keys (AltGr+` then e: è, AltGr+6 then e: ê) through the X input
 * method the session names in XMODIFIERS (`@im=ibus` on Pop!_OS and Ubuntu). When that one is not running (IBus is
 * installed but COSMIC does not start it), Java gets no input method at all and a dead key never meets its letter: no è,
 * ê or à in any field. GTK and Qt combine them by themselves, so only Java apps show it.
 *
 * Kadans then runs with Xlib's own input method (`@im=none`: the locale's Compose table), which combines them. A running
 * IBus or Fcitx is kept (it may do more, like other scripts). It must happen before anything of AWT starts: Xlib takes the
 * modifiers once, when AWT opens its input method.
 */
object LinuxInputMethod {
    /** Xlib's own input method. */
    const val BUILT_IN = "@im=none"

    /**
     * First thing in main: the input method the session names, or Xlib's own when that one is not running. Says what it
     * changed (for the log); null when it changed nothing. Never fails a start.
     */
    fun ensure(environment: Map<String, String> = System.getenv()): String? {
        if ("linux" !in System.getProperty("os.name").lowercase() || environment["DISPLAY"].isNullOrBlank()) return null
        val named = environment["XMODIFIERS"] ?: return null
        return runCatching {
            val chosen = choose(named, ::opens) ?: return null
            setenv("XMODIFIERS", chosen)
            setLocaleModifiers(chosen)
            "The input method $named is not running: Kadans uses the built-in one, so accents typed with dead keys work."
        }.getOrNull()
    }

    /** What to run with instead of [named], or null to keep it: Xlib's own when it names a server that does not open. */
    internal fun choose(named: String, opens: (String) -> Boolean): String? =
        if (namesServer(named) && !opens(named)) BUILT_IN else null

    /** "@im=ibus" names a server to connect to; "@im=none", "@im=local", "@im=" or nothing is Xlib's own. */
    internal fun namesServer(modifiers: String): Boolean {
        val name = Regex("@im=([^@]*)").find(modifiers)?.groupValues?.get(1)?.trim() ?: return false
        return name.isNotEmpty() && name != "none" && name != "local"
    }

    /** Whether an X input method opens with [modifiers], as AWT will ask for one. True without an X display. */
    internal fun opens(modifiers: String): Boolean = Arena.ofConfined().use { arena ->
        x11("XSetLocaleModifiers", FunctionDescriptor.of(ADDRESS, ADDRESS), arena.allocateFrom(modifiers))
        val display = x11("XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS), MemorySegment.NULL) as MemorySegment
        if (display == MemorySegment.NULL) return true
        try {
            val im = x11(
                "XOpenIM", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
            ) as MemorySegment
            if (im == MemorySegment.NULL) return false
            x11("XCloseIM", FunctionDescriptor.of(JAVA_INT, ADDRESS), im)
            true
        } finally {
            x11("XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS), display)
        }
    }

    /** Xlib reads XMODIFIERS from the process's environment (`getenv`), which Java cannot change but libc can. */
    private fun setenv(name: String, value: String) = Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        linker.downcallHandle(
            linker.defaultLookup().find("setenv").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
        ).invokeWithArguments(arena.allocateFrom(name), arena.allocateFrom(value), 1)
    }

    /** And Xlib's own setting, in case AWT opens its input method without reading the environment again. */
    private fun setLocaleModifiers(value: String) = Arena.ofConfined().use { arena ->
        x11("XSetLocaleModifiers", FunctionDescriptor.of(ADDRESS, ADDRESS), arena.allocateFrom(value))
    }

    /** libX11, loaded for good: AWT loads the same one, so the settings made here are the ones it finds. */
    private val libX11 by lazy { SymbolLookup.libraryLookup("libX11.so.6", Arena.global()) }

    private fun x11(name: String, descriptor: FunctionDescriptor, vararg args: Any): Any? =
        Linker.nativeLinker().downcallHandle(libX11.find(name).orElseThrow(), descriptor).invokeWithArguments(*args)
}
