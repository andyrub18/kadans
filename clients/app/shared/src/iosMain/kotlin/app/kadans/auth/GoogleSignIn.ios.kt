package app.kadans.auth

/** iOS needs the GoogleSignIn SDK wired from Xcode (and a Mac to build it) — deferred with iOS push. */
actual fun platformGoogleSignIn(returnToAppText: () -> String): GoogleSignIn = NoGoogleSignIn
