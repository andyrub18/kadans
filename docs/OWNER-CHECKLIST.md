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

- [x] Google Cloud project (`kadans-507716`) + **Desktop** OAuth client — registered as
      `ExternalAuth:Google:ClientIds:0` in dev user-secrets. The desktop client is the right type
      for the JVM app's future loopback sign-in; its "client secret" is non-confidential by
      Google's definition for installed apps, but still keep the JSON out of git.
- [ ] **Android** OAuth client: package `app.kadans`, plus the SHA-1 of your debug keystore
      (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`)
      and later the release keystore's SHA-1
- [ ] **Web application** OAuth client: Android's Credential Manager wants it as `serverClientId`,
      and the ID tokens it returns carry *this* id as audience → it must also go into
      `ExternalAuth:Google:ClientIds`
- [ ] **iOS** OAuth client: bundle id `app.kadans`
- Consent screen: keep it in **Testing** mode with your Google account as a test user — no domain,
      homepage or privacy links required. At public launch: switch to Production and add
      `kadans.app` as an authorized domain plus homepage/privacy-policy URLs (verification needs them).
      Testing mode's 7-day limit applies to Google refresh tokens, which Kadans never uses — sign-in
      consumes fresh ID tokens only.
- The API itself never needs any Google client secret — it only verifies ID-token audiences.

## Sign in with Apple

- [ ] Apple Developer account; enable "Sign in with Apple" on the App ID (iOS/macOS bundle id)
- [ ] For non-Apple platforms (Android, desktop, web) a Services ID
- [ ] Bundle id and/or Services ID → `ExternalAuth:Apple:ClientIds`
- No `.p8` key / client secret is needed for ID-token verification.

## Push notifications – Firebase Cloud Messaging

- [ ] Firebase project (can be the same Google Cloud project); add the Android and iOS apps
- [ ] Project settings → Service accounts → generate a private key (JSON) →
      `Push:Firebase:CredentialsJson` (the whole JSON as one secret value) **or** a file path in
      `Push:Firebase:CredentialsFile`
- [ ] `Push:Provider` = `Fcm` (dev defaults to `Log`)
- [ ] iOS: upload the APNs key (.p8) in Firebase → Cloud Messaging so FCM can reach iPhones
- Desktop clients do not use push: they hold the SignalR connection (`/hubs/kadans`).

## Domain and hosting (later)

- [ ] Domain (e.g. `kadans.app`) – used by `Email:LinkBaseUrl`, `Email:From`, deep links
- [ ] Production Postgres and a host for the API; `ConnectionStrings:kadans`, `Jwt:Key` (≥ 32 random chars)
- [ ] Rotate `InitialAdmin:Password` after first login, or disable seeding (`InitialAdmin:Enabled=false`)

## Client (Compose Multiplatform)

- [ ] Google Play / App Store developer accounts when it is time to ship
- [ ] Deep links (App Links / Universal Links) for the three emailed URLs
- [ ] Register the device on every app start: `PUT /users/me/devices/{installationId}` with the FCM token
