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
- [x] Verify a sending domain in Resend: `kadansplanning.com` (done). `Email:From` defaults to
      `Kadans <no-reply@kadansplanning.com>` in `appsettings.json`; production sets `EMAIL_FROM`.
- [ ] To send real mail from dev: `Email:Provider` = `Resend` (dev defaults to `Log`, which prints the
      email – including its link – to the API log)
- [ ] `Email:LinkBaseUrl` = the public URL the emailed links should open (API URL until the client
      handles deep links: `/auth/confirm-email`, `/auth/reset-password`, `/users/me/email/confirm`)

## Google Sign-In

The code is done on both sides (Phase 8); it lights up the moment these values exist. The clients
ask `GET /auth/providers` and show **Continue with Google** only when the server publishes the id
their platform needs – so nothing to rebuild or configure in the apps.

- [x] One Google Cloud project for everything: the Firebase project **`kadans-420a7`** (every Firebase
      project is a Google Cloud project). The earlier standalone project `kadans-507716` was deleted
      on purpose; the Desktop OAuth client registered from it as `ExternalAuth:Google:ClientIds:0`
      died with it.
- [ ] In `kadans-420a7`: APIs & Services → OAuth consent screen: **Testing** mode, your Google account
      as test user — no domain, homepage or privacy links required.
- [x] (2026-09-18, dev) Credentials → **Desktop app** OAuth client → the JVM app's loopback sign-in.
      Verified: `/auth/providers` publishes the id and Google answers a fake code with `invalid_grant`
      (it would say `invalid_client` for a wrong id/secret pair). Left to do: sign in once for real. The app only gets
      the id; the API does the code exchange, so the secret stays on the server:
      `ExternalAuth:Google:Desktop:ClientId` and `ExternalAuth:Google:Desktop:ClientSecret`
- [ ] Credentials → **Web application** OAuth client (no redirect URIs needed) → Android's Credential
      Manager uses it as `serverClientId`, and Android ID tokens carry it as audience:
      `ExternalAuth:Google:WebClientId`
- [ ] Credentials → **Android** OAuth client: package `app.kadans` + the SHA-1 of your debug keystore
      (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`),
      later a second one with the release keystore's SHA-1. Its id goes nowhere in our config – it only
      has to exist in the same project, it is how Google recognises the app. You do **not** need release
      signing first: the debug keystore exists as soon as Android Studio or Gradle has built once, and
      debug builds are what you test with. When the app goes through Google Play, add a third Android
      client with the *Play App Signing* SHA-1 from the Play Console – Play re-signs the app, so the
      release keystore's fingerprint is not the one on users' phones.
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
- At public launch: switch the consent screen to Production and add `kadansplanning.com` as an authorized
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

## Domain and hosting

The code side is done; the step-by-step is in [DEPLOYMENT.md](DEPLOYMENT.md). What only you can do:

- [x] Buy the domain: **`kadansplanning.com`**. The backend is **`https://api.kadansplanning.com`** (what
      the apps and emailed links use); the bare domain stays free for a website later.
- [x] Resend → Domains → `kadansplanning.com` verified. Still to do: a production API key as
      `RESEND_API_KEY` in `deploy/.env` (`EMAIL_FROM` is already filled in).
- [x] A server, with DNS `api` → its address.
- [ ] Cloudflare → DNS: the `api` record must be **DNS only** (grey cloud), not proxied – otherwise certbot
      cannot get the certificate (DEPLOYMENT.md → DNS on Cloudflare).
- [ ] On the server: `deploy/.env` – it is not in the repository; step 2 of DEPLOYMENT.md → First
      deployment creates it from `.env.example` with generated `POSTGRES_PASSWORD` and `JWT_KEY` – then
      `RESEND_API_KEY` and `GOOGLE_DESKTOP_CLIENT_SECRET` in it, `deploy/secrets/firebase-admin.json` (the
      same service-account key as `~/.kadans/firebase-admin.json`, copied there before the first start,
      readable by the container: `sudo chown "$USER":1654 … && chmod 640 …`), then
      `docker compose up -d --build`.
- [ ] First start with `INITIAL_ADMIN_ENABLED=true`, sign in, enable two-factor, set it back to `false`.
- [ ] Google Cloud → OAuth consent screen: add `<domain>` as an authorized domain when you leave Testing
      mode. No redirect URIs are needed for any of the three clients.
- [ ] An uptime monitor on `https://api.<domain>/health/ready`, and an off-server copy of `deploy/backups/`.
- [ ] Build the apps for production: `-Pkadans.apiBaseUrl=https://api.<domain>` (DEPLOYMENT.md → Building the apps).

## Monitoring

- [ ] On the server, the three new lines in `deploy/.env` (DEPLOYMENT → Monitoring): `GRAFANA_ADMIN_PASSWORD`
      (generated), `ALERT_EMAIL` (where alerts go), `ALERT_FROM_ADDRESS` (on the Resend-verified domain); then
      `docker compose up -d`.
- [ ] Through the SSH tunnel: Grafana → Alerting → Contact points → owner → Test, and check the email arrives.
- [ ] The privacy policy: logs (30 days) hold user ids and what happened, never addresses, names or contents;
      metrics (30 days) are counts and durations only.

## Subscriptions – Google Play and App Store

