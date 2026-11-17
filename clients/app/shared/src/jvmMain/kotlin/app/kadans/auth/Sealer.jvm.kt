package app.kadans.auth

/**
 * The desktop's own protection: the keyring on Linux, the system's data protection on Windows. The Mac's Keychain
 * comes with the Mac build (it needs a Mac to build and to try): until then the session stays in the settings there.
 */
actual fun platformSealer(): Sealer {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "linux" in os -> KeyringSealer()
        "windows" in os -> WindowsSealer()
        else -> NoSealer
    }
}
