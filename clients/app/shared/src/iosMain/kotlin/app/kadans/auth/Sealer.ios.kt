package app.kadans.auth

/**
 * The iPhone's Keychain comes with the iPhone app (it needs a Mac to build and to try): until then the session stays
 * in the app's own settings there, as it did, and the iPhone app is not released (docs/ROADMAP.md).
 */
actual fun platformSealer(): Sealer = NoSealer
