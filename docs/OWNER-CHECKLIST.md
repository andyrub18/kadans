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

- [ ] Google Cloud project → OAuth 2.0 client IDs, one per platform the app runs on
      (Android: package name + SHA-1; iOS: bundle id; desktop/web: "Web application" client)
- [ ] All of them → `ExternalAuth:Google:ClientIds` (array; user-secrets: `ExternalAuth:Google:ClientIds:0`, `:1`, …)
- No client secret is needed: the API only verifies ID tokens the client obtained natively.

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
