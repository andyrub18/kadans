package kadans

/**
 * The desktop app's entry point, named for Linux docks and task bars. AWT names the window's X11 class after the main
 * class, dots made dashes: `kadans.Kadans` gives "kadans-Kadans", the name jpackage gives the installed launcher
 * (`kadans-Kadans.desktop`, which has no StartupWMClass). That is how COSMIC, GNOME and KDE match the running window to
 * the launcher and show its icon instead of a generic one.
 */
object Kadans {
    @JvmStatic
    fun main(args: Array<String>) = app.kadans.desktop.main()
}
