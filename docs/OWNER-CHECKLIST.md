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
- [x] `Email:LinkBaseUrl` = the public URL the emailed links should open (API URL until the client
      handles deep links: `/auth/confirm-email`, `/auth/reset-password`, `/users/me/email/confirm`). Done:
      `https://api.kadansplanning.com` in `appsettings.json`; production takes it from `KADANS_DOMAIN`
      (`deploy/docker-compose.yml`) and refuses to start without it.
- [x] A DMARC record, the one piece of the sending domain that was missing: it tells mail providers what to do with
      a message that claims to come from `kadansplanning.com` and does not, and they trust a domain that has one
      more. Cloudflare → DNS → a `TXT` record named `_dmarc`: `v=DMARC1; p=none;`. Done 2026-11-12, seen from
      outside.
- [ ] Tighten it to `v=DMARC1; p=quarantine;` (a forged message then goes to spam) once Kadans' emails have kept
      arriving for a week, so from 2026-11-19. Before that, decide how support mail is answered: a reply sent as
      `support@kadansplanning.com` through Gmail's own servers would fail the check and land in spam; one from your
      own address, or sent through Resend, would not.
- Not a task: to send real mail from dev, `Email:Provider` = `Resend` in the user secrets. Dev stays on `Log`, which
  prints the email – including its link – to the API log.

## Google Sign-In

The code is done on both sides (Phase 8); it lights up the moment these values exist. The clients
ask `GET /auth/providers` and show **Continue with Google** only when the server publishes the id
their platform needs – so nothing to rebuild or configure in the apps.

- [x] One Google Cloud project for everything: the Firebase project **`kadans-420a7`** (every Firebase
      project is a Google Cloud project). The earlier standalone project `kadans-507716` was deleted
      on purpose; the Desktop OAuth client registered from it as `ExternalAuth:Google:ClientIds:0`
      died with it.
- [x] In `kadans-420a7`: APIs & Services → OAuth consent screen: **Testing** mode, your Google account
      as test user — no domain, homepage or privacy links required. (Done: Google asks for it before it
      creates any client, and the clients below exist.)
- [x] (2026-09-18, dev) Credentials → **Desktop app** OAuth client → the JVM app's loopback sign-in.
      Verified: `/auth/providers` publishes the id and Google answers a fake code with `invalid_grant`
      (it would say `invalid_client` for a wrong id/secret pair). Left to do: sign in once for real. The app only gets
      the id; the API does the code exchange, so the secret stays on the server:
      `ExternalAuth:Google:Desktop:ClientId` and `ExternalAuth:Google:Desktop:ClientSecret`
- [x] (2026-10-21) Credentials → **Web application** OAuth client (no redirect URIs needed) → Android's
      Credential Manager uses it as `serverClientId`, and Android ID tokens carry it as audience:
      `ExternalAuth:Google:WebClientId`. Verified: production's `/auth/providers` publishes it, and Google
      knows it in `kadans-420a7` (its token endpoint asks for this client's secret, where an unknown id gets
      "The OAuth client was not found").
