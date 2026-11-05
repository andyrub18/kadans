package app.kadans.desktop

/**
 * Where this desktop build came from. The Microsoft Store's package marks its launcher (`tools/release/msix.py`): the
 * Store updates it, and the package's startup task opens it with the session, so the app leaves both alone.
 */
object Distribution {
    const val PROPERTY = "kadans.distribution"
    const val MICROSOFT_STORE = "msstore"

    val microsoftStore: Boolean get() = System.getProperty(PROPERTY) == MICROSOFT_STORE
}
