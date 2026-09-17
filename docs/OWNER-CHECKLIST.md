# Owner checklist – things only you can do

Accounts, keys and settings the code cannot create for itself. Each item says where the value
plugs in. Secrets go into `dotnet user-secrets` (dev) or environment variables / your host's
secret store (production) – never into `appsettings*.json`.

```bash
# dev secrets are set like this
dotnet user-secrets set "<Key>" "<value>" --project src/Kadans.Api
```

## Email – Resend

- [x] API key → `Email:Resend:ApiKey` (done, dev)
- [ ] Verify a sending domain in Resend (DNS records), then set `Email:From` to an address on it,
      e.g. `Kadans <no-reply@kadans.app>` (`appsettings.json` has a placeholder)
- [ ] To send real mail from dev: `Email:Provider` = `Resend` (dev defaults to `Log`, which prints the
      email – including its link – to the API log)
- [ ] `Email:LinkBaseUrl` = the public URL the emailed links should open (API URL until the client
      handles deep links: `/auth/confirm-email`, `/auth/reset-password`, `/users/me/email/confirm`)

## Google Sign-In

The code is done on both sides (Phase 8); it lights up the moment these values exist. The clients
ask `GET /auth/providers` and show **Continue with Google** only when the server publishes the id
their platform needs – so nothing to rebuild or configure in the apps.

- [ ] One Google Cloud project for everything: the Firebase project **`kadans-420a7`** (every Firebase
      project is a Google Cloud project). The earlier standalone project `kadans-507716` was deleted
      on purpose; the Desktop OAuth client registered from it as `ExternalAuth:Google:ClientIds:0`
      died with it.
- [ ] In `kadans-420a7`: APIs & Services → OAuth consent screen: **Testing** mode, your Google account
      as test user — no domain, homepage or privacy links required.
- [ ] Credentials → **Desktop app** OAuth client → the JVM app's loopback sign-in. The app only gets
      the id; the API does the code exchange, so the secret stays on the server:
      `ExternalAuth:Google:Desktop:ClientId` and `ExternalAuth:Google:Desktop:ClientSecret`
- [ ] Credentials → **Web application** OAuth client (no redirect URIs needed) → Android's Credential
      Manager uses it as `serverClientId`, and Android ID tokens carry it as audience:
      `ExternalAuth:Google:WebClientId`
- [ ] Credentials → **Android** OAuth client: package `app.kadans` + the SHA-1 of your debug keystore
      (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`),
      later a second one with the release keystore's SHA-1. Its id goes nowhere in our config – it only
      has to exist in the same project, it is how Google recognises the app.
- [ ] **iOS** OAuth client (bundle id `app.kadans`) when iOS happens; its id goes into the
      `ExternalAuth:Google:ClientIds` list (extra accepted audiences).

```bash
dotnet user-secrets set "ExternalAuth:Google:Desktop:ClientId" "<…apps.googleusercontent.com>" --project src/Kadans.Api
dotnet user-secrets set "ExternalAuth:Google:Desktop:ClientSecret" "<GOCSPX-…>" --project src/Kadans.Api
dotnet user-secrets set "ExternalAuth:Google:WebClientId" "<…apps.googleusercontent.com>" --project src/Kadans.Api
dotnet user-secrets remove "ExternalAuth:Google:ClientIds:0" --project src/Kadans.Api   # the dead client
# restart the API, then: curl http://localhost:5199/auth/providers   → both ids, never the secret
```

- Accepted ID-token audiences = `ClientIds` + `Desktop:ClientId` + `WebClientId`.
- At public launch: switch the consent screen to Production and add `kadans.app` as an authorized
      domain plus homepage/privacy-policy URLs (verification needs them). Testing mode's 7-day limit
      applies to Google refresh tokens, which Kadans never uses — sign-in consumes fresh ID tokens only.
- To test: desktop → **Continue with Google** opens the browser and the app signs in when you come back;
      Android emulator needs a Google account added in its settings (and a Play Store system image).

## Sign in with Apple

- [ ] Apple Developer account; enable "Sign in with Apple" on the App ID (iOS/macOS bundle id)
- [ ] For non-Apple platforms (Android, desktop, web) a Services ID
- [ ] Bundle id and/or Services ID → `ExternalAuth:Apple:ClientIds`
- No `.p8` key / client secret is needed for ID-token verification.

## Push notifications – Firebase Cloud Messaging

- [x] Firebase project `kadans-420a7`; Android app registered (package `app.kadans`).
      Its `google-services.json` lives at `clients/app/androidApp/google-services.json`
      (gitignored — the Android build works without it, push just stays off)
- [ ] Project settings → Service accounts → generate a private key (JSON) →
      `Push:Firebase:CredentialsJson` (the whole JSON as one secret value) **or** a file path in
      `Push:Firebase:CredentialsFile`
- [ ] `Push:Provider` = `Fcm` (dev defaults to `Log`)
- [ ] iOS (deferred — needs a Mac + paid Apple Developer account): register the iOS app
      (bundle `app.kadans`), add `GoogleService-Info.plist`, and upload the APNs key (.p8)
      in Firebase → Cloud Messaging so FCM can reach iPhones
- Desktop clients do not use push: they hold the SignalR connection (`/hubs/kadans`).

## Testing push on a real Android phone

The server side is done and verified (service-account key at `~/.kadans/firebase-admin.json`,
`Push:Provider=Fcm` in dev user-secrets). To feel it on a phone:

1. Phone and computer on the same Wi-Fi. Find the computer's LAN IP: `ip addr` (e.g. `192.168.1.10`).
2. Start the API listening on all interfaces so the phone can reach it:
   `ASPNETCORE_URLS=http://0.0.0.0:5199 dotnet run --project src/Kadans.Api`
3. Build and install the app: `cd clients/app && ./gradlew :androidApp:assembleDebug`,
   then copy `androidApp/build/outputs/apk/debug/androidApp-debug.apk` to the phone and open it
   (allow "install unknown apps"), or `adb install` it with USB debugging on.
4. In the app, on the **Login screen tap the small ⚙ server line** and enter
   `http://<your-LAN-IP>:5199` — it applies immediately, no restart.
5. Sign in, allow notifications when prompted (Android 13+). Signing in registers the phone's
   FCM token automatically (Settings-era device list shows it as e.g. "Google Pixel").
6. Start a hands-free session with 1-minute phases and lock the phone: each phase change should
   arrive as a real notification, even with the app closed — the server does the counting.

If nothing arrives: check the API log for `FCM: 1 sent` lines, and that the phone kept Wi-Fi on.

## Domain and hosting (later)

- [ ] Domain (e.g. `kadans.app`) – used by `Email:LinkBaseUrl`, `Email:From`, deep links
- [ ] Production Postgres and a host for the API; `ConnectionStrings:kadans`, `Jwt:Key` (≥ 32 random chars)
- [ ] Rotate `InitialAdmin:Password` after first login, or disable seeding (`InitialAdmin:Enabled=false`)

## Client (Compose Multiplatform)

- [ ] Google Play / App Store developer accounts when it is time to ship
- [x] Deep links: `kadans://auth/...` custom scheme handled on Android; the email landing pages
      offer the app link. Verified https App Links / Universal Links wait for the domain.
- [x] Device registration on every sign-in: `PUT /users/me/devices/{installationId}` with the
      FCM token on Android (null elsewhere)
