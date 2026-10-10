package app.kadans.startup

/** A phone starts nothing at sign-in: the system puts the next reminder's alarm back after a reboot itself. */
actual fun platformStartAtLogin(): StartAtLogin = NoStartAtLogin