The mobile apps are free to download and need a subscription (USD 0.99 a month after a 14-day free trial);
the desktop app is free (ROADMAP → Phase 8 → Subscriptions). The server side is built and waits, switched off
(DEPLOYMENT → Subscriptions); the accounts take days to approve, so they can start now. Play Console only lets you
create the subscription once a build with billing is uploaded to a testing track: the Android app step brings it.

Google Play (sells in Haiti, to Haitian buyers and from a Haitian seller account, paid out in USD):
- [ ] Play Console developer account, then a payments profile (merchant account) with tax and bank details.
- [ ] Create the app (package `app.kadans`) with Play App Signing, make your upload key, and upload the signed
      bundle to the **internal testing** track: DEPLOYMENT → Building the apps (the key, its four Gradle
      properties, then `./gradlew :androidApp:bundleRelease -Pkadans.apiBaseUrl=https://api.kadansplanning.com`).
      It contains Play Billing, which is what unlocks Monetize → Subscriptions.
- [ ] Monetize → Subscriptions: product id **`kadans_mobile`**, a monthly auto-renewing base plan at USD 0.99
      (let Google set the other countries' prices), and an offer with id **`trial-14d`**: a 14-day free trial
      for new subscribers. Those ids are the server's defaults (`Billing:Google:ProductId`, `TrialOfferId`).
- [ ] Real-time developer notifications: a Pub/Sub topic in `kadans-420a7`, publish rights for
      `google-play-developer-notifications@system.gserviceaccount.com`, then a **push** subscription to
      `https://api.kadansplanning.com/billing/google/notifications` with **authentication enabled**: a service
      account of your choice (its email goes in `PLAY_NOTIFICATIONS_SERVICE_ACCOUNT`) and that same URL as the
      audience. Play Console → Monetization setup: the topic's name, then "Send test notification".
- [ ] A service account for the Play Developer API (Google Cloud → IAM → Service accounts → create, then a JSON
      key), invited in Play Console → Users and permissions with "View financial data" and "Manage orders and
      subscriptions". Its key goes to the server as `deploy/secrets/play-developer-api.json` (DEPLOYMENT →
      Subscriptions).
- [ ] Setup → License testing: your Google accounts, to buy test subscriptions without paying (Google's test
      cards; a test trial lasts 3 minutes and a test month 5, ending after 6 renewals). Once a build with billing
      is on any track, a license tester's phone may also run a build installed with adb, debug ones included: the
      package name is what Google checks.
- [ ] Then, on the server, `BILLING_REQUIRED=true` (DEPLOYMENT → Subscriptions) and a test purchase: the paywall
      shows the price and the 14-day trial, the trial opens Home, and Settings shows its end date.
- [ ] Production access. A personal developer account created after 13 November 2023 must first run a **closed
      test with at least 12 testers opted in for 14 days in a row** (organization accounts are exempt, but they
      need a company and a D-U-N-S number). The testers never pay: while `BILLING_REQUIRED=false` nobody sees a
      paywall; once it is on, each one signs up and you add them with `tools/admin/free_accounts.py add <them>`
      (DEPLOYMENT → Subscriptions → Free accounts), then they open the app again. License testing is no use for
      them: their test subscriptions end within the hour.
- [ ] Play Console → App content → App access: an account for Google's reviewers (username and password), listed
      as a free account (`tools/admin/free_accounts.py add <it>`) so they see the whole app. Without two-factor:
      reviewers cannot get the codes.

Apple (no App Store in Haiti; the iPhone subscription is sold in the storefronts where Apple is):
- [ ] Apple Developer Program membership (USD 99 a year). Check first that you can enroll and be paid from
      where you are, or through a company and bank account in a country Apple pays to.
- [ ] App Store Connect → Agreements, Tax and Banking: the Paid Applications agreement.
- [ ] A subscription group with one monthly product at USD 0.99, a 14-day free-trial introductory offer, and
      the storefronts it is sold in.
- [ ] App Store Server Notifications (version 2): the API's Apple notification URL, production and sandbox.
- [ ] Users and Access → Integrations → In-App Purchase key; the key, its id and the issuer id go to the
      server as secrets.
- [ ] Sandbox testers, to buy test subscriptions without paying.

Both:
- [ ] Terms of Use (with the subscription terms) and a Privacy Policy at **`https://kadansplanning.com/terms`**
      and **`https://kadansplanning.com/privacy`**: the paywall already links there (`config/LegalLinks.kt`;
      change both together if they live elsewhere), and both store listings need them too. The privacy policy states what is kept and for how long: the table in
      ARCHITECTURE → Data retention, including the 14 days of backups.

## Client (Compose Multiplatform)

- [ ] Google Play / App Store developer accounts when it is time to ship
- [ ] Play Console → App content → Data safety → "Delete account URL": `https://api.kadansplanning.com/account/delete`
      (the web route Google Play requires; the app has its own in Settings). The form also asks how long deletion
      takes: closed at once, erased after 7 days, out of the backups 14 days after that.
- [x] Deep links: `kadans://auth/...` custom scheme handled on Android; the email landing pages
      offer the app link. Verified https App Links / Universal Links wait for the domain.
- [x] Device registration on every sign-in: `PUT /users/me/devices/{installationId}` with the
      FCM token on Android (null elsewhere)
