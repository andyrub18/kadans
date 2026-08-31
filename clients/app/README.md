# Kadans client

Compose Multiplatform app for Android, iOS and desktop, in the current JetBrains template
structure: all UI and logic live in `shared` (a KMP library); `androidApp`, `desktopApp` and
`iosApp` are thin launchers.

```bash
./gradlew :desktopApp:run          # desktop (Linux/Windows/macOS)
./gradlew :androidApp:assembleDebug
# iOS: open iosApp/iosApp.xcodeproj in Xcode (macOS only)
```

`local.properties` (untracked) must point at the Android SDK: `sdk.dir=/path/to/Android/Sdk`.
