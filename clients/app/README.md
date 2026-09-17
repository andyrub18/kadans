# Kadans client

Compose Multiplatform app for Android, iOS and desktop, in the current JetBrains template
structure: all UI and logic live in `shared` (a KMP library); `androidApp`, `desktopApp` and
`iosApp` are thin launchers.

```bash
./gradlew :desktopApp:run          # desktop (Linux/Windows/macOS)
./gradlew :androidApp:assembleDebug
# iOS: open iosApp/iosApp.xcodeproj in Xcode (macOS only)
```

**Continue with Google** appears on the Login screen only when the server publishes the client id this
platform needs (`GET /auth/providers`; setup in `docs/OWNER-CHECKLIST.md`). Desktop opens the system
browser and listens on a throwaway `127.0.0.1` port (loopback + PKCE), then lets the server exchange the
code; Android uses Credential Manager; iOS has no flow yet. The platform code sits behind
`app.kadans.auth.GoogleSignIn`.

`local.properties` (untracked) must point at the Android SDK: `sdk.dir=/path/to/Android/Sdk`.
