package app.kadans.startup

/** No reminders ring from an iPhone yet (no build without a Mac); nothing to start at sign-in either way. */
actual fun platformStartAtLogin(): StartAtLogin = NoStartAtLogin
