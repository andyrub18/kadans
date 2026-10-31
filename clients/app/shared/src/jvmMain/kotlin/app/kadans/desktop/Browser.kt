package app.kadans.desktop

import java.net.URI

/** Opens [url] in the person's browser: Google sign-in, the update's download page. */
internal fun openInBrowser(url: String) {
    val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
    if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
        desktop.browse(URI(url))
        return
    }
    // Minimal Linux window managers have no AWT desktop integration, but do ship xdg-open.
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        "mac" in os -> listOf("open", url)
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
    }
    ProcessBuilder(command).start()
}