- [x] (2026-10-21) Credentials → **Android** OAuth client: package `app.kadans` + the SHA-1 of your debug
      keystore, `AE:D8:20:4D:3E:AC:5D:75:43:8F:E5:F2:C0:E8:DA:E3:0C:80:18:D6`
      (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`),
      later a second one with the release keystore's SHA-1. Its id goes nowhere in our config – it only
      has to exist in the same project, it is how Google recognises the app. Nothing outside Google Cloud
      shows it (`google-services.json` lists no sign-in client, and the app does not need it to), so a
      **Continue with Google** on the phone is its check – passed 2026-10-26 on your phone, with the debug
      build. When the package or the SHA-1 does not match, sign-in fails: the app shows Google's error ("Developer
      console is not set up correctly") or "Google sign-in did not complete", as Play's copy would before its client
      existed (Google answers "cancelled", the same as closing its sheet). You do **not** need release
      signing first: the debug keystore exists as soon as Android Studio or Gradle has built once, and
      debug builds are what you test with. When the app goes through Google Play, add a third Android
      client with the *Play App Signing* SHA-1 from the Play Console – Play re-signs the app, so the
      release keystore's fingerprint is not the one on users' phones. Done 2026-11-08 (Protected with Play → Play
      Store protection → Manage Play app signing → App signing key).
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
      applies to Google refresh tokens, which Kadans never uses — sign-in consumes fresh ID tokens only. Testers need
      not be listed as test users either: Google exempts a client asking only for email, profile and openid, which
      is all Kadans asks. In Google Cloud → APIs & Services → OAuth consent screen (Google Auth Platform): Branding
      for the domain and the URLs, Audience → Publish app.
- To test: desktop → **Continue with Google** opens the browser and the app signs in when you come back;
      Android: a phone with your Google account (Testing on a real Android phone, below); an emulator needs
      a Google account added in its settings (and a Play Store system image).

## Sign in with Apple

- [ ] Apple Developer account; enable "Sign in with Apple" on the App ID (iOS/macOS bundle id)
- [ ] For non-Apple platforms (Android, desktop, web) a Services ID
- [ ] Bundle id and/or Services ID → `ExternalAuth:Apple:ClientIds`
- No `.p8` key / client secret is needed for ID-token verification.

## Push notifications – Firebase Cloud Messaging

- [x] Firebase project `kadans-420a7`; Android app registered (package `app.kadans`).
      Its `google-services.json` lives at `clients/app/androidApp/google-services.json`
      (gitignored — the Android build works without it, push just stays off)
- [x] Project settings → Service accounts → generate a private key (JSON) →
      `Push:Firebase:CredentialsJson` (the whole JSON as one secret value) **or** a file path in
      `Push:Firebase:CredentialsFile`. Done: `~/.kadans/firebase-admin.json` in dev, the same key at
      `deploy/secrets/firebase-admin.json` on the server (Domain and hosting).
- [x] `Push:Provider` = `Fcm` (dev defaults to `Log`): in dev user-secrets; production's default
      (`PUSH_PROVIDER` in `deploy/.env`).
- [ ] iOS (deferred — needs a Mac + paid Apple Developer account): register the iOS app
      (bundle `app.kadans`), add `GoogleService-Info.plist`, and upload the APNs key (.p8)
      in Firebase → Cloud Messaging so FCM can reach iPhones
- Desktop clients do not use push: they hold the SignalR connection (`/hubs/kadans`).

## Testing on a real Android phone

Against the server: nothing runs on your computer, and the phone works on any network.

1. The server runs the code to test: when `main` has server changes since the last deploy, update it first
   (DEPLOYMENT → Updating).
2. Build the app for the server and install it, the phone on USB with "USB debugging" on (Settings →
   Developer options):

   ```bash
   cd clients/app && ./gradlew :androidApp:assembleDebug -Pkadans.apiBaseUrl=https://api.kadansplanning.com
   ~/Android/Sdk/platform-tools/adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
   ```

   Or copy the APK to the phone and open it (allow "install unknown apps"). A debug build, because it is
   signed with the debug key the Android OAuth client names; a release build needs the upload key
   (DEPLOYMENT → Building the apps). Built for the server, it has no ⚙ server line on the Login screen: it
   talks to that server only.
3. Sign in (password, or **Continue with Google**: the Android OAuth client's check), allow notifications
   (Android 13+) and "Alarms & reminders". Signing in registers the phone's FCM token (Settings → devices
   shows it as e.g. "Google Pixel").
4. Start a hands-free session with 1-minute phases and lock the phone: each phase change should arrive as a
   real notification, even with the app closed — the server does the counting.

What you create lands in the production database, under your account. If nothing arrives: on the server,
`cd kadans/deploy && docker compose logs api | grep FCM` shows a `FCM: 1 sent` line per push.

To try a server change before it is deployed: phone and computer on the same Wi-Fi, the API listening on all
interfaces (`ASPNETCORE_URLS=http://0.0.0.0:5199 dotnet run --project src/Kadans.Api`), a plain
`./gradlew :androidApp:assembleDebug`, and `http://<the computer's LAN IP>:5199` (`ip addr`) in the ⚙ server
line – it applies immediately, no restart.

## Domain and hosting

The code side is done; the step-by-step is in [DEPLOYMENT.md](DEPLOYMENT.md). What only you can do:

- [x] Buy the domain: **`kadansplanning.com`**. The backend is **`https://api.kadansplanning.com`** (what
      the apps and emailed links use); the bare domain is the website.
- [x] Cloudflare → DNS: `A` records for `@` and `www` → the server (148.113.254.227), **DNS only** (2026-10-31).
- [x] The website (DEPLOYMENT.md → The website): `KADANS_SITE_DOMAIN=kadansplanning.com` in `deploy/.env` on the
      server, then `git pull && docker compose up -d`. Check: `https://kadansplanning.com` opens, and
      `https://www.kadansplanning.com` goes to it. Verified 2026-11-12 from outside, both.
- [x] A mailbox for **`support@kadansplanning.com`**: the site and the privacy policy give it. Resend only sends;
      Cloudflare → Email → Email Routing (free) can forward that address to your own inbox. It adds `MX` records on
      the bare domain, which do not touch Resend's. Done 2026-11-12: a routing rule forwards it to the owner's inbox.
      Check: a message sent to it from another address arrives (none had been received that day).
- [x] Resend → Domains → `kadansplanning.com` verified, and a production API key as `RESEND_API_KEY` in
      `deploy/.env` (`EMAIL_FROM` is filled in). Seen 2026-11-12: the records Resend asks for are in the DNS
      (`resend._domainkey`, `send`), and the deploy of that day ran, which compose refuses to do without the key.
- [x] A server, with DNS `api` → its address.
- [x] Cloudflare → DNS: the `api` record must be **DNS only** (grey cloud), not proxied – otherwise certbot
      cannot get the certificate (DEPLOYMENT.md → DNS on Cloudflare). Verified 2026-10-21: the name resolves
      to the server itself (148.113.254.227, not Cloudflare) and serves a Let's Encrypt certificate.
- [x] On the server: `deploy/.env` – it is not in the repository; step 2 of DEPLOYMENT.md → First
      deployment creates it from `.env.example` with generated `POSTGRES_PASSWORD` and `JWT_KEY` – then
      `RESEND_API_KEY` and `GOOGLE_DESKTOP_CLIENT_SECRET` in it, `deploy/secrets/firebase-admin.json` (the
      same service-account key as `~/.kadans/firebase-admin.json`, copied there before the first start,
      readable by the container: `sudo chown "$USER":1654 … && chmod 640 …`), then
      `docker compose up -d --build`. Verified 2026-10-21 from outside: `/health/ready` answers Healthy, and
      `/auth/providers` publishes the Google Desktop id, which the server does only with its secret set too.
- [ ] First start with `INITIAL_ADMIN_ENABLED=true`, sign in, enable two-factor, set it back to `false`.
- [ ] Google Cloud → OAuth consent screen: add `<domain>` as an authorized domain when you leave Testing
      mode. No redirect URIs are needed for any of the three clients.
- [ ] An uptime monitor on `https://api.<domain>/health/ready`, and an off-server copy of `deploy/backups/`.
- [ ] Build the apps for production: `-Pkadans.apiBaseUrl=https://api.<domain>` (DEPLOYMENT.md → Building the apps).

## Google Search Console

Where Google says what it has indexed of the website, and why not the rest (DEPLOYMENT.md → The website → Search
engines, which also lists the entries that are meant).

- [x] Deploy the change of 2026-11-12 (the English pages were reported as duplicates of `/`, `/privacy`, …): the
      command under DEPLOYMENT.md → The website, which makes nginx again. Check: `https://kadansplanning.com/en`
      (no slash) goes to `/en/`. Verified 2026-11-12 from outside: it does, `/` and `/privacy` answer 301, the pages
      and the sitemap name the English page as `x-default`, and the API answers `X-Robots-Tag: noindex`.
- [x] Sitemaps → submit `https://kadansplanning.com/sitemap.xml` again (2026-11-12).
- [x] URL inspection → each of the six English addresses (`https://kadansplanning.com/en/`, `/en/download`,
      `/en/support`, `/en/delete-account`, `/en/privacy`, `/en/terms`) → **Request indexing** (2026-11-12).
- [x] Pages → "Duplicate, Google chose different canonical than user" → **Validate fix** (started 2026-11-12).
      Google takes from a few days to a few weeks: the six pages move to Indexed, and the addresses without a
      language to "Page with redirect". Look again around the end of November.
- Leave "Page with redirect" and "Blocked by robots.txt" as they are: both are meant, and a validation there fails.
- [ ] Deploy the change of 2026-11-20 (the site's name for Google, link previews): `cd kadans && git pull` on the
      server, pages only, nginx stays as it is. Check: `curl -s https://kadansplanning.com/en/ | grep -c
      application/ld+json` prints 1.
- [ ] URL inspection → `https://kadansplanning.com/en/`, `/fr/` and `/ht/` → **Request indexing** (their site name).
- [ ] Pages → "Duplicate, Google chose different canonical than user": the export of 2026-11-20 (its data stops at
      11-15, three days after the deploy of 11-12) still lists 3 pages, with the validation "Not started". Open the
      entry and inspect each address it lists: "Google-selected canonical" should be an address without a language
      (`/`, `/privacy`, …), Google's view from before 11-12. Then **Validate fix** again. Any other kind of address
      there is something new: bring it back with what the inspection says.
- [x] Links to the site from elsewhere, the first Google follows: the GitHub repository's website is
      `https://kadansplanning.com` (set 2026-11-20).
- [ ] Play Console → Store settings → Store listing contact details → Website, `https://kadansplanning.com` if it is
      empty (shown once the listing is public).
- What to expect: a new site that nothing links to shows first for its own address (`kadansplanning.com`;
  `site:kadansplanning.com` lists what Google holds), then for its name within weeks of the first links
  (Search Console → Performance counts each time it appears). "kadans planning" rests on the domain and the links (the
  pages say Kadans), and "Kadans" alone, a common word, comes last.

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
- [x] Play Console developer account, then a payments profile (merchant account) with tax and bank details.
      The app itself stays **Free** (Monetize with Play → Products → App pricing): "Paid" means paying to download,
      and the subscription is sold inside a free app.
- [x] (2026-11-07, version code 1) Create the app (package `app.kadans`) with Play App Signing, make your upload key, and upload the signed
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
- [ ] Read and approve the Terms of Use (with the subscription terms) and the Privacy Policy, drafted 2026-10-31 in
      `site/legal/` in the three languages, served at **`https://kadansplanning.com/terms`** and
      **`https://kadansplanning.com/privacy`** (the paywall links there: `config/LegalLinks.kt`), and give both
      addresses to the store listings. To decide or add: your name or company as the one responsible (the drafts say
      "Kadans"), the country of the server if you want it stated, and the governing law (the drafts say Haiti's).
      The privacy policy states what is kept and for how long: the table in ARCHITECTURE → Data retention, including
      the 14 days of backups; change both together. Have a native speaker read the Kreyòl.

## Spanish (2026-11-23)

- [ ] Have a Spanish speaker read it before the version that carries it is released: one of the testers who asked is
      the right person. In the app (Settings → Idioma → Español): the sign-in screen, Home, a todo and its repeat
      rule, a focus session, the budget, Settings. On the web: `https://kadansplanning.com/es/`, its privacy policy
      and its terms. It is Latin American Spanish with "tú"; the words chosen are at the head of
      `clients/app/shared/src/commonMain/kotlin/app/kadans/i18n/Es.kt` ("tarea", "repetición", "enfoque",
      "descanso", "monto", "estado de cuenta"). Send me what reads wrong, with the screen it is on.
- [ ] Deploy: the website's pages and nginx both changed (the `/es/…` addresses), so after `git pull` nginx is
      created again (DEPLOYMENT → The website; a few seconds without the site, the API does not restart). Check:
      `curl -sI https://kadansplanning.com/es/ | head -1` says 200, and
      `curl -sI -H 'Accept-Language: es' https://kadansplanning.com/ | grep -i location` points at `/es/`.
- [ ] The website and the store texts now say "English, French, Kreyòl and Spanish", and the support page that you
      can be written to in Spanish. The apps people have today (1.2.0) do not speak it yet: either release the next
      version soon after the deploy, or tell me to hold those sentences back until then. If you would rather not
      answer support in Spanish, tell me and the page says three languages again.
- [ ] Search Console → URL inspection → `https://kadansplanning.com/es/` → **Request indexing**.
- [ ] Play Console, once the version with Spanish is on the track (not before: the listing would promise a language
      the app does not have): Grow → Store presence → Main store listing → Manage translations → add **Spanish
      (Latin America) – es-419**, with the title, the short and the full description of `store/play/es-419/`; and
      paste the English and French full descriptions again (their last lines name four languages). No Spanish
      screenshots yet: Play shows the English ones. That release's notes need a Spanish "What's new" too.

## Client (Compose Multiplatform)

- [x] The first desktop release, v1.0.0 (2026-11-01): `https://kadansplanning.com/download`'s buttons work.
- [x] v1.1.0, the first in the stores (2026-11-05): its release brings the new navigation, dates, clock and dark mode
      to the site's installers, announces them to the installed 1.0.0 apps, and carries the Microsoft Store's `.msix`.
- [x] v1.1.1: the desktop on a company network (its proxy, and the certificate authorities Windows trusts) and a Google
      button that no longer goes missing. Not before the Windows build connects on the company PC (`Kadans
      --network-check`, ARCHITECTURE → "The desktop app: in the tray, once"). Then `git tag v1.1.1 && git push origin v1.1.1`, then a new Partner Center
      submission with that release's `kadans-microsoft-store-x64.msix`. Android takes it with its next upload.
      Tagged and released 2026-11-12 (GitHub's release `v1.1.1`); its `.msix` went to Partner Center the same day,
      and Microsoft certified it: the Store's copy works (2026-11-18).
- [ ] v1.2.0 (prepared 2026-11-17): November's six integrations (the calendar link, the home-screen widget, Do Not
      Disturb during focus, Share to Kadans, the phone's calendar, statements in and data out) and the sealed session.
      Test it before tagging, in this order:
      1. **The server first**: deploy `main` (DEPLOYMENT → Updating). A 1.2.0 app asks for what an older server does
         not have, and shows errors that are not bugs.
      2. **Linux**: the `.deb` the release workflow built for the version's pull request (GitHub → Actions → Release →
         that run → Artifacts → `linux`; `gh run download <run id> -n linux` from a terminal), installed over the
         1.1.1 you use (`sudo apt install ./kadans-linux-amd64.deb`). It must open signed in, without asking. Do not
         start a build from source before that (`./gradlew :desktopApp:run`): it shares the installed app's settings
         and would seal the session, and the installed 1.1.1 would then find itself signed out.
      3. **Android**: a bundle with `-Pkadans.versionCode=2` (DEPLOYMENT → Building the apps), uploaded to the
         **internal testing** track with `store/play/<language>/changelogs/2.txt` as its release notes, then updated
         from Play on your phone: it must open signed in. Not with adb: a build signed with another key does not
         install over Play's copy, and uninstalling first wipes the very session the update must carry over.
      4. **Satisfied**: `git tag v1.2.0 && git push origin v1.2.0` (DEPLOYMENT → Releasing the desktop app), a new
         Partner Center submission with that release's `.msix`, the same Play bundle promoted to the closed test, and
         both stores' descriptions updated from `store/play/<language>/full_description.txt`.
      The stores' screenshots were retaken for 1.2 (2026-11-18): upload them with the descriptions, Play's eight per
      language from `store/play/<language>/images/phoneScreenshots/` (the widget on a home screen is new; Settings
      gave it its place) and the Microsoft Store's seven from `store/microsoft/<language>/screenshots/`. If a test
      changes a screen that is in them, that picture is taken again.
- [ ] Microsoft Store (Partner Center, an "MSIX or PWA app" named Kadans: identity `AndersonRuban.Kadans`, Store ID
      9NPHCHB96T03, already in `clients/app/desktopApp/msix/AppxManifest.xml`). A submission's Packages page takes
      `kadans-microsoft-store-x64.msix` from the GitHub Release (DEPLOYMENT → Releasing the desktop app); before the
      first release, from the release workflow's run on a pull request (Actions → the run → Artifacts → `windows`).
      Partner Center asks why the package needs `runFullTrust`: "Kadans is a desktop app (Java, Compose) packaged as
      MSIX; it runs its own desktop executable." The package declares English and French: give the Store listing
      both (the texts of `store/play/en-US` and `fr-FR` fit, and the desktop screenshots are in
      `store/microsoft/<language>/screenshots/`; Store logos → 1:1 App tile icon is `store/microsoft/app-tile-icon.png`,
      the same for both: store/microsoft/README.md). The Store installs and updates it, signed by Microsoft,
      so Windows shows no unknown-publisher warning; the `.msi` from the site still does, until it is signed with a
      code-signing certificate (optional).
- [ ] Google Play / App Store developer accounts when it is time to ship
- [ ] Play Console → Grow → Store presence → Main store listing: the texts are in `store/play/<language>/` (English
      default, French under "Manage translations", Kreyòl when Play offers it: store/play/README.md), the app icon
      is `branding/play-store-icon.png` (512 x 512, the logo: branding/README.md) and the feature graphic
      `branding/play-feature-graphic.png` (1024 x 500, no text: Play writes the name beside it). Phone screenshots:
      `store/play/en-US/images/phoneScreenshots/` for the main listing and `store/play/fr-FR/images/phoneScreenshots/`
      for the French translation, eight each, uploaded in their order (a language without its own set shows the
      English one). To take a set again (two to eight): register a demo account per language in the app, fill it
      with
      `python3 tools/demo/seed_demo.py <username> --language en|fr|ht` (it refuses an account with data), take the
      screenshots on the phone within a few hours (today's todos are on Home), then
      `python3 tools/store/play.py crop <files>` (Play refuses taller than 2:1; a 1080 x 2340 capture is). Run the
      script on `googleTest` too: Google's reviewers then find a lived-in account.
- [ ] Play Console → App content → Data safety → "Delete account URL": `https://kadansplanning.com/delete-account`
      (the site's page: both ways, in the app and on the web, in three languages, with the link to the web form
      `https://api.kadansplanning.com/account/delete`). The form also asks how long deletion takes: closed at once,
      erased after 7 days, out of the backups 14 days after that.
- [ ] The session sealed on the device (2026-11-17), with the next builds, each installed over the one you use and
      never after a sign-out: the phone (from Play's test track), the computer (the `.deb`), Windows (the `.msi` or
      the Store's). Each should open signed in, without asking anything: the session the old version kept in the
      clear is sealed at that first start. On Linux, "Passwords and Keys" (Seahorse) then shows an item named
      "Kadans" in the Login keyring: leave it there, it is the key that opens your session (deleting it only signs
      you out). On the phone, Settings → System → Backup no longer lists Kadans' data: a new phone starts at the
      sign-in screen, by design. Nothing to change in Play Console's Data safety form.
- [ ] Statements in, data out (2026-11-16), with the next builds: on your computer or your phone, Budget → "Import a
      statement" with a file downloaded from a bank's site (CSV, OFX, QFX or CAMT.053; a PDF is answered with what to
      ask the bank for instead, and a Haitian bank's statement is usually one: an account abroad, or any export you can
      get as CSV, is the test). Check the columns screen read the dates and the amounts right, import, then import the same
      file again: every line should be "Already in this account". Then Settings → "My data" → "Save a copy of my
      data", and open the zip: `budget/movements.csv` in Excel (accents, amounts), `todos/todos.ics` in a calendar.
      Play Console → App content → Data safety: nothing new for the file (it is read on the phone and never sent).
      The lines kept are budget movements like those typed by hand; look that the form declares the budget
      ("Financial info" → "Other financial info": collected, not shared, for the app's own working) and tick it if it
      does not. The export is the "copy of your data" the privacy policy now points to.
- [ ] The phone's calendar (2026-11-16), with the next Android build on your phone: Calendar tab → "Show this phone's
      calendar here?" → Turn on → Allow. Your meetings should show on Home and in the Calendar tab, outlined, with their
      calendar's colour; a tap opens one in your calendar app. If your todos are in Google Calendar through the feed,
      none should show twice. Tell me what a Samsung or a work calendar looks like: only a test calendar on the
      emulator was checked.
      Play Console, when you upload that build: the app now asks for the calendar permission. App content → Data
      safety stays as it is (events are read on the phone and never sent: that is not "collected"); if the upload or a
      review asks why the app reads the calendar, the answer is "to show the person's own events beside their tasks, on
      the device only", and the privacy policy's "What stays on your phone" says it.
- [ ] Share to Kadans (2026-11-16), with the next Android build on your phone: in Chrome, ⋮ → Share → Kadans ("New
      todo"); the form opens with the page's title and its link, and "Create" brings you back to Chrome. The same from
      a WhatsApp message (press it, Share). Select a text in any app and look for "Add to Kadans" in the menu that
      appears (sometimes under ⋮). Then open the todo: the link in its description opens the page.
- [ ] Do Not Disturb during focus (2026-11-15), with the next Android build on your phone: Settings → "Do Not Disturb
      during focus" → the switch, then Android's own screens ("Do Not Disturb access"; "Alarms & reminders" if it is
      off). Start a session on the computer while the phone has been lying still for a few minutes: it should go
      quiet within seconds and say "Quiet during focus" in its notifications; at the phase's end the sound comes
      back by itself. "Kadans focus" in the phone's Do Not Disturb settings is where what rings through is changed.
      On a Samsung or a Tecno, tell me what those settings look like: only the emulator's stock Android was checked.
      If Play Console asks about the new permission (Do Not Disturb access) at upload, tell me what it asks: no
      declaration was found to be required.
- [ ] Deep links: on Android the emailed password reset is a verified App Link (DEPLOYMENT → Building the apps → App
      Links). To finish: update the server, upload version code 2, install it from Play, then tap the link in a reset
      email: Kadans' own screen opens. Universal Links wait for the iPhone app.
- [x] Device registration on every sign-in: `PUT /users/me/devices/{installationId}` with the
      FCM token on Android (null elsewhere)
