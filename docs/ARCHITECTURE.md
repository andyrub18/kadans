# Kadans – Architecture

## Shape: modular monolith

One deployable API, one Postgres database, hard module boundaries inside the codebase.
Not microservices, and not a four-layer "clean architecture" per module – vertical slices inside
modules are enough.

### Layout (all five modules exist)

```
src/
  Kadans.Api/                    host only: Program.cs, middleware, module registration
  Kadans.SharedKernel/           errors/ProblemDetails, OneOf helpers, ICurrentUserService,
                                 Money, audit/entity base, IModule, naming conventions,
                                 recurrence engine (shared by Tasks and Budget)
  Kadans.Modules.Identity/       users, auth, tokens, external logins, profile, devices
  Kadans.Modules.Tasks/          todos, occurrences, pomodoro
  Kadans.Modules.Budget/         accounts, categories, transactions/transfers, category limits,
                                 recurring money, monthly summary, base currency + rates
  Kadans.Modules.Notifications/  notification log, SignalR hub, push (FCM), dispatcher
  Kadans.Modules.Billing/        subscriptions as the stores report them, phone access (IMobileAccess)
tests/
  Kadans.<Module>.Tests/         TUnit unit tests (Tasks, Budget, Identity, Notifications, Billing, SharedKernel)
  Kadans.Api.IntegrationTests/   planned: TUnit + Testcontainers (real Postgres); until then
                                 tools/smoke/*.py exercise the DB paths against a running API
clients/
  app/                           Compose Multiplatform (Android, iOS, desktop JVM)
tools/
  smoke/                         end-to-end smoke scripts, one per module/feature area
```

Inside a module:

```
Kadans.Modules.Tasks/
  TasksModule.cs        IModule: AddServices(IServiceCollection, IConfiguration), MapEndpoints(IEndpointRouteBuilder)
  Domain/               entities + pure domain logic
  Features/             one folder per use case: Endpoint, Request, Validator, Handler
  Persistence/          TasksDbContext, configurations, Migrations/
  Contracts/            response DTOs – the only public surface besides the module class
```

### Rules

1. **One `DbContext` and one Postgres schema per module** (`identity`, `tasks`, `budget`,
   `notifications`). Each module owns its migrations.
2. **No cross-module foreign keys or navigation properties.** `Todo.UserId` is a plain string.
   Endpoints never return entities that could drag another module's data along.
3. **Modules depend only on SharedKernel.** They communicate through SharedKernel abstractions:
   `IUserDirectory` and `IDevicePushTargets` (implemented by Identity), `INotificationDispatcher`
   and `IRealtimePublisher` (implemented by Notifications), `ISessionEndListener` (called by Identity
   when sessions end; Notifications closes their live connections), `IUserDataEraser` (each module erases a
   user's data when Identity erases the account), `IUserDataExporter` (each module hands over what it holds for a
   person, as files, when Identity builds "export my data"), and `IMobileAccess` (implemented by Billing; Notifications asks
   it before pushing to a phone). Tasks and Budget only consume the rest.
4. **Everything is `internal`** except `Contracts` and the `IModule` implementation.
5. **Per-user isolation via EF global query filters** on `UserId == CurrentUserId`. Contexts are pooled (building one
   per request was a tenth of the API's CPU, docs/LOADTEST.md), so a context with user filters derives from
   `UserScopedDbContext` and is registered with `AddUserScopedDbContextPool`: each request's instance is handed that
   request's `ICurrentUserService` when it is rented and loses it when it goes back. A context rented any other way
   sees no one's rows, never the previous renter's. A pooled context takes nothing scoped in its constructor.
6. **Endpoints return DTOs**, never EF entities (the old code returned `Todo`/`PomodoroRun`
   with an `IdentityUser` navigation – a password-hash leak waiting for an `Include`).
7. Authorization fallback policy = authenticated user; anonymous endpoints opt out explicitly.

## Decisions

### Identity: ASP.NET Core Identity, not an external IdP

Keycloak/Zitadel/Auth0 were considered. For a personal-first, solo-developed app the cost of a
second, publicly reachable service outweighs what it provides. Everything needed (register, login,
rotating refresh tokens, change/reset password, email confirmation, Google/Apple sign-in via
native ID-token verification, TOTP MFA, device registration) is a few hundred lines on top of
`UserManager`. The seam is kept: other modules only see `ICurrentUserService` and the API validates
a bearer JWT, so swapping the Identity module for an IdP later only touches `AddJwtBearer`.
OpenIddict on top of Identity is the middle path if standard OIDC is ever needed.

Implemented (Phase 2): sessions are refresh-token *families* (one per login/device), stored as
SHA-256 hashes; refreshing rotates inside the family and replaying a rotated token revokes the whole
family. Password change/reset revokes all families. TOTP MFA is a two-step login: the password step
returns a short-lived challenge JWT with audience `<Audience>:mfa` (never accepted as a bearer token),
exchanged with a TOTP or recovery code. Turning it on, the app shows the server's `otpauth://` address as a QR code
(`ui/settings/AuthenticatorSetup`, drawn by the app from `qrcode-kotlin`'s squares, black on white in both themes, on
whole pixels), and on a phone a button that hands that address to the authenticator app there; the key stays below,
with a copy button (the owner, 2026-10-31: typing the key is not practical). External login verifies Google/Apple ID tokens obtained natively
by the client against the provider's JWKS (OIDC discovery). A login already linked signs straight in.
Otherwise only a **verified** address counts: without one, nothing is linked or created. With one, the
login is linked to a confirmed account with that address, or a new account is created. An account whose
address was never confirmed is taken over by the verified owner. Anyone can register someone else's
address and set a password, so the takeover first removes everything that unproven registrant set up:
password, 2FA and recovery codes, other logins, sessions, devices and any lockout. It runs in one
transaction with the link. 2FA still applies to external sign-in.

**A session ends everywhere at once** (Phase 8). Every access token names its session (the family id, in the
`sid` claim) and the bearer handler refuses a token whose session is over, so a sign-out cuts access on the
next request instead of when the token expires (up to 60 minutes later). The check is in memory
(`SessionRegistry`): a session is read from the database at most once a minute, and one that ends is dropped
the moment it does. That is exact only because Kadans runs as one instance; a second instance would need the
endings shared. Every way a session ends goes through `Sessions`: sign-out, sign out everywhere, a password
change or reset (by the person or an admin), deactivation, a withdrawn role (roles ride in the token), a
replayed rotated refresh token, and the takeover above. Ending one also removes the device that session
registered, so a signed-out phone gets no more reminders, and tells the other modules
(`ISessionEndListener`): the hub closes that session's live connections. A device, and its push token, belong
to one account at a time: the next account to register the same installation or token takes it. Considered
and rejected: very short access tokens (every device would refresh every few minutes, and a sign-out would still
lag), and a database read on every request (the in-memory answer is as exact with one instance). The app signs
out only when the server refuses a refresh (400, 401, 403). A server that cannot answer (a deploy restart
behind the proxy, a rate limit, no network) keeps the session and fails just that call.

**Locks, and proving it is you again** (Phase 8). Identity's lockout carries two locks (`AccountLock`).
Deactivation locks an account for good: it ends sessions, refuses Google sign-in and withholds the reset
email. Wrong passwords or codes (5) lock it for 15 minutes, answered with 429 / 10054. That lock only guards
sign-in: sessions go on, Google sign-in (which proves who it is) and the reset link still work, and the reset
lifts it. Otherwise a stranger typing wrong passwords against a username would sign its owner out
everywhere. Wrong passwords and codes count wherever they are typed: sign-in, 2FA at sign-in, changing the
password, changing the email, turning 2FA off, new recovery codes. So an open session cannot be used to try
passwords or codes at leisure. Changing the email takes the current password, because the address is where
reset links go; accounts that only use Google have none to give. Sign-in looks up anything with an `@` as an
address first. A username may hold an `@` only when it is the account's own address, and it follows that
address when it changes. Passwords need 8 characters; Identity's complexity rules stay as they are.

Every emailed link opens an anonymous page served by the API (confirm email, reset password,
confirm a new email): a person clicks it in a mail client, where no session exists, so the token in the
link is the proof – it is bound to the user and, for an email change, to the new address. After a change
the previous address receives a notice, the owner's only alarm if it was not them.
Google has one OAuth client per way of signing in: Android's Credential Manager returns an ID
token whose audience is the *Web* client id; the desktop app runs the loopback flow with PKCE and sends
the authorization code to `POST /auth/external/google/code`, where `GoogleCodeExchange` trades it for the
ID token – the one place the API talks to Google with a secret, chosen so the Desktop client's secret
never ships inside the app. `GET /auth/providers` publishes the client ids (never the secret) so clients
only offer what the server can complete. On the client the platform flows sit behind one
`GoogleSignIn` interface (`expect fun platformGoogleSignIn`), returning either an ID token or a code. Emails go through `Kadans.SharedKernel.Email.IEmailSender` (Resend in production, log in dev).

### Deployment: one container, exactly one instance

The scheduler (Quartz, RAM store), the pomodoro deadline watcher, the push queue, SignalR (no
backplane) and the rate-limit counters all live in the API process. That is deliberate – every job is an idempotent scan, so a
restart loses nothing – and it fixes the deployment shape: **one always-on instance**, never scale-to-zero,
never two replicas. Because of that, migrations run at startup in production
(`Database:MigrateOnStartup`, set by the image) and a deploy is "start the new image". The image is
host-neutral (environment variables only, port 8080 behind a TLS-terminating proxy whose forwarded
headers it trusts); `deploy/` is the reference setup for a single VPS (nginx with certbot, API, Postgres,
nightly dump). The same nginx serves the website, `https://kadansplanning.com`: plain files (`site/`), outside the
API, so a crawler never takes one of its admission places and an API update never touches the site (DEPLOYMENT →
The website). Outside Development the process refuses to start on an incomplete configuration. The only state
besides Postgres would have been ASP.NET Core's Data Protection key ring (it protects the tokens in emailed
links, and its default home is a folder inside the container, lost on every update); it lives in the
Identity schema instead (`data_protection_keys`), encrypted with AES-GCM under a key derived from `Jwt:Key`
(`KeyRingEncryption`), so it is in the backups without making a backup enough to forge a link. If Kadans ever
needs a second instance, the things to externalize are exactly that list: a persistent Quartz store,
a Redis backplane for SignalR, a real queue for push, and shared rate-limit counters.

Rate limiting is the host's (ASP.NET Core's limiter, per client address: IPv4, or the IPv6 /64). There
is a global limit, plus three named policies modules put on endpoints (`RateLimitPolicies`). **Email** covers
endpoints that mail an address the caller chooses; **Credentials** covers those that check a password or a
code; **Export** covers "export my data", a whole account in one answer, heavy to build (10 per 15 minutes). All
are token buckets, so a rejection says when to retry. The numbers are configuration
(`RateLimiting`), generous in Development for the smoke scripts. Behind them, `EmailThrottle` sends one
confirmation, reset or email-change mail per address every 2 minutes, so many senders cannot bury one
inbox either. Token refresh is only under the global limit: a refresh token has nothing to guess. Details: [DEPLOYMENT.md](DEPLOYMENT.md).

Admission control is the host's too, for the server rather than a client (`AdmissionControl`, after the rate limiter
and before authentication). The load test showed why (docs/LOADTEST.md, run 4): past about 280 requests a second on
2 vCPU, an API that accepts everything slows every request at once, until the database pool, the thread pool and the
memory run out and nothing is answered at all. So the API works on at most 32 requests at a time, below the
database pool's 40 so that an admitted request never waits for a connection. Up to 128 more wait for a place, first
come first served, for up to 2 seconds. Anything beyond is answered at once with 503, `Retry-After: 5` and error
10058 ("The server is busy. Try again in a moment.", translated). A request turned away has cost no token check and no
query, so the server keeps its full speed for the requests it took. Health checks always pass. A hub connection lives
as long as the app stays open, so it holds no place; a new one is refused while requests are waiting. The apps
already treat a 503 as passing: a refresh refused that way keeps the session, a screen shows the sentence and its
Retry, and the live connection retries with backoff. The numbers are configuration (`Admission`); every request
turned away is counted (`kadans_admission_shed_total`), and any at all raises the "Server busy" alert: the server is
at capacity.

### Errors: written once in English, worded per request

Services return `ApplicationError(ErrorType, message)` with an English message next to the code that
detected the problem – about 150 call sites, none of which knows a language. The wording a user reads
is chosen in one place, the HTTP boundary: `error.ToProblemDetails(context)` resolves the request's
language from `Accept-Language` (`RequestLanguage`: en, fr, ht or es; anything else is English) and
translates `detail` and every validation `message` through `ErrorTexts`. The header rather than the
account's `PreferredLanguage`, because register and login have no account yet and because it is what
the person is looking at right now. `errorCode` and validation `code`s never change with the language:
clients branch on codes, people read sentences. Considered and rejected: `.resx` / `IStringLocalizer`
(a missing translation silently falls back to English, and the rest of the server already uses typed
texts), and translating by code on the client (every new rule would show English until a client
release, and the parameters – "at least 6 characters" – live on the server).

`ErrorTexts` tries the exact English sentence, then one sentence per error type (for messages carrying
an id or a name), then English. ASP.NET Identity's messages carry numbers and names, so they are
translated where the parameters are: `LocalizedIdentityErrorDescriber`. Emails and notifications keep
using the account's language – there is no request to read a header from.

### Languages: four, and what one touches (Spanish added 2026-11-23)

Kadans speaks English, French, Kreyòl and Spanish: `en`, `fr`, `ht`, `es`, the same tag in the app's settings, in
`Accept-Language`, on the account and in the website's addresses; a region never changes the wording (`es-DO` and
`es-MX` are `es`). Spanish came from the closed test's testers, and it is Latin America's, since that is where its
readers are (the Dominican Republic next door, Mexico: the budget already kept their pesos): "tú", "computadora",
"monto", "estado de cuenta", "configuración", numbers written "5 000" as the amounts are. Its vocabulary is fixed at
the head of `i18n/Es.kt`: a todo is "una tarea" and one time it falls "una repetición", so what describes them is
feminine ("completada", "movida"); a focus phase is "enfoque", a break "descanso", and a session is "empezada",
never "iniciada", because "iniciar sesión" is signing in.

No language is a fallback of another, and nothing is translated at run time: every sentence is written where the
others are, and a missing one fails a build or a test. What a language touches:

| Where | What | What keeps it complete |
|-------|------|------------------------|
| App | its catalog (`i18n/Es.kt`) and its entry in `Language`: the pickers (sign-in, Settings), `Accept-Language` and the account's language follow | the compiler (a `StringsCatalog` takes every key), `StringsCatalogTests` |
| Android | `res/values-<tag>`: what the system shows in the phone's language (the widget picker, the share sheet, the kind of the quiet mode) | compared by hand with `values-fr` |
| Server | `RequestLanguage.Supported`; `ErrorTexts.Text` (a sentence for every entry); `LocalizedIdentityErrorDescriber`; `EmailTexts` and `DeletionTexts` (emails, the pages their links open); Tasks' `LocalizedTexts` (reminders, pomodoro notices); Budget's `BudgetTexts` (limit alerts) | `ErrorLocalizationTests`, `LocalizedIdentityErrorDescriberTests`; a `switch` without the language falls to English silently, so each has a test that names it |
| Website | `site/texts.py`, `site/legal/*.<tag>.html`, and nginx's language map and its two address patterns (`deploy/nginx/site-https.conf.template`) | the generator fails on a missing key or legal page; `generate.py --check` in CI |
| Stores | `store/play/<locale>/` (Spanish is `es-419`, Play's Latin American Spanish); the demo account's words for screenshots (`tools/demo/seed_demo.py`); the tags release notes may use (`tools/release/release.py`) | `tools/store/play.py check` |

A sentence is one whole phrase in each language, never words joined in code: French agrees with the unit ("Toutes
les 2 semaines"), and Spanish puts a word where the others put a space, which is why a yearly rule's date goes through
`RepeatStrings.yearDayFormat` ("15 March", "15 de marzo") instead of a day and a month side by side. Dates take their
patterns from `DateStrings` ("jueves, 5 de noviembre", "jue, 5 nov", "31 de marzo de 2027").

An older app meets a newer account without harm: a version that has no Spanish shows English for an account whose
language is `es`, and sends nothing back, so the account keeps its Spanish for the devices that have it. The server
must have the language before an app that offers it is released (a server without it would answer a change of
language with success and keep the old one): the server deploys on merge, the apps are released after.

### The account's time zone, language and clock

The server works in the account's time zone and language: reminder texts, focus-stats days, Budget months and
emails. Both come from the device, not from a form. A new account starts with the device's IANA zone and the
app's language: sign-up sends them, and so does a Google sign-in that creates the account (the server keeps a
zone its tz database knows and a language among en, fr, ht and es, otherwise UTC and English; linking leaves an
existing profile alone). After that the client's `ProfileSync` runs after every sign-in and app start:

- **Time zone**: while "Follow this device" is on (per install, on by default) the account takes the device's
  zone, so it follows the person when they travel. A device that reports only `UTC` or a raw offset is not
  followed: desktops whose zone was never set say `UTC`. Picking a zone in Settings (a searchable list of
  region/city zones with their current offset) turns following off on that install. Otherwise the next start
  would undo the choice.
- **Language**: the latest explicit choice wins. A choice made on a device is sent to the account. A fresh
  install takes the account's language rather than pushing the phone's system language, since in Haiti the
  phone is often in French while the person reads Kreyòl. An install whose choice the account already has
  follows a change made on another device. The install remembers the last language the account confirmed
  (`kadans.language.synced`), which tells "chosen here, not sent yet" from "changed elsewhere".
- **Clock** ("21:00" or "9:00 PM"): one choice per account (`ClockFormat`), so the phone, the computer and the
  server's own texts (reminders, "the session ended at …") always agree. A new account starts with the clock of
  the device it is created on (Android's 24-hour setting, the computer's region, iOS's 24-Hour Time); accounts
  from before keep 24 hours. Settings changes it on the account; every install adopts the account's choice at
  each start (`ClockPreference`, kept on the device for offline starts and for what rings away from the screens).
  12-hour times read "9:00 PM" in every language, as they are written in Haiti.

Every screen writes dates and times through one formatter, `ui/Dates` (`LocalDates`): "Today", "Tomorrow",
"Thursday, November 5" / "jeudi 5 novembre" / "jedi 5 novanm" (the year only when it is not this one), in the
device's zone, on the account's clock. Never an ISO date or a `toString()` of one.

Considered and rejected: asking for a zone in the sign-up form (people do not know their IANA id, and it goes
stale when they travel), and syncing on every request (a header the server would trust on each call).

### Tests: TUnit on Microsoft.Testing.Platform

Opt-in for `dotnet test` is `"test": { "runner": "Microsoft.Testing.Platform" }` in `global.json`.
Domain rules are unit-tested as pure code. Where a rule lives in ASP.NET Identity itself (which account an
external sign-in lands in, what a takeover removes), `tests/Kadans.Identity.Tests` runs the real `UserManager`
and token providers on an in-memory SQLite database, so CI needs no Postgres. Integration tests with
Testcontainers against real Postgres are still to come (recurrence and query filters must be tested on the
real provider); the smoke scripts in `tools/smoke/` cover those paths against a running Development API for now.

### Subscriptions: paid phones, free desktop

Decided 2026-10-02. The Android and iPhone apps are free to download and need a subscription: USD 0.99 a
month after a 14-day free trial, with the stores converting the price per country. The desktop app is free. The
`Billing` module (`billing` schema) keeps each account's store subscriptions as the stores report them.

- **Never the app's word.** The app hands over a purchase (`POST /billing/google/purchases`). The server reads
  it from Google (Play Developer API, `purchases.subscriptionsv2.get`), acknowledges it (unacknowledged
  purchases are refunded after 3 days), and keeps the state.
- **One account per purchase.** A purchase carries the account: the app sets Google's obfuscated account id to
  `accountHash`, a SHA-256 of the user id, so the store never sees the id. A purchase naming another account,
  or a token already linked to one, is refused.
- **Changes come from Google.** Renewals, failures, cancellations and refunds arrive as real-time developer
  notifications, pushed by Pub/Sub to `/billing/google/notifications`. The push must carry an OIDC token Google
  signed for that URL as the push subscription's service account. Even then the server only reads the purchase
  again from Google. An hourly job re-reads anything still counted as paid whose period ran out, in case a
  notification went missing.
- **Access.** Trial, active, grace period (the store is retrying a payment) and a cancelled subscription until
  its end count; on hold, paused, expired and refunded do not.
- **Enforced on the server where it matters.** Reminders are pushed to phones only for accounts with access
  (`IMobileAccess`, answered from memory and dropped on every change). The app's paywall could be patched out of
  an app package; the reminders are what a phone subscription buys. The desktop app and the live connection are
  unaffected.
- **The calendar feed is part of it** (2026-11-13; "The calendar feed" below): it brings the todos to the phone's
  calendar app, so without a subscription it cannot be turned on, from the free desktop app either, and a
  subscription that ends empties the calendar while keeping its link.
- **`Billing:Required`** switches all of this on, in configuration. Until the store product is live it is off:
  no paywall, every phone gets its reminders. Development has a fake store (`Billing:FakeStore:Enabled`,
  `POST /billing/fake/purchases`) for the flows without a store; the production guard refuses it.
- **Free accounts** have their phones without a store: the closed test's testers, Google's reviewers, family. An
  admin keeps the list (`billing.free_accounts`, by user id) through `/billing/free-accounts` (role `Admin`, so
  behind its two-factor), with `tools/admin/free_accounts.py`. An account is found by username or by a confirmed
  address (`IUserDirectory.FindByLoginAsync`: anyone can sign up with an address that is not theirs) and stored by
  id, which never changes, unlike a username that can be given up and taken. A change applies at once: status
  answers `hasAccess` and `freeAccess`, and `IMobileAccess` forgets its cached answer. Each entry records which
  admin added it; erasing the account removes it.
- **Erasing an account** cancels a Google subscription that is still renewing (paid time stays), since the store
  would otherwise keep billing a deleted account. Apple's cannot be cancelled by Kadans, so the emails say so.
- **Apple** comes with the iPhone app (StoreKit 2 and the App Store Server API), which needs a Mac.
- **Pending payments** (cash, bank transfer) are linked while they wait (state `Pending`, no access). When Google
  says the payment cleared, the notification re-reads it and the server acknowledges it then: the app may not
  open again within Google's 3 days.

#### In the app

`app.kadans.billing` in `clients/app/shared`.

- **`StoreBilling`** is the phone's store: the price and trial as it sells them, the purchase sheet, and the
  purchases this store account already holds. Android implements it with Play Billing 9
  (`StoreBilling.android.kt`); desktop and, until StoreKit, iOS have `NoStoreBilling`. The app never acknowledges
  a purchase: the server does once it has checked it, so a purchase that never reaches the server is refunded.
- **`SubscriptionGate`** runs before Home on phones that can sell (`HomeAccess`: Home shows a spinner meanwhile).
  It asks `GET /billing/subscription`. When the answer is no, it first hands the store's purchases to the server
  again (a new phone, a reinstall, a link that failed), then decides. An unreachable server shows no paywall:
  a network hiccup must not lock anyone out, and the pushes are gated on the server anyway. Desktop never asks.
- **The paywall** (`PaywallRoute`) shows what the stores require on the screen that sells: the price and trial as
  Google formats them in the buyer's currency (a trial only when Google still offers it to this Google account),
  how it renews and how to cancel, Terms and Privacy (`config/LegalLinks.kt`), Restore, and Manage in Google
  Play once the account holds a Google subscription. Buying names the account (`accountHash`) and sends the
  token to the server; only its answer opens Home. A pending payment waits on the paywall; "already owned" turns
  into a restore; another account's purchase shows the server's refusal (10057). Development adds a fake trial
  button when the server allows it (`fakeStore`). Sign out stays available. It says nothing about the free
  desktop app: both stores forbid steering buyers away from in-app purchase.
- **Settings** shows the subscription on phones once subscriptions are sold (or the account holds one): trial
  end, renewal date, cancelled until, or a payment problem, with Manage in Google Play.

### Account deletion: closed now, erased after 7 days

Google Play and the App Store require deleting the account in the app, and Google Play also wants a web route for
people without the app. Deleting closes the account at once and erases it with everything in it 7 days later,
unless its owner keeps it (decided 2026-10-03).

- **Asking.** In the app, the current password (wrong ones count toward the lock), or for an account without one
  (Google only) a link to its address. The web page `/account/delete` takes an address and sends the same link. It
  answers the same for every address, so it tells nobody which addresses have an account. The link's page only
  shows what will happen; its button (a POST) does it, because mail scanners open links.
- **Closed.** `AccountDeletions` records the request (`identity.account_deletions`) and ends every session, which
  removes the devices and closes the live connections. It emails the owner the date, which is also their alarm if
  it was not them. A correct sign-in during the grace period opens nothing: like the 2FA challenge, it returns
  `deletionScheduled` and a short-lived `restoreToken`, its own audience, accepted only by
  `POST /auth/restore-account`, which keeps the account and starts a session.
- **Erased.** `AccountErasureJob` (every 15 minutes) calls every module's `IUserDataEraser` (SharedKernel; Tasks,
  Notifications and Budget implement it, deleting in batches), then deletes the user, which takes sign-ins, 2FA,
  sessions and devices with it, and sends a last email. Each eraser can run again, so a run cut short is finished
  by the next.
- **Afterwards.** The deletion record stays with the id and dates only, 30 days, longer than the backups: after a
  backup restore, the erasures it predates are re-applied (DEPLOYMENT → Backups). A subscription is billed by the
  store, so the app and the emails tell the person to cancel it there.

Deleting one todo (`DELETE /todos/{id}`), unlike cancelling it, takes its occurrences (with their notes) and focus
history along, and its stats with them.

### Data retention

What Kadans keeps, and for how long. It is what the privacy policy and Google Play's data-safety form state.
Each module cleans up its own tables nightly at 07:30 UTC (about 03:30 in Port-au-Prince), and once two minutes
after a start. `Retention.DeleteInBatchesAsync` deletes 5,000 rows at a time, at most 1,000 batches a run, and
each run logs what it removed. The day counts are configuration with code defaults.

| Data | Kept | Setting |
|------|------|---------|
| Occurrences nobody acted on (pending, never moved, no remark) | 90 days after they were due | `Tasks:UntouchedOccurrenceRetentionDays` |
| Occurrences someone completed, cancelled, moved or annotated | until the todo or the account is deleted | – |
| Future occurrences of a cancelled todo, untouched | deleted at the cancel (they never happened) | – |
| Notifications (the notification centre) | 30 days, read or not | `Notifications:RetentionDays` |
| Sign-in tokens | 7 days after they expire | `Identity:Retention:ExpiredTokenGraceDays` |
| Devices without a push token (desktops, push off) | 180 days unseen | `Identity:Retention:IdleDeviceDays` |
| Devices with a push token | until signed out, or until the push provider reports the token dead | – |
| Todos, Pomodoro history, every Budget record | until the person deletes them (never automatic for money) | – |
| A deleted account | closed at once, erased with everything in it 7 days later (above) | `Identity:AccountDeletion:GraceDays` |
| The record of an erased account (its id and dates) | 30 days | `Identity:Retention:ErasedAccountRecordDays` |
| A free account (its id, the admin who added it, when) | until an admin removes it, or the account is erased | – |
| The calendar feed's link (what the link is signed from, when it was made, when a calendar last read it) | until the person turns it off or replaces it, or the account is erased | – |
| A bank statement's file | never received: read on the person's device, only the lines kept come, as movements ("Statements in, data out") | – |
| An export of a person's data | built in memory for the one request, nothing of it kept | – |
| Nightly database dumps | 14 days (DEPLOYMENT → Backups) | the compose file |
| Logs in Loki (no addresses, names or contents: below) | 30 days | `deploy/observability/loki.yml` |
| Metrics in Prometheus (counts and durations, no personal data) | 30 days, at most 2 GB | the compose file |
| Console logs Docker keeps | 5 × 10 MB per container, oldest first | the compose file |

A phone that still takes pushes is kept however long the app stays closed: someone may only ever see the reminders.
An uninstalled app's token is reported dead at the next push, and the device goes then. Cancelling a todo leaves
its missed past occurrences pending, without reminders, so they age out like the rest. Moved or annotated ones are
cancelled and kept.

### Observability: Prometheus, Loki and Grafana on the server

Decided 2026-10-05, before the load test, which needs the measurements. All on the one server, in the compose file;
nothing leaves it, and nothing of it is published to the internet.

- **Metrics: OpenTelemetry, pushed to Prometheus.** The API exports every 15 s over OTLP to Prometheus's own
  receiver (`--web.enable-otlp-receiver`): no collector, no scrape endpoint to protect, only stable packages.
  Most metrics are .NET's own: requests by route and status (`http_server_request_duration_seconds`), Kestrel,
  SignalR connections, rate limiting, sign-ins, HttpClient, the runtime (CPU, memory, GC, thread pool), Npgsql (pool,
  command durations) and EF Core. Kadans adds what a person would feel first (`Telemetry.Meters`; one meter per
  module, named `Kadans.<Module>`, through `IMeterFactory`):
  - `kadans_reminder_lateness_seconds`: from a reminder's notify time to its dispatch; `kadans_reminders_sent_total`,
    `kadans_reminders_stale_total` (skipped, too late);
  - `kadans_pomodoro_deadline_lateness_seconds{kind=advance|time_up|finish}`;
  - `kadans_calendar_feed_fetches_total{outcome=served|unchanged|withheld|refused|limited}`: reads of calendar feed
    links (`withheld`: an empty calendar, the account has no subscription);
  - push: `kadans_push_queue_length`, `kadans_push_dropped_total`, `kadans_push_delay_seconds` (queued to answered),
    `kadans_push_messages_total{result=sent|failed|dead}` per device, `kadans_push_withheld_total` (phones without a
    subscription);
  - `kadans_job_duration_seconds{job_name, outcome}`: every Quartz pass, through a job listener in the host;
  - admission control, from the host: `kadans_admission_shed_total{reason=queue_full|queue_timeout|hub}` (turned away
    with a 503, also marked `kadans_admission="shed"` on the request metrics so "server errors" leave them out),
    `kadans_admission_waiting`, `kadans_admission_wait_seconds` (for a place, those that waited);
  - from the backup container, through node-exporter's textfile collector: `kadans_backup_last_success_timestamp_seconds`.

  Labels stay bounded (routes, results, job names), never a user id. Histograms carry explicit buckets
  (`InstrumentAdvice`) sized for what they measure.
- **Logs: Serilog, pushed to Loki.** The console sink stays (`docker compose logs`); the OpenTelemetry sink sends the
  same events to Loki's OTLP endpoint. Each property arrives as structured metadata, nested ones flattened with `_`,
  so `{service_name="kadans-api"} | RequestPath="/todos" | StatusCode >= 500` needs no parsing; `trace_id` ties one
  request's lines together. One line per request is the metrics' job now: a request is logged when it failed (4xx,
  5xx, an exception) or took over a second (`Telemetry.RequestLogLevel`).
- **No personal data in either.** Logs carry user ids, never an address, a name, what someone wrote (a todo's title)
  or a secret; a failed sign-in does not log what was typed. The privacy policy can say so.
- **Grafana** reads both, with one provisioned dashboard (Kadans: overview, API, reminders and push, jobs, database
  and runtime, server, logs) and twelve alerts, emailed through Resend's SMTP (`deploy/observability/grafana/`):
  the API silent for 5 minutes, more than 5 server errors in 10 minutes, any request turned away as "server busy"
  in 10 minutes, reminders' p95 over a minute, Pomodoro
  deadlines' p95 over 10 s, any push dropped, over 20% of pushes failing, a job throwing, the disk over 85%, memory
  under 10%, no backup for 26 hours, and the HTTPS certificate within 14 days of its end. Grafana listens on the
  server's loopback only: an SSH tunnel reaches it.
- **node-exporter** adds the server: CPU, memory, disk, and the backup's last success.
- **Cost.** Memory caps: Prometheus and Loki 512 MB, Grafana 768 MB, node-exporter 64 MB, so monitoring cannot starve
  the API or Postgres. The three Go programs also get `GOMEMLIMIT` below their cap, so their garbage collector works
  harder near it instead of letting the kernel kill them. Grafana's numbers are measured (about 250 MB idle, 590 MB
  with three browsers opening the dashboard at once); at 256 MB, opening the dashboard got it killed. Traces are not collected (one process, little to follow across); the trace ids in the logs
  are enough to group a request's lines.

### Reminders ring on the phone (approved 2026-10-09; the server, Android and desktop are built)

Today a reminder is the server's: `OccurrenceReminderJob` finds it due, the dispatcher stores it in the notification
centre, sends it live to open apps and pushes it through Firebase. A phone that is offline at that minute hears
nothing, and hears it late when it comes back; in Haiti that is common. An iPhone hears nothing at all outside the
app (there is no APNs yet). So the phone rings its reminders itself, and the server stays the source of truth and the
safety net. The motive is the people's: the load test showed reminders are a small share of the server's work
(docs/LOADTEST.md).

1. **The server keeps the rules; the phone gets instants.** `POST /reminders/sync`, with the device's installation id,
   returns the account's pending occurrences whose reminder falls in the next 7 days: occurrence and todo ids, notify
   time, start, and the notification's title and text, written by the same code as a push (the account's language and
   time zone), so both always read the same. The server records on that device when it synced, how far its window
   reaches and which version of the account's reminders it holds. No second recurrence engine or text formatter on
   the phone: rescheduled, cancelled and done occurrences are already settled in what it gets. A phone without a subscription gets no window, as it gets no push. (The window is Tasks';
   the device's sync state is Identity's, recorded through a SharedKernel interface, as push targets are read.)
2. **The phone schedules them with the OS.** Android: exact alarms (`setExactAndAllowWhileIdle`), which need the
   "Alarms & reminders" permission – off by default since Android 14, so the app asks once, saying what it is for. A
   phone without it schedules nothing and stays on push, as today. iOS: the 64 soonest local notifications
   (`UNUserNotificationCenter`). Desktop: a timer in the app, which keeps running in the tray. A tap opens the todo
   (a new deep link: today a notification opens nothing in particular).
3. **The window is kept current**: when the app starts or comes back to the foreground, after the person's own
   changes, after a change of language or time zone, by a background job twice a day (JobScheduler on Android: one
   periodic job needs no library; background refresh on iOS), when the server says the account's reminders changed
   (live over the hub, otherwise a silent push), when the hub is back after a drop, and after a reboot or an app
   update. It is kept in the app's settings store as it came (a few hundred entries at most).
4. **The server pushes a reminder only where the device may not have it.** Devices that do not schedule (older
   versions, no permission, desktop) get the push as today. So does a device whose window is stale: holding an older
   version of the account's reminders, not reaching that notify time, or not synced for 36 hours (its alarms may be
   gone). In the
   common case nothing is sent twice. When both arrive (a stale phone), they carry the occurrence as their identity
   and the phone shows one: an Android reminder push becomes a data message the app displays itself, so it can drop
   one it has already rung (apps already installed display data messages too).
5. **The notification centre stays the server's.** The job still stores every reminder and sends it live to open
   apps; on a device that rang it already, the live event only updates the bell.
6. **A local alarm for something changed elsewhere** (deleted, moved or done on the desktop while the phone slept):
   the "changed" signal re-syncs the phone, and an alarm that rings while online first checks its occurrence (two
   seconds at most) and stays quiet if it is no longer due. Offline it rings: the phone cannot know better.
7. **Signing out, switching accounts, or the paywall closing on a phone** cancels every local reminder. Every sign-out
   path ends in clearing the stored tokens: that is where the app cancels them.

Server (built): Tasks keeps each account's reminders version (`reminder_changes`), one more for every change a person
makes to a todo, its rule or an occurrence, in the same transaction as the change: in one place, the context's save,
so no feature forgets it, and the jobs' own bookkeeping (stamping a reminder sent, moving the horizon) moves nothing.
`POST /reminders/sync` reads the version and the window in one snapshot (repeatable read) and records on the device
(`Device.RemindersSyncedAt`, `RemindersThrough`, `RemindersVersion`, through `IDeviceReminders`) exactly what it
holds: no clock margins, and the phone that made a change and syncs right after holds it. The reminder job hands each
reminder's notify time, start and account version to the push worker, which skips covered devices
(`kadans_push_skipped_total{reason="on_device"}`). A reminder push lives until its start (Firebase TTL), collapses per
occurrence, and is a data message for a phone that rings reminders (the app shows it, or drops one it already rang);
older apps still get a notification the system shows. A change sends "reminders.changed" two seconds later (gathered
per account): live on the hub, and a silent push to the phones that ring. `GET /reminders/{occurrenceId}` answers
whether a reminder is still due; `DELETE /reminders/sync/{installationId}` stops a device. Checked by
`tools/smoke/reminder_flows.py`. Unchanged: the job and its timing, the bell, desktop's live channel, and every app
version already installed.

**Two reminders per occurrence (2026-10-12): before the start, and "starts now".** The lead's reminder alone left the
moment to begin silent (the owner's test: reminded at 08:45, nothing at 09:00). Every occurrence with notifications on
now also rings at its start; a zero lead is the start alone (`ReminderMoments`). `TodoOccurrence.StartNotifiedAt` stamps
it as `NotifiedAt` stamps the other (bookkeeping: it moves no version), and `OccurrenceReminderJob` sends the reminders
before the start, then the "starts now" ones (filtered index `ix_todo_occurrences_start_due`). A reminder sent at or
after the start reads "starts now", carries the start as its moment and stamps both (`MarkReminded`): never two at once,
whether the server was down or the todo was made inside its lead. An occurrence whose focus session is running gets
neither: the person has begun (stamped, not sent: `kadans_reminders_skipped_total{reason="focus_running"}`). Only its
own: a session left running from the occurrence before ends as this one starts, and never quiets it (Pomodoro, below;
until 2026-10-31 any session on the todo did, and a forgotten one swallowed the next occurrence). The window holds
both moments as two entries (the phone's identity `occurrence@notifyAt` tells them apart), each with its todo's template
so the app knows which button to offer. `GET /reminders/{id}?at=` checks one reminder by its time: due unless the
occurrence is gone, done, cancelled or moved (that time is none of its reminders), or a session for it runs; without `at` (an
app from before) it answers as before. A reminder push lives until it is of no more use: its start, or ten minutes after
a "starts now" (`ReminderDelivery.UsefulUntil`, the apps' own rule); collapsed per occurrence, the later replaces the
first. The migration stamped occurrences already under way, so none got a late "starts now".

**A reminder's buttons.** The notification offers "Start focus" for a todo with a Pomodoro template, "Done" for any
other, and "In 10 min" (`ReminderActions`, common to the apps): never an automatic start (the app cannot know the person
is there, and a session nobody runs would fill the focus stats). "Start focus" starts the session as the todo's screen
does (looping, hands-free as last chosen on the device), for that occurrence; "Done" completes it (one done or
cancelled elsewhere meanwhile counts as done). Either way the occurrence's other reminders stay quiet on that device,
offline too (`LocalReminders.settled`). "In 10 min" is the device's own (`LocalReminders.snooze`, kept beside the window,
which a sync replaces): the reminder rings again ten minutes later, read afresh ("Starts at 09:00", "Started at 09:00"),
checked as the reminder it was, and the occurrence's "starts now" inside those ten minutes waits with it.

The buttons come with "starts now" (and a reminder rung again after "In 10 min"), never with the heads-up before the start
(`UpcomingReminder.isHeadsUp`; owner's phone test, 2026-10-26). The heads-up says "get ready" and a tap opens the todo;
"starts now" is the moment to decide. On the heads-up, "Start focus" would start the session minutes early and quiet the
"starts now", and "In 10 min" would quiet it too and ring after the start: put off past the very moment it announces.
A button that worked tells the app's screens at once (`DataChanges`, below): the todo shows done, not pending.

Android (built): `reminders/LocalReminders` (common) keeps the window in the settings store and decides what rings;
`ReminderScheduler` is the OS's side (Android: `AndroidReminderScheduler`; desktop below; iOS has none yet and stays on
the push and the hub). What rings is always read from the stored window:
- **One exact alarm**, the next reminder's (`setExactAndAllowWhileIdle`, far from Android's 500 per app). When it
  rings, every reminder due and not rung here is checked with the server (two seconds at most, in parallel), shown
  unless no longer due (a moved one fetches the window again), and the next alarm is set. A reminder rings late (a
  phone that was off) until its event starts, or ten minutes after its time when that is later; never twice
  (`occurrence@notifyAt` remembered three days, so a moved one rings again).
- **Notifications**: their own channel, `kadans.reminders` (urgent: heads-up, sound; named in the app's language),
  tagged with the occurrence as Firebase tags the push it shows, so one replaces the other ("starts now" replaces the
  reminder before it). Two buttons, broadcasts to `ReminderActionReceiver` (not exported, immutable PendingIntents, one
  data URI per reminder and button): it asks the server within the eight seconds it gives itself, then updates the same
  notification silently: "Focus session started" (a tap opens the session, `kadans://todos/{id}/focus`), gone, or the
  reminder again saying Kadans could not be reached, its buttons still there. A system-shown reminder
  push goes to that channel too (an app too old to have it falls back to its default one). A tap opens the todo
  (`kadans://todos/{id}`, only with a session), whether the app is open, in the background or stopped.
- **Pushes**: "occurrence.due" goes through `LocalReminders.pushed` (dropped if it rang here or the session is gone);
  "reminders.changed" fetches the window, always: Android freezes an app in the background, and the server may have
  dropped a hub connection the app still believes open. The hub's own copy goes through `pushed` too
  (`SystemAlerts`): wherever the app shows reminders itself, a reminder shows once, whichever of the alarm, the push
  or the hub brings it first, and not at all when too late to be of use (a catch-up after a reconnect). Home's
  snackbar is left for the other live notifications.
- **The permission**: asked once, with its explanation, when a todo with a reminder is saved and "Alarms & reminders"
  is off (the todo is saved whatever the answer; "Allow" then opens the system's screen; a dismissed dialog asks again
  next time). Settings → "Reminders on this phone" says which way reminders come and opens the screen that turns them
  on. Revoking it in the system settings stops the app and its alarms: the next sync (the app's next start, a change,
  the twice-daily job) tells the server, which in the meantime still trusts the window, for 36 hours at most.
- **Receivers** (not exported): the alarm; boot, app update and the permission granted (`restore`: the stored window is
  scheduled again, then fetched); and a persisted JobScheduler job every 12 hours with a network, only while this
  phone rings reminders and has access (an empty window that reaches no further than now keeps no job alive).
- **Sign-out**: every path ends in `save(null)` on the token store (`ClearAwareTokenStore`), which cancels the alarm and
  the job and clears the window; a fetch begun before it lands nowhere. The paywall does the same.

Checked on an Android 15 emulator against a local API (2026-10-09): the dialog, the permission granted and the window
fetched by itself, a change made elsewhere moving the alarm within seconds, a reminder rung in airplane mode at its
minute (and the server pushing nothing to that phone), a tap opening the todo (with the app alive and after Android
had killed it), the alarm back after a reboot, and a sign-out cancelling everything.

Desktop (built): `DesktopReminderScheduler`, a timer in the app for the next reminder, running while the window is
closed to the tray. It sleeps in steps of 30 s at most on the wall clock, so a computer that slept or had its clock set
rings on time within one step. The app's start schedules the stored window again (`restoreDesktopReminders`: a
reminder missed while it was closed rings if still of use), then fetches it. Nothing rings while the app is not
running, as before (the hub had nothing to deliver to then either). No background job and no permission: while the
app runs, the hub keeps the window current (each change, each reconnect, at least hourly when its token is renewed;
a change made on the device itself fetches it at once, `KadansApi.remindersChanged`),
and Settings has no reminders section. On Linux a reminder is a freedesktop notification over D-Bus
(`FreedesktopNotifications`, dbus-java as the tray): its buttons where the server has them (COSMIC, GNOME and KDE do), a
click opening its todo (`DesktopWindow`: the window comes forward, the link opens in it), the body escaped where the
server reads markup, and clicks taken from the notification server alone, not any program on the session bus; the
result shows as on the phone (`DesktopReminderNotices`). Without a notification server it falls
back to `notify-send`, without buttons. Windows and macOS show a balloon of the tray icon, without buttons: a click on it
reaches only the icon's action, so an "open" within a minute of a reminder's balloon opens its todo (`ReminderBalloon`).
Checked against a local API by `RealDesktopRemindersSmokeTest` (opt-in, `KADANS_API_URL`): a reminder reaches the
window over the hub, then rings from the timer with the server out of reach (112 ms after its time).

Order: the server first (3–4 days; on its own it changes nothing until an app says it schedules), then Android
(about a week), desktop (1–2 days), and iOS with the iPhone app on a Mac (3–4 days). Pomodoro's "time's up" could
move the same way later.

### Screens stay in step (2026-10-26)

A screen loaded what it shows when it opened, and only then. A change made elsewhere (another device, a notification's
"Done", a recurring rule) stayed invisible until the person left the screen and came back, and acting on the stale
screen got a refusal ("already completed") for what it showed as still to do. Now every change reaches the screens:

- **The server says what changed.** `ILiveChanges` (SharedKernel; `LiveChanges` and `LiveChangesPublisher` in
  Notifications) sends `data.changed` with `{ "areas": [...] }` to the account's open apps, the changes gathered for a
  second (a todo and its occurrences, a movement and the limit alert it set off: one signal). Live only, nothing stored
  or pushed: an app that was not connected loads afresh when it comes back. Tasks says `todos` after a person's save
  touching a todo, a rule, an occurrence (its note too) or a focus template (`TasksDbContext.ChangesScreens`), and
  after a todo is deleted for good, whose bulk deletes pass no save (`ChangedByBulkStatementsAsync`, which moves the
  reminders version too: until 2026-11-13 a deletion told no other device); sessions
  have their own `pomodoro.run.changed`, and the jobs' stamps say nothing. Budget says `budget` after any save, to each
  owner of a row it changed (`BudgetDbContext.Owners`): the recurring job's bookings and the limit alerts too.
  `reminders.changed` stays what phones fetch their reminder window on (with its silent push). No read saves anything,
  so the apps' reloads never signal again; the device that made a change reloads once more than it needed, a few GETs.
- **The app listens** (`sync/DataChanges`): the hub's `data.changed`, a session changed (`todos`), the hub back after a
  drop (everything: what changed meanwhile was not heard), the app back in front (everything, `ON_START` but the
  first: on Android the hub sleeps in the background), and this device's notification buttons (at once, even with the
  hub away). Home, a todo, the calendar and the stats reload on `todos`, the budget on `budget`.
- **Listening from the moment it is made** (`sync/Listening.kt`, 2026-11-17). Whatever hears a signal (a screen's view
  model, the reminders, the widget, the quiet mode, the system alerts) subscribes before its constructor returns:
  `scope.listen(signals) { }`, and for a screen `dataChanges.reloadOn(scope, area) { }`, which also folds the signals
  that arrive while a reload runs into one more. A shared flow keeps nothing for a listener that is not there yet,
  and `scope.launch { signals.collect { } }` subscribes when that coroutine first runs: on a background dispatcher a
  moment later, through `merge` or `conflate` several turns later, and what is sent in between is lost without a
  trace. Nothing in the app is written that way any more (`ListenFromTheStartTest` reads the code and fails on it).
- **Quietly**: no spinner; a failed reload (offline) keeps what is shown; a todo deleted elsewhere closes its screen;
  a reload that ends after the person moved to another month is dropped (the calendar, the budget).
- **Acting on a stale screen**: "Complete" or "Skip" on an occurrence already so shows it so, without an error; any
  other refusal reloads the screen and says why.

Checked live by `RealRealtimeSmokeTest.hub_tells_the_screens_what_changed` (`KADANS_API_URL`): an occurrence completed
and an account added reach the hub as `todos` and `budget`; by hand once, a recurring rule's booking with nothing else
going on (the job at a one-minute interval: 18 s).

### The calendar feed (2026-11-13)

A tester asked to see their todos in their phone's calendar. Kadans publishes each account's todos as an iCalendar
file behind a secret link, which Google Calendar, Apple Calendar, Outlook or any calendar app subscribes to: one
feature for every platform, the desktop included, and nothing to build per calendar provider. It goes one way: nothing
done in the calendar comes back. Chosen over Google's own API (Google only, its verification for calendar access,
tokens to keep on the server, two-way conflicts); the phone writing into its own calendar (instant, but that phone
only) can come later.

- **What a calendar reads** (`Features/CalendarFeed`: `CalendarFeedReader`, `CalendarFeedEvents`). Titles and times,
  nothing else: no description, no note, no alarm (Kadans rings its own reminders). Pending times and done ones (their
  title starts with "✓ "), never cancelled ones, from 30 days back to 90 days ahead: the stored occurrences, then the
  times the rules give, as the calendar screen previews them. At most 2,000 events, the ones to come kept first
  (`Tasks:CalendarFeed*`). One event per time, never a rule: the file says exactly what the app shows, moved and
  skipped times included, whatever a calendar app would make of an RRULE. A todo has a start and no end, so an event
  lasts the todo's focus cycle, 30 minutes without one, 5 minutes to a day, and never runs into the todo's next time
  (every ten minutes: events of ten minutes). An event's UID is its todo and the time the rule gave: a moved time is
  the same event, moved. Times are in UTC.
- **The link is the credential** (`SecretLinks` in SharedKernel, `CalendarFeedLinks`). A calendar app can send nothing
  but an address, so `/calendar/feed/{secret}/kadans.ics` is read without a sign-in. The secret is 16 random bytes
  (the nonce: all the database keeps, one row per account in `tasks.calendar_feed_links`) followed by their
  HMAC-SHA256 under a key derived from `Jwt:Key` for this purpose alone (`ServerSecret`, HKDF). So a database dump
  opens no feed, a link this server did not sign is refused before any database read, and Settings can show the link
  again whenever asked. Changing `Jwt:Key` ends every link, as it ends every session. "Replace the link" draws a new
  nonce and "Turn off" deletes the row: the old link stops at once. An account that is closed (its owner asked to
  delete it, or it was deactivated: `IUserDirectory.IsOpenAsync`) stops answering at once too, and the row goes with
  the account's erasure (`TodoErasure`). Every refusal is the same bare 404. The link survives a password change:
  replacing it is how it is taken back.
- **It reads as its owner.** Once the link is recognised, the endpoint makes its owner the request's user
  (authentication type `CalendarFeedLink`, seen by nothing else), so the data is read through the same per-user query
  filters as the app's own requests, not around them.
- **https only.** Kadans hands out `https://` links and never `webcal://`: a calendar app given that one reads over
  plain http, the secret in the clear at every read. nginx refuses `/calendar/feed/` on port 80 instead of redirecting
  it, so such a subscription fails the first time rather than working while it leaks. That is why there is no "open in
  my calendar app" button: the link is copied, and pasted where the calendar app takes an address.
- **Never in a log.** The request log writes the path, the rate limiter's warning did, and ASP.NET attaches the
  request's path to every line logged while a request runs (its scope's `RequestPath`), which Loki would keep a month.
  `SecretLinkScrubber`, a Serilog enricher on the root logger (`Telemetry.HideSecretLinks`), cuts every text property
  of every event after the prefix: `/calendar/feed/…`. `TelemetryTests` runs a server with ASP.NET's own logs turned
  all the way up and finds the link in no line; the smoke script searches the API log for every link it was given.
- **Its own allowances.** Google's or Apple's servers read everyone's feed from the same few addresses, so everything
  under the prefix has its own per-address allowance, apart from the API's (`RateLimiting:FeedPerMinute`, 600; a
  mistyped or invented link counts too). On top of it each link has its own, wherever it is read from
  (`CalendarFeedLimiter`, 60 an hour, `Tasks:CalendarFeedFetchesPerHour`): asked only for a link that exists, so there
  are never more counters than links.
- **Cheap to ask again.** Calendar apps ask often and todos change rarely: the file's hash is its ETag, and
  `If-None-Match` gets a 304. The same events always write the same bytes (a fixed order, `DTSTAMP` the last change and
  never "now", a window that moves once a day). The file is written by hand (`CalendarFeedWriter`: CRLF, text escaped,
  lines folded at 75 octets between characters) and read back with Ical.Net in the tests. It invites a calendar to
  read again every 15 minutes (`REFRESH-INTERVAL`, `X-PUBLISHED-TTL`): Apple's and Outlook's listen, Google's reads
  when it wants, up to about 12 hours later, and the app says so.
- **Settings → My todos in my calendar** (`ui/settings/CalendarFeedPage`, over `GET` and `PUT /calendar-feed`,
  `POST /calendar-feed/reset`, `DELETE /calendar-feed`). Off: what it does and what a calendar gets. On: the link with
  "Copy", the last time a calendar read it (`last_fetched_at`, kept to the minute: "is it working?" has an answer),
  where each calendar takes the link, how late a calendar may be, that the link is a secret, and "Replace the link"
  and "Turn off", each asked twice. Google Calendar's phone app cannot add a calendar from a link: on a computer a
  button copies the link and opens Google Calendar's "From URL" page, on a phone the page says to do it once on a
  computer. Turned on, off or replaced on another device, the page follows (the link's row is saved through
  `SaveChanges`, which says `todos`; "Screens stay in step").
- **Part of the phones' subscription** (decided 2026-11-13). The feed puts the todos on a phone, in its calendar
  app: free, it would be a way around the paywall through the free desktop app. So it asks what push asks
  (`IMobileAccess`): an account without a subscription cannot turn it on or replace its link (402, error 10059),
  `GET /calendar-feed` says `available: false`, and the page says where to subscribe (it is the desktop app that
  shows this: a phone without a subscription is behind the paywall). A subscription that ends keeps the link and
  empties the calendar: a refusal would leave a calendar app showing the todos it last read for good, and the link
  would have to be given again later. When the subscription is back the same link fills again. Turning the feed
  off never needs a subscription. While `Billing:Required` is off everyone has it, like the phone apps.

Checked by `tools/smoke/calendar_feed_flows.py` (the file's content, 304, done, skipped and moved times, another
account's feed, a closed account's, invented and changed links, reset, off, no link in the API log; and against an API
started with `Billing__Required=true`, the subscription: none, ended, back) and by `RealApiSmokeTest` from the app's
side. Not checked: a real Google, Apple or Outlook account subscribing to it.

### The home-screen widget (2026-11-13)

Second of the integrations ranked after a tester's feedback (the calendar feed was the first): the day's todos on the
phone's home screen, without opening the app. Android now; the iPhone's (WidgetKit) comes with the iPhone app, and a
computer's desktop has none.

- **What it shows** (`widget/TodayWidgetContent`, pure, shared). "Today" and the date, then today's pending todos,
  soonest first, each with a circle and its hour; one whose hour has passed and is still pending stays. With room
  left, tomorrow's follow under "Tomorrow", without a circle, as long as one of them fits under the label; todos that
  do not fit give their last line to "4 more"; with none left it says "Nothing left today". Today is the phone's day,
  the hours follow the account's clock and the words the app's language, as on the app's screens. As many lines as the
  widget's height holds (40 dp each, tall enough for the circle): it starts four cells by two and resizes both ways.
- **Taps.** A todo's hour or title opens its screen (`kadans://todos/{id}`, the reminders' link), "+" the form for a
  new one (`kadans://todos/new`), anywhere else Home (`kadans://home`: the day's list, whatever screen the app was left
  on). Each needs a session; signed out, the app opens where it signs in.
- **Done, with five seconds to undo** (the owner's choice, 2026-11-15, among three versions on a mock-up: none, at
  once, with an undo). A tap on a line's circle ticks it, strikes the title through and shows "Undo"; the tick takes
  it back too. Nothing is sent meanwhile: a completion cannot be undone on the server, and a tap on a home screen is
  easy to make by accident, so the five seconds on the phone are the undo. Then the occurrence is marked done, as a
  reminder's "Done" does (done or skipped elsewhere meanwhile counts; its reminders stay quiet on this phone; the
  app's screens are told), and the line goes. Offline, the line goes all the same and the completion waits in the
  phone's settings for a network; refused for good (the todo was deleted, the session ended) it is dropped and the
  next fetch shows what is true. Only today's lines have a circle: tomorrow's have not come, as a reminder offers
  "Done" only at its todo's start (`TodayWidget.markDone`, `undo`, `sendDue`).
- **Not in it.** The running focus session: its notifications already say each phase, the session is one tap away,
  and with the app closed the phone does not know a session's state reliably.
- **Its data** (`widget/TodayWidget`, shared, beside `LocalReminders`). One `GET /occurrences` for the phone's today
  and tomorrow, kept in the app's settings (`TodayWidgetStore`): shown offline, and in the first minutes of a new day.
  One fetch at a time, and none while no widget is placed. A phone without a subscription shows the widget locked, by
  the rule that puts the app behind its paywall (`SubscriptionGate.blocks`). Signed out, by any path, the store is
  emptied and the widget says to sign in.
- **What keeps it current.**
  - With the app open: this device's own saves, the hub (`reminders.changed`, `data.changed`, back after a drop), a
    notification's button, and the app back in front when what it holds is more than two minutes old. Leaving the
    front redraws it without asking the server: the language, the clock or the appearance may have been changed.
  - With the app closed: the server's silent "reminders changed" push (`KadansMessagingService`), the system (placed,
    restarted, the app updated), a job every hour with a network (`TodayWidgetRefreshJob`), and a new day or a change
    of the phone's clock or time zone (`TodayWidgetClockReceiver`: drawn at once from what is held, then fetched).
  - The midnight alarm is exact where the phone allows it ("Alarms & reminders", which the reminders ask for), within
    ten minutes otherwise: a plain alarm set a day ahead may ring hours late. It never wakes the phone.
    `updatePeriodMillis` is 0: that timer does wake it.
  - A tapped "Done" is sent by an alarm five seconds later (`TodayWidgetActionReceiver`, `TodayWidgetUpkeep.await`),
    never by waiting in the receiver: Android hands an app its broadcasts one at a time, and an "Undo" held up behind
    the wait would arrive too late. A send the network holds up gives way after three seconds, shorter than the five
    a tap waits. What could not go is sent by a job as soon as there is a network, and by any later fetch.
- **On Android** (`widget/TodayWidgetProvider`, `AndroidWidgetSurface`): plain RemoteViews, a header and one small
  layout per kind of line, each line with its own immutable PendingIntents: to the app's activity for what opens, to
  a receiver that is not exported for its circle and its "Undo". No library: Glance
  would be a dependency outside the template's tested set, for a header and a few lines. Colours are resources, light
  and `values-night`: the widget follows the system's theme like its neighbours on the home screen, not the app's
  Appearance setting, and the launcher redraws it when the theme turns. The provider is exported, as a widget's must
  be (the launcher is another app); it acts on the system's widget actions only and reads nothing from an intent but
  the widget ids. The clock receiver and the job are not exported.
- **Two languages meet here.** The widget's own words come from `StringsCatalog`, the app's language. What the
  system's widget picker shows (the name, the description, the preview's sample day) are Android resources in the
  phone's language (`values`, `values-fr`, `values-ht`), like the app's name.
- **Settings → "Home-screen widget"** offers "Add to my home screen" where the home screen takes a widget when an app
  asks (`requestPinAppWidget`: Android 8+, most launchers). Few people look in the launcher's own list.

Checked on the emulator (Android 15, Pixel launcher) with a local API: placed from Settings' button; the picker's
preview; the day's and tomorrow's todos, "4 more", "Nothing left today"; a todo, "+", the header and "more" each
opening what they should, with the app open, in the background and not running; light and dark with the system; the
app's language and clock after leaving it (French, 12 hours); a todo completed from outside the phone gone within
seconds; offline; the app's process killed and the hourly job forced; signed out (nothing of the account left in the
phone's settings, "+" opening the sign-in screen) and signed in again; the day turning over five seconds after
midnight, the next alarm set; a circle tapped and left (struck through with "Undo", then done on the server), tapped
and undone (still to do on the server), and tapped offline (gone from the widget, sent when the network came back).
Not checked: Android below 12 (no emulator image here), a resize by hand, other launchers, and a real phone.

### Do Not Disturb during focus (2026-11-15)

Third of the integrations ranked after a tester's feedback: the phone goes quiet while a focus phase counts down.
Android 10 and later. The iPhone cannot (Apple lets no app switch a Focus on), a computer has no one switch an app may
use, and before Android 10 the only way is to flip the phone's main Do Not Disturb and put it back afterwards, which is
not risked. The owner chose the defaults on a written proposal (2026-11-15).

- **When the phone is quiet.** While a focus phase of one of the account's sessions counts down, whichever device
  started it. Not in a break (that is when one looks at the phone; what was silenced waits in the shade), not while
  paused, not with the timer at 0:00 waiting for the person (a manual session, or a hands-free cycle that ended and asks
  "Another cycle?"), not once the session is over. The sound comes back two seconds before the phase ends
  (`QuietPlan.SOUND_BACK`), so what Kadans says at that end ("time's up", the break that begins) is heard.
- **Off by default, one switch.** Settings → "Do Not Disturb during focus", this device's own setting like its
  language (it survives a sign-out; the access Android gave does too). The session screen offers it once, on a phone
  that can and has it off (`QuietOffer`); either answer ends the offer.
- **What Android has to allow**, asked one at a time by opening its own screen when the switch is turned on
  (`QuietSection`): "Do Not Disturb access" (the special access for an app to have a mode; it does not let an app read
  notifications) and "Alarms & reminders" (the exact alarm that brings the sound back; without it the sound could come
  back ten minutes late, so Kadans does not take it away). While one is missing the switch stays on and Settings says
  which. Nothing new for Google Play to review was found: `ACCESS_NOTIFICATION_POLICY` is a normal permission.
- **Kadans' own mode, never the phone's** (`quiet/AndroidQuietMode`): an `AutomaticZenRule` named "Kadans focus", listed
  in the system's Do Not Disturb settings (under Modes on recent Android), switched with `setAutomaticZenRuleState`.
  Turning it off leaves the person's own Do Not Disturb and every other mode as they were; Android combines the modes
  that are on and the strictest wins. From Android 15 an app can no longer switch the phone's main Do Not Disturb at
  all (`setInterruptionFilter` only toggles a rule the system makes for the app), so a rule of one's own is the only
  shape that is the same on every version. Its row in the system's settings opens Kadans' Settings (`kadans://settings`:
  the main activity is the mode's configuration activity, one mode at most; the system starts it by name with the
  mode's id as an extra, and with `ACTION_AUTOMATIC_ZEN_RULE` only from its list of kinds of mode, so `linkOf` takes
  both).
- **What gets through** is set once, when the mode is created, and is the person's to change in the system's settings
  from then on (the mode is never updated): alarms; music and videos (people focus with music); calls from starred
  contacts, and anyone who calls twice within fifteen minutes; reminders, which Kadans' own are
  (`Notification.CATEGORY_REMINDER`), so a "starts now" for another todo is not swallowed by a focus phase; calendar
  events. Everything else is silent, does not pop up and does not light the screen; it is listed in the shade.
- **A silent notice while quiet** (channel `kadans.quiet`, low importance): "Quiet during focus", "Until 10:25" in the
  account's clock, a tap opening the session, and "Sound on". It removes itself a minute after the phase's end,
  whatever happens to the app.
- **The person's word holds for the session.** "Sound on", or turning Kadans' mode off in the system's own settings
  (quick settings, Modes), brings the sound back and keeps it for the rest of the sessions running then; the next
  session is quiet again. Android itself only keeps such an "off" until the phase is over. From Android 15 the system
  announces that the mode went off (`ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED`), whoever turned it off, Kadans
  included, so the announcement decides nothing; taking it for the person's doing cost the rest of every session its
  quiet (found on the emulator). What decides is the phone itself: whenever it settles while Kadans holds it quiet, it
  looks whether the phone rings for everything although the mode should be on (`QuietMode.turnedOffByPerson`; not
  `getAutomaticZenRuleState`, which keeps answering "on" for a mode the person switched off). The announcement makes
  the phone settle at once (`FocusQuiet.modeWentOff`); before Android 15 there is none, and the person's "off" is seen
  at the next alarm, that phase's end at the latest, the notice staying until then. The person removing or disabling
  the mode there is the switch, off (`modeRemoved`); Android taking it away with its access is not: the switch stays
  and Settings says what is missing.
- **How the phone follows a session** (`quiet/FocusQuiet`, shared, beside `LocalReminders` and `TodayWidget`). A session
  runs on the server, so the phone keeps its timetable (`QuietPlan`, pure): the current focus phase and, hands-free, the
  phases after it up to the end of the cycle, each beginning as the one before ends, nothing past the session's end. A
  manual session is known for its current phase only. It learns of a session:
  - from its own requests: every session a request brings back passes through one place (`KadansApi.seen`) and
    settles the phone before the caller goes on, without a second request (a reminder's "Start focus" has seconds to
    live; a network that drops right after "Pause" still gives the sound back);
  - from the hub while the app is open (`pomodoro.run.changed`; a hub back after a drop reads the sessions again);
  - from a push when the session changed on another device (below);
  - by reading `GET /pomodoro/active-runs` (the account's running and paused sessions) when the app comes to the
    front, after a restart of the phone, an app update, and when Home opens.
  News older than what is held is not believed (the session's `updatedAt`), and a session that ended never runs again
  whatever arrives late. A session the server no longer lists is over, unless the phone heard of it after asking.
  The phase the server says is counting down has begun, whatever the phone's clock makes of its start, and a phase that
  begins within five seconds has begun too (`QuietPlan.BEGINS_WITHIN`): a phone half a second behind the server saw a
  phase just started as about to start, and Android rings no alarm nearer than five seconds, so it went quiet five
  seconds late (found on the emulator). The ends are the phone's clock against the server's times, as the session
  screen's countdown is: a phone whose clock is wrong by a minute gets its sound back a minute off.
- **One exact alarm** for the next change (`QuietMode.wakeAt` → `QuietAlarmReceiver` → `FocusQuiet.tick`), so the
  timetable is followed with the app closed, the phone asleep or offline. The sound coming back waits for nothing.
  Going quiet for a phase that begins by the timetable first asks the server, two seconds at most: the session may have
  been paused or ended elsewhere and the news not have arrived. A receiver never waits longer: Android hands an app its
  broadcasts one at a time.
- **A session changed on another device** is the main case for someone at a desk: started on the computer, the phone in
  the pocket goes quiet. Until now a session's changes only went over the hub, which a closed app does not hear. The
  phone tells the server it goes quiet (`Device.QuietDuringFocus`, with its registration; told again until it went
  through, and when the switch or Android's access changes), and the server pushes it "focus.changed" on every change a
  person makes to a session (start, pause, resume, "Next phase", finish, end, the end time moved) and when a session
  ends by itself (`INotificationDispatcher.FocusChangedAsync`, `FocusSignal`). The push carries nothing: the phone
  reads the sessions. A phase that ran out by itself is not pushed: the phone holds the timetable.
  - It is **urgent while a focus phase counts down** (Firebase's high priority, living until that phase ends): an
    ordinary push to a phone lying still on battery is held for minutes, and that is exactly a phone on a desk.
    Firebase keeps high priority for messages that show something, and lowers it for an app whose urgent messages show
    nothing: the phone shows its "quiet until" notice. So it only goes to phones that said they go quiet.
  - Otherwise (a break, a pause, the session over) it is an ordinary push, worth delivering for four hours (the
    longest phase): the phone's own alarm gives the sound back at the phase's planned end at the latest.
  - The latest replaces one still waiting for a phone that is offline. Phones only, and only with the subscription, as
    every push to a phone (`IMobileAccess`).
- **It must never leave the phone stuck quiet.**
  - Whenever Kadans makes the phone quiet it sets, in the same step, the alarm for that phase's planned end. Whatever
    news is missed afterwards, the sound is back then: early in the worst case (the session was paused and resumed
    elsewhere, so its phase really ends later), never late.
  - A restart of the phone keeps no alarm, and may not keep the mode on: the mode and the alarm are set again from what
    is held, then the sessions are read (`FocusQuiet.restore`; the same after an app update and when "Alarms &
    reminders" is granted). A phone that slept through the end gets its sound back when it wakes.
  - Signed out by any path (the token store's clearing), the switch turned off, Android's access gone, the paywall
    closing: the sound is back at once and nothing of the account stays (`forget`, `turnOff`, `paywall`).
  - The one hole that cannot be closed: Android force-stopping the app during a focus phase (the person, in the system's
    settings; an aggressive battery saver on some brands) cancels its alarms, and Android offers no "quiet until 10:25"
    that ends by itself. The phone then stays quiet until Kadans is opened again or the person turns Do Not Disturb off
    (the quick settings show it on). The same phones lose their reminders the same way.
- **Not a subscription feature of its own.** It lives in the phone's app, which the subscription already pays for:
  behind the paywall nothing is followed, and the server pushes a phone without access nothing.

Checked on the emulator (Android 15, stock settings) against a local API sending real pushes through Firebase:
- **Turning it on**: the switch opening Android's "Do Not Disturb access" page for Kadans; from the session screen's
  offer on a clean install, that page and then "Alarms & reminders" one after the other, and the phone quiet for the
  phase under way on coming back; the server told each time.
- **A session started on the computer** (through the API): the phone quiet 0.1 s later with the app in front (the
  hub), 0.4 to 0.7 s later with the app in the background (the push), 2.2 s with its process not running. Paused,
  resumed, moved on and finished there: each reached the phone within 0.4 to 0.7 s, the pushes urgent or ordinary as
  designed.
- **A whole hands-free cycle** with the app closed: the sound back 2 s before each focus phase's end (the server's
  "Break" then rang), quiet again 1 s into the next focus phase (the server asked first), and nothing more once the
  cycle waited for "Another cycle?". The same offline with the app's process killed, by the timetable alone.
- **The person's word**: "Sound on" in the notice, and Do Not Disturb turned off from the quick settings, each gave
  the sound back, removed the notice and held for the rest of the session; the next session was quiet again.
- **Never stuck**: a restart of the phone in the middle of a phase (Android kept the mode on; Kadans set its alarm and
  notice again and the sound came back on time); signing out while quiet; the switch turned off (the mode gone from
  Android's list); the mode deleted in Android's settings (Kadans' switch off, the server told).
- Android's list of schedules showing "Kadans focus" and opening Kadans' Settings from it; the notice and the mode's
  name in French with the 12-hour clock ("Jusqu'à 6:04 PM").

Not checked: Android 10 to 14 (no emulator image here; there the person's "off" in the system's settings is only seen
at the phase's end), Samsung's, Xiaomi's and Tecno's own Do Not Disturb screens, a phone asleep on battery for a long
time (whether Firebase's urgent push wakes it at once is the documented behaviour, not one seen here), a force-stopped
app, and a real phone.

### Share to Kadans (2026-11-16)

Fourth of the integrations ranked after a tester's feedback: a text or a link from any app becomes a todo. Android now;
the iPhone's share extension comes with the iPhone app, and a computer has no share sheet.

- **Two ways in**, both on the app's main activity (the manifest's two intent filters, text only):
  - the **share sheet** (`ACTION_SEND`, `text/plain`): Kadans is listed with "New todo" under its name;
  - the **menu of a text selection** (`ACTION_PROCESS_TEXT`): "Add to Kadans", for a text in an app that has no share
    button (a PDF, an email).
  The two labels are Android resources in the phone's language (`values`, `values-fr`, `values-ht`), like the widget
  picker's. A share of anything but text (a picture, a file) is not offered Kadans.
- **It opens the form for a new todo, filled in. Nothing is saved before "Create"**: the person still says when, and
  whether to be reminded. A todo made without them, at a guessed date, would be noise in the list.
- **From a text to a title and a description** (`share/SharedText`, pure, shared):
  - the title is the share's subject when it has one (a browser sends the page's title); else the text's first line;
    when that line is nothing but a link, the site's name ("example.com"), which reads better at the top of a todo
    than an address. Longer than 120 characters, it is cut at a word, with "…";
  - the description is the whole text (the link, the message), unless the title already says all of it;
  - both are cut to what a todo holds (below), and cleared of what is not text: control characters, and the invisible
    marks that can make a text read as another (zero-width spaces, direction overrides).
- **Back where the person was.** Opened by a share, the form is alone on the screen (`SharedTodoRoute`, no Home under
  it): "Create" saves, says "Saved in Kadans" and closes that activity, "Cancel" and the system's Back close it too, and
  the person is back in the app they shared from (`App(leave = …)`, Android's `finish()`). Android opens it in a task of
  its own, so a Kadans already open is left as it was.
- **A share is not a way around anything.** It needs a session: signed out, or with a session that turns out to be
  over, the sign-in comes first and the form after it, with what was shared. It reaches the app past Home, which is
  where a phone without a subscription meets its paywall, so the form asks first (`SharedTodoScreen`,
  `SubscriptionGate`) and leads there.
- **What is shared is not trusted.** The activity is exported (it is the launcher's) and now takes text from any app:
  at most 20,000 characters are read, the text is cleaned and cut, nothing in it is opened or run, and it is never
  logged. The route travels as a link the app makes itself (`kadans://todos/new?title=…&description=…`,
  `sharedTodoLink`); the same link from another app does no more than a share does, and is cut the same.
- **A link in a todo's description opens** (`ui/LinkedText`): `http://` and `https://` addresses only, underlined, in
  the system's browser, on every platform. The address shown is the address opened; the sentence's own punctuation
  around it is left out. Without it a page shared to Kadans could be read but not opened.
- **A todo's title holds 500 characters and its description 4,000** (`Todo.MaxTitleLength`, `MaxDescriptionLength`,
  the columns' sizes). The server now refuses a longer one with a sentence (400, code 10060); it used to reach the
  database and come back as a 500 ("Database error"), which typing rarely met and sharing a long message would. The
  forms, new and edit, stop at the same lengths.

Checked on the emulator (Android 15) against a local API: Chrome's share sheet listing Kadans with "New todo", the form
filled in with the page's title and its address, "Create" landing back in Chrome with the todo on the server, and the
system's Back and "Cancel" landing there too; a selection's text (through its intent), its title cut at a word and the
whole of it in the description; a message of several lines; shared to a phone that is signed out, and to one whose
session had been ended on the server (the form after the sign-in, both times); with subscriptions required and none
(the paywall); with Kadans already open (its own window untouched); the phone turned and switched to dark mode with the
form open; the link in the todo's description opening Chrome. Not checked: the selection menu by hand in another app
(Android lists Kadans for it), other makers' share sheets, a real phone, the iPhone.

### The phone's calendar (2026-11-16)

Fifth of the integrations ranked after a tester's feedback, and the other half of the calendar question: the feed puts
the todos in the person's calendar; this shows the calendar's events beside the todos, so that a day is planned in one
place. Android now; the iPhone's (EventKit) comes with the iPhone app; a computer has no one calendar every program
writes to.

- **Read on the phone, shown, and no further.** The events are read from Android's calendar provider when a screen
  shows them (`calendar/AndroidPhoneCalendar`), handed to that screen, and dropped with it. Nothing of them is kept in
  the app's settings, sent to the server or written to a log: the server never learns that a calendar exists. The
  privacy policy says so ("What stays on your phone").
- **Off by default.** Settings → "My phone's calendar" (this device's own switch), and a one-time offer on the Calendar
  tab, where someone looks for their meetings (`PhoneCalendarSection`, `PhoneCalendarOffer`). Turning it on asks
  Android's own question (`READ_CALENDAR`), never at start. Refused twice, Android no longer asks: the same button then
  opens Kadans' page in the system's settings, where the permission is. With the switch on and no permission, Settings
  says what is missing and the screens show the todos alone.
- **Which events** (`Instances`, so a repeating event counts each time): those of the calendars the person shows in
  their calendar app (`VISIBLE`), without the events they declined and the cancelled ones. The person's own choice of
  calendars is respected rather than asked a second time in Kadans.
- **Kadans' own todos do not come back twice.** Someone who gave their calendar app the calendar feed has every todo
  in the phone's calendar too. An event is left out when it starts at the minute a todo of the same days does and
  bears its title, with or without the feed's "✓ " for done (`PhoneEvents.withoutKadans`). A whole-day event never is.
- **Where they show** (`ui/calendar/DayItems`): on Home's "Next 7 days" and in the Calendar tab's day, in one list with
  the todos: the events of the whole day first, then todos and events by their hour, the todo first at the same
  minute. A day with only events is listed. An event that crosses midnight is on both days ("22:00 – …", "… – 06:00");
  a whole day is the day the calendar says, whatever the phone's time zone. In the month, a day with events has a small
  ring where a todo is a full dot.
- **Never taken for a todo** (`PhoneEventCard`): outlined where a todo is filled, its calendar's colour down the side,
  its hours and its calendar's name under the title. Read-only: a tap opens it in the phone's calendar app, and Kadans
  changes nothing in it. A title is someone else's text (an invitation can come from anyone): plain text, cut, two
  lines at most.
- **Kept current** by the screens' own reloads: on opening, on coming back to the front (an event added in the
  calendar app meanwhile is there), and when the switch or the permission changes (`PhoneCalendar.changes`). A calendar
  that cannot be read never fails a screen of todos: it is shown without events.
- **Google Play**: `READ_CALENDAR` is a runtime permission with no declaration form of its own, and events read on the
  device and never sent are not "collected" in the Data safety form's sense. The owner's checklist says what to look at.

Checked on the emulator (Android 15) against a local API, with a calendar filled for the test: the offer on the Calendar
tab, Android's question, and the month showing the events at once after "Allow"; a meeting beside a todo, a whole-day
event first, a meeting after the todo of the same hour, a repeating event on each of its days, a night shift on both
its days; left out: a declined event, a cancelled one, one of a hidden calendar, and the copies of two todos as the
calendar feed would make them; Home's next seven days, light and dark; the switch off and on; the permission taken
away (todos alone, Settings saying what is missing), refused until Android no longer asks (the button opening the
system's settings) and given back there; an event added while Kadans was in the background; no event title in the
app's log or the server's. A tap starts the phone's calendar app on the event (seen in Android's log); the event's own
screen was not seen, because the emulator's Google Calendar app dies at start on this image. Not checked: a real
phone's calendars (Google, Samsung, a work account), other makers' calendar apps, the iPhone.

### Statements in, data out (2026-11-16)

Sixth and last of the integrations ranked after a tester's feedback, in two halves that belong together: what a bank
knows comes into the budget without being typed again, and what the person put in Kadans goes out as files they keep.
Desktop and Android; the iPhone's document picker comes with the iPhone app (the entries are not shown where the
device has no file dialog: `FilePorts.available`).

**A statement in** (Budget → "Import a statement", over the month's movements): CSV, OFX, QFX or camt.053. A PDF
is answered, not read: it holds a picture of the lines, not the lines, and each bank's would need a reader of its own.

- **Read on the device, never sent.** A statement holds more than its lines: an account number, a balance, other
  people's names. The app reads the file (`statement/`: pure code, tested with files as banks make them) and sends
  only the lines the person kept, as movements, when they press "Import". Until then nothing of the statement leaves
  the device, not a text, an amount or a key: to sort the lines, the app asks the server what it already holds and
  compares on the device (below). The server has no upload endpoint and never sees the file; the privacy policy says
  so ("What stays on your phone").
- **Files** come through the system's own dialog (`files/FilePorts`): Android's document picker, the computer's
  "Open" (AWT's `FileDialog`, shown from a background thread). One file, handed over by the person: no storage
  permission anywhere. Any type can be picked, since banks name a statement's type every which way; what a file is is
  told from what is in it (`Statements.kind`). A PDF and an Excel workbook are recognised and answered with what to do
  instead; 5 MB at most (never read past it), and 10,000 lines (a longer file is read that far, and the screen says
  the rest was left out).
- **CSV** has no standard: every bank invents its columns, so the file is guessed and the person confirms
  (`CsvFile`). The sign between cells is the one of `, ; tab |` that cuts the first lines into the same number of
  cells; the text is UTF-8, UTF-16 or the old Windows encoding (where a French bank's "é" is one byte). The table is
  the rows of the width whose rows most often hold both a date and an amount: the lines about the account before it
  and the total after it are counted as left out, never invented into movements, even over a statement of two lines.
  Its first row is a header when it holds neither a date nor an amount. Each column is then guessed
  (`CsvFile.guess`): its name proposes (English, French, Spanish, Kreyòl; the surest names first, a movement's own
  date before its value date, a description before a reference) and what it holds confirms (a "Date valeur" column
  holds dates, so it is never the amount, whatever "valeur" says). Where no name helps, the cells decide alone: the
  date is where most cells read as dates; two columns of amounts that share the rows between them are money out and
  money in; else the amount is the column that most looks like money (decimals, a sign), the first of them, a balance
  coming after; the text is where the letters are. The person corrects the guess on one screen with the first lines
  read that way under it: the date and how its numbers are ordered, the text, and the amount as one signed column or
  as money out and money in. A card's statement counts purchases above zero: one switch turns the signs. Dates that
  do not say their own order (every day before the 13th) are settled by the calendar when they can be (a statement
  holds what happened, so the order that puts a line in the future is wrong), else read day first, or month first for
  an account in English with a 12-hour clock, and the screen says to check.
- **OFX and QFX** (`Ofx`): both forms, the old one that closes no tag and the XML one. No columns to ask about. The
  file's own currency is read: one that differs from the account's is said on the review screen, not refused.
- **camt.053** (`Camt`), the ISO 20022 statement a bank's "CAMT" export follows (its relatives camt.052 and camt.054
  read the same way): each entry's amount with its currency, whether it is a credit or a debit, the day it was
  booked, the bank's reference, and in words who was paid (or who paid) and what the payment said. Only what is
  booked (an entry pending or yet to come is left out); an entry that gathers several transactions is one line, with
  the entry's own amount; a file with several accounts' statements gives the first account's lines and says so. It is
  read by its tags, as OFX is, a namespace's prefix allowed for: no XML machinery runs on a file from outside, so
  nothing in it is expanded, included or fetched, whatever it declares. Other XML, and a web page saved under a
  statement's name, are refused rather than read as a table.
- **Amounts** are read as banks write them (`StatementText.cents`: a currency around them, spaces or signs between
  thousands, a comma or a point, a minus in front or behind, brackets, CR and DR) into hundredths, never through a
  floating number, and booked in the account's currency. In a standard's file (OFX, camt) an amount is read as the
  standard writes it (`decimalCents`: no sign between thousands, so "1.250" is one and a quarter, where a bank's own
  CSV would mean 1,250). A line dated a day is booked that day at noon where the
  person is, as a movement recorded by hand is.
- **A line is booked once.** Each line carries a key that names it (`ImportKeys`, the server's
  `Transaction.ImportKey`, unique per account by a partial index): made from the bank's own id for the line when the
  file has one that can be trusted (OFX's `FITID`, a camt entry's reference), else from the line's day, amount and
  text and its rank among the lines of the file that read exactly the same (two coffees at one price on one day are
  two lines, and both are booked). A bank's id is not believed when it is short (a rank in the statement, starting
  again in the next one), a word that says there is none ("NOTPROVIDED"), or shared by two lines of the file: such an
  id would make another line look already booked, and that line would be lost, where a key made from what the line
  holds is never wrong about another line. No two lines of a file get the same key. Before the review the app
  asks for the keys the account already has (`GET /budget/transactions/import/known`, the newest 50,000) and looks its
  lines up in them: those lines are shown set aside, "Already in this account", and cannot be ticked. The import
  itself skips a key the account has (`POST /budget/transactions/import`), so the same statement twice, the next one
  that overlaps it, or a double tap adds nothing twice whatever the app believed.
- **The review** (`StatementImportScreen`, `StatementImportViewModel`): every line with its day, text and amount, a
  tick, and a category. A line that reads like one of the person's earlier movements comes with that movement's
  category: the same answer brings the person's own texts with their categories (same kind, same text, the latest
  5,000), matched on the device and checked against the categories the app has; choosing a
  category for a line gives it to the lines that read the same, unless the person says otherwise. Lines go 500 a
  request, each request booked whole or not at all (one line that cannot be booked stops its request); a request that
  fails stops there, what was booked is set aside, and trying again sends only the rest. Back goes one step at a time
  (`ui/BackSteps`), and does nothing while lines are being sent.
- **On the server** (`TransactionImportService`): income and expenses only, on an account that is not archived, with
  categories of the right kind; the limits of the categories touched are checked as for any expense, and the person's
  other devices hear `data.changed` like any other change to the budget. Nothing of a line is logged.
- **A line's text is somebody else's**: the bank's, and behind it a shop's or a sender's. It becomes a note as plain
  text, without control characters, cut to a note's length (`Statements.note`), and is shown as plain text.

Known limits, said rather than hidden: a transfer between two of the person's own accounts is in each statement as an
expense on one side and income on the other, and is imported so (untick it, or record the transfer instead); a line
whose text the bank rewrites between two exports (pending, then posted) has another key and shows as new, as does a
CSV file read a second time with another column for its text; so does a movement the person deleted after an import,
at the next statement that holds it; categories are suggested for a text
that is exactly the same, and many banks put a date or a number in theirs.

**Data out** (Settings → "My data", and from "Delete my account")

- **One zip** (`GET /users/me/export`, Identity's `DataExport`): every module hands over what it holds for the person
  through `IUserDataExporter` (SharedKernel), the counterpart of `IUserDataEraser`: what can be erased can be taken
  away first. `account.csv`; `budget/movements.csv`, `accounts.csv` (with balances), `categories.csv` (with limits),
  `recurring.csv`, `currencies.csv`; `todos/todos.ics` (every time done so far and a year ahead, as the calendar feed
  writes them: any calendar app opens it), `todos/todos.csv`, `todos/history.csv` (times done, cancelled or moved,
  with the person's notes and reasons); `focus/sessions.csv`, `focus/cycles.csv`. Never a password's hash, a token,
  a device or the calendar link: the service's workings are not what the person put in.
- **Files a person can read**: names instead of ids, days and times in the account's time zone, amounts with a point
  and two decimals, English headers in every language (for whatever reads the file next). CSV as a spreadsheet opens
  it (`SharedKernel/Text/Csv`: RFC 4180, UTF-8 with its mark so that Excel shows the accents).
- **A cell never runs.** A text that begins with `=`, `+`, `-` or `@` would be taken for a formula by a spreadsheet,
  and a note can be a line imported from a bank: `Csv.Cell` puts an apostrophe in front, which a spreadsheet shows as
  text. A plain negative number is left alone.
- **Free for every account**, with or without the phones' subscription: the data is the person's. Asked from the app
  as the signed-in person, limited to 10 a quarter hour per client (`RateLimitPolicies.Export`: a whole account in
  one answer is heavy), built in memory for the one request and not kept; the log says who exported, how many files
  and how many bytes, nothing else. The largest files are capped at their newest (20,000 calendar times, 50,000 lines
  of history, 20,000 sessions).
- **Saved where the person says** (`DataExportViewModel`): the copy is fetched first and the system's "Save" dialog
  asked second, so a place is never left holding an empty file; the file is named by the app with the day
  (`kadans-export-2026-11-16.zip`), never by what a server answers. On a phone behind the paywall the app shows
  nothing but the paywall: the free desktop app exports.

Checked: the reader with files as banks make them (a French bank's with two columns of money and lines around the
table in the old Windows encoding, an American one with one signed amount and quoted commas, a file without a header, a
card's, a value date beside the date, Kreyòl headers, two money columns without a header, two movements under six
lines about the account, dates of both orders, OFX in both forms, camt.053 in two versions with a pending entry, a
batch and two accounts, a file longer than what is read), the keys (a bank's id repeated, or too short to be one), the view model against
a mock server (the three steps, nothing of the statement in any request before "Import", 1,200 lines in three
requests with the second failing once, a failed check), the desktop's file reading and saving,
and both of its dialogs shown and closed on Linux; `budget_flows.py` against a local API (a statement imported, again,
overlapped, on two accounts, its refusals; the export's eleven files, a formula defused); and on the emulator (Android
15) the whole way: Android's picker, a French statement read with its accents and both columns, a category given to a
line and its twin, six lines booked and the account's balance equal to the statement's closing balance, the same file
again with every line set aside, a follow-up statement that overlaps the first (its new line arriving with the
category its twin was given), Back step by step, a PDF answered, an OFX file in dollars into a gourde account, a
card's statement with its switch, in English and in French; then "My data", Android's "Save" dialog, and the zip
pulled back and opened. Not checked: a real bank's export (the samples are written after their formats, camt.053
included, which was not run on the emulator: its path is OFX's, and tested as such), the desktop app's import screens
on Windows and macOS, a statement of thousands of lines on a slow phone, the iPhone.

### The session, sealed on the device (2026-11-17)

Signing in leaves two tokens on the device: a short-lived one that each request carries and a long-lived one that
gets the next pair (it is replaced at each use, and the server forgets the old one). Whoever holds the second is
signed in. Until 1.1.1 the app kept both in its plain settings: on Android a file only the app reads, but which
Android copied into the phone's backup, to Google's cloud and to the next phone; on a computer a file in the person's
profile (Linux) or a registry value (Windows) that anything able to read the profile reads: a backup of the home
folder, the disk in another machine.

Now the settings hold what the device made of the two tokens, never the tokens. What is sealed opens on that device,
for that person, and nowhere else.

- **One store, a sealer per platform.** `auth/SealedTokenStore` is the app's `TokenStore`: it formats the pair, hands
  it to `auth/Sealer` and keeps the result (`kadans.session.sealed`, Base64 behind a layout number) in the settings.
  It reads once a run and keeps the pair in memory: the reminders, the widget and the quiet mode look at the session
  all the time, and none of those looks asks the device's protection again. A sealer never throws; it answers what the
  store may do: sealed, nothing to seal with here (`None`), or protection that is there and did not answer
  (`Refused`); opened, will never open (`Lost`), or could not be asked now (`Refused`).
- **An update signs nobody out.** A session an earlier version left in the clear is sealed the first time it is read,
  and the clear copy removed in the same step.
- **Android: the Keystore** (`KeystoreSealer`). An AES-256-GCM key made on the phone, in the Keystore (the phone's
  secure hardware where it has one), which uses it for the app and never hands it over: it can be neither read nor
  copied, so a sealed session copied to another phone opens nothing. The key asks for no unlock of its own, because
  the alarm that rings a reminder, the widget and a push read the session with the screen off; the phone's storage is
  encrypted until the first unlock anyway, and Kadans does not run before it. A key that stopped working (a system
  update can do that) is replaced at the next sign-in. A phone whose Keystore makes no key at all keeps the session in
  the app's private settings as before: an app that signs out at every start serves nobody, and that file is out of
  other apps' reach and, now, of the backup's.
- **Android: nothing in the backup.** `android:allowBackup="false"` and `res/xml/data_extraction_rules.xml` (Android
  12 and later, cloud and phone-to-phone alike) keep everything of Kadans out. Not only the session: the id this
  installation registered for push was copied too, and two phones with one id take each other's notifications. What
  a phone keeps is that phone's; the account is on the server, and signing in on the new phone brings all of it.
- **Windows: its own data protection** (`WindowsSealer`: DPAPI, `CryptProtectData`, called through Java's bridge to
  native code like `LinuxInputMethod`). Windows encrypts with a key it derives from the person's sign-in: it opens
  for that person on that computer, not for another account, not from a copy of the registry. No window, no
  question. Kadans mixes a purpose of its own into the sealing, so that what another program sealed for the same
  person does not open as a session.
- **Linux: the keyring** (`KeyringSealer`, `SecretService`: the freedesktop Secret Service over D-Bus, which GNOME
  Keyring, KWallet and KeePassXC implement). The keyring keeps one random key, named "Kadans", encrypted with the
  person's login password like everything it holds; Kadans seals the session with that key (AES-256-GCM) and keeps
  the result in its settings, the way browsers keep their own secrets on Linux. The key is read once a run, so a new
  pair of tokens every few minutes asks the keyring nothing. On the bus the key travels sealed for that one
  conversation (Diffie-Hellman, then AES: `dh-ietf1024-sha256-aes128-cbc-pkcs7`, what libsecret speaks); a keyring
  that does not know that exchange gets it as it is, as the desktop's own tools do.
  - *No keyring* (no session bus, nobody behind `org.freedesktop.secrets`): the session stays in the settings file,
    which only the person's account reads, as it did.
  - *A locked keyring* asks the person for its password itself, in the desktop's own window; Kadans never sees what is
    typed. Closed without an answer, or unanswered for two minutes: nothing is written in the clear, the sealed
    session stays where it is, and the sign-in screen says that the device's secure storage did not open, with
    "Retry" (`SealedTokenStore.waiting`, `retry`). Kadans does not ask twice by itself in one run. Signing in instead
    works, for as long as the app runs.
  - The key stays in the keyring after a sign-out and after Kadans is removed. It opens nothing by then: the sealed
    session goes with the sign-out.
- **A session that no longer opens is a sign-out** (`onLost`): what the device kept for that account (the reminders
  to ring, the widget's list, the quiet mode) is forgotten as it is when the person signs out, and so it is when
  someone signs in over a session that never opened.
- **Not yet: the Mac and the iPhone** (`NoSealer` there: the session in the settings, as before). Neither is released,
  and each needs a Mac to build and to try; the Keychain is part of releasing either.

What this protects, and what it does not. A copy of the app's settings no longer signs in: a phone's backup, a
computer's backup, a disk read elsewhere, another account on the computer. A program that runs as the person, on
their unlocked device, can still ask the keyring or Windows for what Kadans asks: no desktop app is protected from
that, and the server's side answers it (the long-lived token is replaced at each use, "Sign out everywhere" ends
every session at once). The rest of what the settings hold (the reminders to ring, the widget's list, the language)
is the device's own copy of what the server has, not a way in, and is not sealed.

Never write a token, or anything else that signs in, into `Settings` in the clear; a new secret the app must keep
goes through `Sealer`.

Checked: the store's rules with a stand-in sealer (sealed and found again, the clear copy removed, a value changed or
from elsewhere dropped as a sign-out, nothing in the clear when the protection refuses, the wait and "Retry"); the
keyring's part against a stand-in keyring on a private bus (the key made once, the exchange, a keyring opened by the
person, left locked, never answered, absent) and against GNOME Keyring 46 itself, run privately so that nobody's own
keyring is touched (the exchange, the key kept and handed back, a locked keyring refusing); the app's own wiring on
Linux against that private keyring and a local API (the first start after the update, the next start, a locked
keyring and "Retry", the keyring open again, a sign-out); on the emulator (Android 15): the settings after a sign-in
(no token in them), the session back after the app was stopped, a 1.1.1 session sealed at the first start, one
character of the sealed value changed (signed out, the value gone), a reminder fetched and its alarm set by the
background job with the app killed, Android refusing to back the app up, as a cloud backup and as a transfer to a new
phone; the sign-in screen's note drawn in the three languages at a phone's and a computer's width. Windows' part
runs on a real Windows in the release workflow (`WindowsSealerTests`: sealed and opened, a value changed, something
another program sealed), not on this side. Not checked: Windows by hand, KWallet and KeePassXC, a real keyring's
password window answered by a person (the stand-in plays that part), a phone whose Keystore fails, the Mac, the
iPhone.

### Client: Compose Multiplatform

Already started (`clients/app`). Covers Android, iOS and desktop (Windows/macOS/Linux) from one
codebase, which matches the requirement of reliable background timers + OS notifications on
desktop and real push on mobile. Web is a possible later bonus (Wasm target).

**Light and dark** (2026-11-05). The app follows the system's dark mode, and Settings → Appearance (System, Light,
Dark, per device: `AppearancePreference`) overrides it. `KadansTheme` resolves it once into `LocalDarkTheme`, which every
screen asks; none calls `isSystemInDarkTheme()` itself. The system's answer comes from Compose on Android, iOS, Windows
and macOS; on Linux, where Compose has none and always says light, from the desktop's settings portal over D-Bus
(`desktop/LinuxColorScheme`: `org.freedesktop.appearance` `color-scheme`, read at start and followed through its
`SettingChanged` signal, so switching COSMIC to dark switches Kadans at once). On Android the status and navigation bars'
icons follow the app's choice, not the system's (`SystemBarsFollow`). The QR code stays dark on white for the camera.

**Navigation** (approved 2026-11-04 on a design canvas). Four destinations, one tap from each other: Home,
Calendar, Budget and Focus (the focus stats and the Pomodoro cycles, one switch apart). `ui/shell/MainShell` frames
them. On a phone: the destination's name, the bell (its unread count shared by every destination,
`UnreadNotifications`) and Settings on top, the destinations in a bar at the bottom, and + floating at the bottom
right of the list on Home (a todo) and Budget (a movement). In a window 600 dp wide or more (a computer, a tablet):
the destinations in a column on the left, each with its name, the bell and Settings at its foot, and + as a button
that says what it adds ("New todo") at the top right. Home, Budget, a todo's screen and the forms use a wide window's
width in two columns; the other lists keep a readable width, centred (`ReadableList`). What the frame and the screens
look like is "The look", below. Home stays at the
bottom of the back stack and another destination sits alone on it (`selectTab`), so Back from a destination leads
Home and Back from Home leaves the app; everything else (a todo, a form, the notifications, Settings) opens over the
frame with its own back arrow. Settings' main page holds the account with Sign out, the language, the time format,
the time zone and what belongs to the device; Profile, Email and password, Two-factor, Devices (with "Sign out
everywhere") and Delete my account are pages of their own. The icons are drawn in `KadansIcons` (no icon library).

### The look (2026-11-24)

The closed test's testers found the app plain: it wore Material 3's stock colours, type and shapes. The owner chose
between two directions drawn on a design canvas, phone and computer side by side, and took the warm one: a cream
page, white rounded cards with a soft shadow, the logo's purple for what is pressed or chosen, rounder letters, and
one large thing to look at on each screen (the day's ring, the countdown's ring, the balance). Focus is purple,
because the logo is a purple clock; a break is green. Nothing was copied from another app.

- **The palette** (`ui/Look.kt`). Two Material colour schemes, `KadansLight` and `KadansDark`, so every Material
  component follows without being told, bent on purpose in two places: every `surfaceContainer*` is the card's colour
  (white by day), so a card, a dialog, a menu and the bar are the same white on the cream page; and
  `secondaryContainer` is the purple itself, so whatever Material draws as "selected" is solid purple, not a tint.
  What Material has no role for is `KadansColors` (`LocalKadansColors`): done (green), a break's and a coming
  phase's share of the strip, the tracks under rings and bars, a limit that is near (amber), "pending", money out,
  and the balance card's purple, which by night is a deeper one: the scheme's primary is light there, and a card
  filled with it would be the brightest thing on the screen. A colour is never written in a screen: it comes from
  `MaterialTheme.colorScheme` or `LocalKadansColors`. Two things outside Compose repeat the palette by hand, with a
  comment saying so: the Android widget's `res/values/widget.xml` and `values-night` (a widget follows the system's
  theme, not the app's setting), and the logo's purple in `branding/`.
- **The type.** Baloo 2 for titles and numbers, Nunito for everything else (`kadansTypography()`), both under the
  SIL Open Font License, which allows shipping them in an app: six static files in `composeResources/font` (about
  1.3 MB; Android 7 reads no variable font), their licence texts beside them in `composeResources/files/licenses`.
  They hold the four languages' letters, Kreyòl's "ò" and Spanish's "ñ" included. A countdown is written in digits
  of one width (`tnum`) so that it does not shake as it counts.
- **The pieces** (`ui/Kit.kt`, `ui/Fields.kt`, `ui/Forms.kt`): what a screen is built from, so that a card or a
  field is drawn once. `KadansCard`, `ProgressRing`, `TickCircle`, `Tag`, `SectionHeader`, `CircleButton`,
  `BigButton` (a screen's main action), `SecondaryButton`, `ProgressLine`, and `BackButton` with `PageHeader` (the
  way back, below); `KadansField`, the one text field (white, round, its
  label inside, a faint line that turns purple while it is written in; Material's own are see-through or
  underlined, and their colours are not the theme's to set); and for forms `FormPage` (the title, the round button
  that leaves, one column or two), `FormActions`, `Segmented` (two or three choices in one white pill), `ChoiceChip`
  in `Choices` (a pill each, white until chosen), `SwitchCard`, `StepButton`, `RuleWords`. A screen takes these
  before it takes Material's `OutlinedTextField`, `FilterChip`, `OutlinedButton` or segmented buttons, which are no
  longer used. On a white card or in a dialog a pill is the page's colour instead (`onCard`), or it would not show.
- **Home says how the day is going.** A ring and "2 of 4 done" with what comes next (`home/TodayProgress`, pure),
  over the day's todos, each with a circle that marks it done, the done ones struck through and kept in the count.
  Two things that took: the circle waits five seconds before it sends, with "Undo" on the snackbar (`home/Ticks`),
  as the home-screen widget's circle does, because a circle is pressed by mistake and the server has no "not done
  after all"; ticks still waiting when the screen goes away are sent then, not lost. And the list of occurrences
  left out everything done (a one-time todo completes with its only occurrence), so the count fell as it rose:
  `GET /occurrences?completed=true` now returns the completed ones of the range too. The default is unchanged, so an
  older app, the widget and the calendar feed see what they saw.
- **A focus session** is a ring that fills around the time left, purple for focus and green for a break, over the
  cycle as a strip, one segment a phase and as wide as the phase is long (`pomodoro/PhaseStrip`, pure; a session
  that repeats shows the cycle it is in). The todo's name is on the screen, read once when it opens.
- **Budget** opens on the balance, large on purple, with the month's money in and out; accounts as tiles, each
  kind with its picture; a category's limit as a bar that is green, amber from 80% and red from 100%, the alerts'
  own thresholds. Its own bars, not Material's: `LinearProgressIndicator`'s track takes `secondaryContainer`, which
  is the purple here.
- **A computer is not a wide phone.** From 600 dp the frame is a column of named destinations; from 720 dp Home and
  Budget are two columns, from 840 dp (`WideWindow`) a todo's screen and the forms too, and a session puts the ring
  beside its buttons. Pushed screens stay full pages with their way back. The desktop window used to open 480 dp
  wide, the phone's layout on a computer screen: it opens at 1180 by 800 where the screen has room and at what fits
  where it has not (`desktop/FirstWindow`), and can still be narrowed down to the phone's layout.
- **The way back is one button.** A page opened over the frame (Settings and its pages, the notifications, the
  statement import, a todo, a session) goes back by `BackButton`: the arrow and the word "Back" in one white pill,
  pressed anywhere on it. Where the page has a name, `PageHeader` puts it in the middle of the page, away from the
  button. Settings used to show a bare arrow with its title beside it, which read as one thing: the owner pressed
  "Settings" to go back and nothing happened (2026-11-24). A form is left by its round cross instead: it is given
  up, not gone back from.
- **A server out of reach is said on the sign-in screen at once** (the owner's remark, 2026-11-24). The screen
  already asked the server which sign-ins it offers, and with no answer it only left "Continue with Google" out,
  which told nobody anything. The same question now says whether the server is there, on every platform
  (`LoginUiState.serverUnreachable`): "Could not reach the server" with "Retry" at the top of the form, before
  anything is typed. It keeps asking by itself, less and less often, and the first answer takes the notice back.
  An answer that is a refusal (busy, too many requests) is a server that is there.
- **Dark** is the same design on a purple-tinted night: the page darker than its cards, the primary a light purple
  with dark writing on it. `LocalDarkTheme` decides, as before ("Light and dark", above).

Checked on the emulator (Android 15) with a seeded demo account against a local API, at a phone's size (411 dp) and
at a computer's (1280 by 800 dp, the same code the desktop app runs), by day and by night: Home with a todo ticked,
taken back, and ticked for good (the server then says Completed); a session in focus and in a break; Budget and one
of its dialogs; a todo's screen; the three forms (a new todo with a weekly and a monthly rule, a new movement with
its amount, a category and a rule that ends on a date, a Pomodoro cycle) and their date picker; Settings, the
calendar and the focus stats. In Spanish and in French on a narrow phone (360 dp): Home, Settings, Budget and the two
forms, which found three things, all fixed: "Sign out" beside the address left it four letters (the button goes
under it on a narrow phone), "Notifications" was cut by its count in the column of destinations (the count sits on
the bell), and "Concentration" reached the edge of the bottom bar (a label gets smaller before it is cut). The
owner then looked at the desktop app on Linux (2026-11-24: "really better"), which brought the two remarks above, the
way back and the sign-in screen, both checked on the emulator afterwards (the notice with the app pointed at an
address where nothing listens, "Retry", and the notice gone once the address was right again). The tests
cover what is pure: the day's count and what is next, the tick and its undo, the strip, the window's size; and
`task_flows.py` what `completed=true` returns; and the sign-in screen's notice (told at once, taken back by an
answer, "Retry", a refusal that is not "out of reach"). Not checked: the desktop app on Windows (its tray menu, the
fonts as Windows draws them), Kreyòl in the new layouts, a screen reader's reading of the new pieces, the statement
import's pages, the paywall and the sign-in screens beyond a glance, the Android widget with its new colours on a
home screen, and the iPhone.

### The desktop app: in the tray, once

Closing the window hides it: the timer that rings reminders, the live connection and a running focus session go on, and
the tray icon brings the window back ("Open Kadans") or quits. The menu follows the app's language.

- **Linux: the freedesktop tray.** A StatusNotifierItem on the session bus, its menu over `com.canonical.dbusmenu`
  (`tray/StatusNotifierTray`). That is what COSMIC, KDE Plasma and GNOME with the AppIndicator extension (Ubuntu's
  default) show. AWT's `SystemTray` speaks only the older XEmbed protocol, which COSMIC does not host at all: there it
  reported no tray, and closing the window quit Kadans (found on Pop!_OS 24.04, 2026-10-09). D-Bus comes from
  dbus-java (MIT, pure Java over the JDK's own Unix sockets, no native code; JetBrains Toolbox shows its tray the same
  way). The icon registers again when the panel restarts. Without a host showing items, Kadans uses AWT's tray, and
  without any tray the close button quits, as before, so the app is never left running invisible.
- **Elsewhere: AWT's tray**, which is the system's own on Windows and macOS. Notification balloons use that same icon
  (they used to add a second one). macOS: clicking the Dock icon shows the window again (untested: no Mac yet).
- **Windows: Kadans' own menu** (`tray/WindowsTray`, `tray/TrayMenu`; the design approved on 2026-11-12). AWT's menu is
  Windows' old classic one (small type, thin lines, never dark) and cannot be styled, so the right-click opens a menu
  Kadans draws: rounded corners, a soft shadow, 40 dp rows with an icon each (the Kadans clock, "Open Kadans"; a
  separator; a power icon, "Quit"), light or dark as the app, in its language. It is a window of its own (a dialog: no
  taskbar button; no frame, a transparent background for the shadow, above the others), opened above the click and to
  its left (below or to the right next to a taskbar at the top or the left), always whole on the screen
  (`TrayMenuLayout.place`), and it closes when it loses focus, on Esc, or once a row is taken; the arrows pick a row.
  The icon itself is AWT's, created by Kadans rather than Compose's `Tray` (which offers no right-click of its own), so
  the balloons still find it, and a double-click still opens the window.
- **One Kadans per user** (`desktop/SingleInstance`): a lock file the system releases however the process ends, and a
  Unix socket beside it, in a folder only the user can open (`$XDG_RUNTIME_DIR/kadans` on Linux). A second start, such
  as the launcher clicked while Kadans sits in the tray, connects, the first shows its window, and the second exits.
  Without it, a second copy would hold a second live connection and ring every reminder twice. Dev and installed builds
  share their settings, so they share the lock. Nothing about it can stop Kadans from starting: no usable folder, no
  lock.
- **Any network, a company's too** (`desktop/DesktopNetwork`). Browsers and most programs follow the computer's proxy
  setting and trust the certificate authorities the computer trusts; Java does neither by itself. Behind a company proxy
  that blocks direct connections, the Store's copy reached nothing while the browser worked: no Google button, no
  sign-in (the owner's first Windows test, 2026-11-11). First thing in `main`, `java.net.useSystemProxies` makes Java
  ask the system for the proxy of each address (Windows: Internet Options, PAC scripts included; Linux: GNOME's
  setting), and every desktop HTTP client (`platformHttpEngine`, the update check) uses an OkHttp engine that trusts
  Java's authorities and, on Windows, the computer's as well (`Windows-ROOT`, `jdk.crypto.mscapi` in that runtime
  image only): a proxy that inspects secure connections signs them with an authority its IT adds to Windows, never to
  Java. A server is trusted when either list vouches for it, as in the browser, and OkHttp checks the hostname either
  way. Its sockets never pick a proxy themselves (`DirectSockets`): on Windows, Java hands the one proxy set for every
  protocol to plain sockets as SOCKS, and the first build greeted the company's HTTP proxy in SOCKS ("Malformed reply
  from SOCKS server", which `--network-check` showed); OkHttp alone picks the proxy and speaks HTTP's `CONNECT` to it. A proxy that asks for a login (407) is not handled. Phones need none of it: Android and iOS do both themselves.
  When the desktop still reaches nothing, `Kadans --network-check` (`desktop/NetworkCheck`, before anything else starts)
  writes `kadans-network-check.txt` in the home folder: the proxy Java picks, the authorities found, each step of one
  request to `/health/live` and the certificates it was shown. No account data. On Windows:
  `Start-Process "$env:LOCALAPPDATA\Kadans\Kadans.exe" -ArgumentList "--network-check" -Wait`.
- **The installed app** (`packageDeb`): the runtime image adds `jdk.security.auth` and `jdk.net` (dbus-java's login and
  socket options). The main class is `kadans.Kadans`, so the window's X11 class is `kadans-Kadans`, the name of the
  launcher jpackage installs (it writes no `StartupWMClass`): that is how docks match the window to its icon.
- **Opening with the session** (`startup/StartAtLogin`), so reminders ring after a restart without opening Kadans.
  Installed builds only: jpackage's launcher names itself in `jpackage.app-path`, and a Gradle run has no launcher a
  session could start. The session starts it with `--background`: hidden in the tray, or minimized without one; a
  Kadans already running is left as it is (a quiet `SingleInstance` claim). Linux: `~/.config/autostart/kadans.desktop`,
  the way JetBrains Toolbox starts on COSMIC, with `TryExec` so an uninstalled Kadans is skipped, and an entry hidden in
  the desktop's settings (`Hidden=true`) reads as off. Windows: a value under `HKCU\…\Run`, imported from a .reg file so
  the quoted path survives, and read as off when Windows' own startup settings turned it off (not run on Windows yet).
  macOS: with the Mac build. The first run of an installed app turns it on (Settings → This computer turns it off);
  later runs rewrite an entry still there, for the app may have moved, and never bring back one the person removed.
  Phones need none of it: Android puts the next reminder's alarm back after a reboot itself.
- Accents (Linux): Java combines a dead key with its letter (AltGr+` then e: è) only through the X input method the
  session names in XMODIFIERS. Pop!_OS and Ubuntu name IBus (`@im=ibus`), which COSMIC does not start: Java then has no
  input method at all, and no è, ê or à can be typed (GTK and Qt combine them by themselves, so only Java apps show it).
  First thing in `main`, `LinuxInputMethod` asks Xlib whether the named input method opens and, when it does not, runs
  Kadans with Xlib's own (`@im=none`, the locale's Compose table) by setting XMODIFIERS through libc before AWT reads it
  (`XToolkit.init` calls `XSetLocaleModifiers("")`). A running IBus or Fcitx is kept. The choice lasts until Kadans quits,
  so a start at sign-in, which may come before the session's input method, waits for it up to 15 s (its window is
  hidden then) before doing without: someone typing Chinese through IBus keeps it. The calls go through Java's
  foreign-function API, so the packaged app runs with `--enable-native-access=ALL-UNNAMED`. Windows and macOS combine
  dead keys in the system; phones' keyboards do it themselves.
- **Updates** (2026-10-31). The installed app (never a Gradle run) asks `https://kadansplanning.com/desktop/latest.json`
  a minute after it starts and then once a day; nginx sends that address to the latest GitHub release's `latest.json`
  (DEPLOYMENT → The website). It reads `version`, `minimumVersion` (older apps no longer work with the server) and
  `notes` by language (`updates/AppUpdates`). A newer version is announced once, by a notification whose click (or its
  "Download" button on Linux) opens the site's download page; Settings → This computer shows the app's version and the
  newer one for as long as it is out; one older than `minimumVersion` also gets a dialog at each start
  (`UpdateRequiredDialog`). The app never downloads or installs anything itself, and the request is its own client's,
  never the API's: no token, nothing about the person. Offline, a 404 (before the first release) or a file that is not
  latest.json changes nothing. Phones are updated by their stores and never ask, nor the Microsoft Store's package
  (below). Every app reports one version,
  `kadans.version` in `clients/app/gradle.properties` (Android's `versionName`, the desktop package, `BuildConfig`).
- **The Microsoft Store's package** (2026-11-04). The same app folder as the `.msi`, packaged as MSIX for the Store
  (`tools/release/msix.py`, DEPLOYMENT → Releasing the desktop app), which signs, hosts and updates it. Its launcher
  carries `-Dkadans.distribution=msstore` (`desktop/Distribution`): the app then never checks for updates, and has no
  start-at-login switch, for the package opens it with the session itself: a startup task, on from the first launch
  like the other builds' entry and turned off in Windows' Settings → Apps → Startup, which starts a second launcher,
  `KadansBackground.exe`, whose `.cfg` passes `--background` (a startup task passes no arguments). Inside a package,
  a Run key the app wrote would stay in the package's private registry and never reach Windows.

Checked on COSMIC (Pop!_OS 24.04), from Gradle and from the packaged runtime: the icon in the panel, close hides, the
icon's click and the menu bring the window back, Quit ends the app and removes the icon, and a second start shows the
first. Opening with the session, from the packaged runtime: the first run writes the entry, the entry's own command
starts Kadans hidden with its tray icon, a second such start leaves it alone, an entry removed stays removed and a stale
one is rewritten. `StatusNotifierTrayTests` replays the tray against a private bus (dbus-java's own daemon and a fake
panel), so CI needs no desktop.

## Domain designs

### Recurrence (SharedKernel – used by Tasks and by Budget periods / recurring transactions)

- Rule stored as an **RFC 5545 RRULE string + IANA timezone**, expanded with **Ical.Net** –
  implemented as `Kadans.SharedKernel.Recurrence.RecurrenceSchedule` (pure value object;
  `RecurrenceSpec` is the structured input clients send, so nobody hand-writes RRULE strings).
  The Tasks entity `RecurrenceRule` is only a persistence wrapper around it.
  Semantics follow the RFC: omitted BY-parts come from the start date; `BYMONTHDAY=31` skips
  short months (use `-1` for "last day"); `COUNT` bounds the generated set and exceptions
  remove from it; `UNTIL` is stored in UTC. Wall-clock times are interpreted in the rule's
  time zone, so "09:00 daily" crosses DST correctly.
- **Hourly, minute and one-time rules are expanded in UTC.** Hourly and minute rules count elapsed
  time, and Ical.Net gives those instants in local time too, except across an autumn DST change
  (the hour that happens twice), where 5.2.3 never returns. A one-time rule is exactly its start
  instant, which local time cannot always name (01:30 twice on a fall-back night). Daily and slower
  rules stay in local time. So hourly and minute rules take no hour, day or month parts; the app's
  "N times a day" is a daily rule with an hour list.
- **Limits.** A new rule repeats at most 5,000 times, ends within 10 years, and fires at most 288
  times a day (every 5 minutes). Stored rules are trusted as they are. `GetOccurrences` stops at a
  limit (the caller passes what it keeps, never above 10,000), so a wide window costs what it
  returns. Whether a bounded rule is finished is "no instance after the horizon", never a walk to
  its last instance. A calendar request covers at most a year. Ical.Net replays hourly and minute
  rules from their start (about 0.5 ms per day of age at every 5 minutes) but fast-forwards daily
  and slower ones. If dense rules ever become common, re-anchor their start.
- **The app builds rules the way calendar apps offer them** (the create-todo form; `RulePattern`, `RuleSummary`).
  Weekly: the days (BYDAY). Monthly: by date, several days and the last one (BYMONTHDAY, -1), or by day of the week,
  "the first … fifth / last" Monday … Sunday, day, weekday or weekend day (BYDAY + BYSETPOS, Apple Calendar's set).
  Yearly: the months (BYMONTH), then either. Every choice starts from the date picked, and the start sent is the first
  day the rule falls on from it; a rule falling on no day within its ten years is not sent. One choice is rewritten:
  "the second Sunday" in several months is a MONTHLY rule kept to those months, because a YEARLY BYSETPOS counts
  across all the year's Sundays; it can only repeat every year (the contract takes no ordinal BYDAY such as `2SU`).
  The app only looks for that first day; the server expands everything. Budget rules take the same day parts
  (`BudgetRecurrence`, without times of day); the budget form keeps within the same limits (`RuleLimits`: 5,000 times, ten
  years) and the budget's own (a repeating movement starts at most a year ago; amounts keep two decimals, under a
  trillion).
- **Materialized occurrences with a rolling horizon**: a scheduled job guarantees every active
  rule has occurrences generated through `now + 30 days`. Past and near future = table (truth);
  far future = computed preview only.
- **Per-occurrence overrides**: cancel / reschedule / complete act only on occurrence rows
  (`Status`, `ScheduledAt` vs `OriginalScheduledAt`, `RescheduledAt`, `CompletedAt`, `CancelledAt`,
  reasons, `Remarks`, `NotifiedAt`). Changing the rule regenerates untouched future rows and keeps
  touched ones. This is the Google Calendar model. The app's edit screen sends a new rule only once it is changed,
  starting at the next untouched occurrence's first time still ahead (today's later times stay), and a counted rule
  keeps the times it had left (`remaining`, computed from the rule by `GET /todos/{id}`: occurrence rows age out). (Implemented in Phase 3: `OccurrencePlanner` is the
  pure part, `OccurrenceGenerator` writes rows, `OccurrenceHorizonJob` keeps every active todo ahead of
  `Tasks:OccurrenceHorizonDays`; `Todo.OccurrencesGeneratedThrough` records progress, `MaxValue` meaning a
  bounded rule is exhausted.)
- **Notes**: one per occurrence, "how it went" (`TodoOccurrence.Remarks`, the app's "note": `PUT
  /occurrences/{id}/remark`), on any status, at most 4,000 characters (`SetNote`; blank removes it). The app shows it
  under the occurrence, its history included, so a recurring todo's history reads as a journal. A note makes the row
  touched (kept by a rule change and by retention) and moves no reminders version: it changes nothing a phone rings.
  A todo's own text is its description: the todo-level remarks (a list nothing showed) were removed on 2026-10-15
  (migration `DropTodoRemarks`).

### Pomodoro (Tasks module)

- Server-authoritative run state (already the case). Clients are countdowns.
- Implemented (Phase 5): absolute `PhaseEndsAt` while active, `PausedRemaining` while paused,
  resume re-anchors (`PhaseEndsAt = now + remaining`). Clients count down to a timestamp, which
  survives reconnects and multiple devices. `ExpectedPhaseIndex` optimistic concurrency stays.
  Auto-advance is per-run opt-in: `PomodoroDeadlineWatcher` (a hosted service, not a polling job)
  sleeps until the exact moment the nearest hands-free phase ends, then `PomodoroAutoAdvancer` steps
  overdue runs phase by phase on their own schedule and notifies through the normal pipeline. Every
  mutation pulses the watcher so it re-aims at the new nearest deadline; its fallback sleep
  (`Tasks:PomodoroAutoAdvanceSeconds`) and the idempotent scan make restarts and missed pulses harmless.
- A watching client advances its own run at the same instant, so `PomodoroRun` carries Postgres `xmin`
  as an optimistic row version: exactly one writer wins, the loser gets "refresh and retry" (a request)
  or silently skips (the watcher). One phase change, one notification, one appended lap.
- The server's clock decides when a phase has ended (Phase 8). The app's "this phase ran out" is an advance
  with `onlyIfEnded`: it counts only for a hands-free run whose deadline the server has also reached, and
  otherwise the run comes back unchanged, unsaved and unannounced. A device whose clock runs fast cannot cut
  phases short. "Next phase" is a plain advance and skips at once.
- Manual runs wait at the end of each phase for the person, and say so once: the watcher also wakes at
  their phase ends, and `PomodoroTimeUp` sends a "time's up" naming what comes next.
  `TimeUpPhaseIndex` makes it once per phase, so pausing and resuming at 0:00 does not repeat it. A phase
  that ran out more than 15 minutes earlier is marked without a notification: the server was down, or the
  run was left at 0:00.
- Hands-free is the default for a new session, and each device remembers the last choice.
- **A hands-free cycle waits for the person at its end** (2026-10-31, the owner's design, after trying the other way
  round: a session that goes on by itself, forgotten, fills the stats with focus nobody did and notifies until evening;
  one that stops costs a working person one tap per cycle, at the end of the long break, where the decision is anyway).
  When the last phase of a cycle runs out by itself (the watcher, or a watching app's "ran out"), the next lap is added
  but not begun: the session is paused before its first phase (`PomodoroRun.IsBetweenLaps`: paused, the current phase
  never started; `BetweenLaps` in the response). One notification says "Cycle 1 done. Another one? The session waits
  for you." (`PomodoroAutoAdvancer.PhaseMessage`, `CycleDone`; its data carries `cycleDone` and the session's
  `occurrenceId`). The apps give it two buttons (`pomodoro/CycleActions`): "Another cycle" resumes, which starts that
  phase then, so a longer break costs nothing; "Done" finishes the session and marks its occurrence done, as a
  reminder's "Done" does (either already so counts, so pressing again is safe). Android builds it from its push
  (`PomodoroCycleNotices`, `PomodoroActionReceiver`; one per session, a failed button says Kadans could not be
  reached), the Linux desktop shows it over D-Bus (`DesktopCycleNotices`), a tap opens the session, whose screen asks
  the same (Another cycle, Finish). The push is `NotificationMessage.ShownByApp`: data at high priority to a phone that
  rings its reminders (an app that builds its notifications); any other device gets it as a notification the system
  shows, without the buttons. Neither button: nothing more counts and nothing more notifies; the session ends at its end
  (below) as it ended, with its last cycle, silently (it asked already), and its occurrence stays pending: never marked
  done on a guess. Pressing Next on the last phase goes straight on (the person is there); manual runs wait at every
  phase anyway.
- **A session is for one occurrence, and ends when the next one starts** (2026-10-31; the owner's question: a forgotten
  looping session, and the todo's next occurrence arrives). Before, the next occurrence's reminders stayed quiet ("a
  session runs"), it stayed pending unmentioned, starting a session for it was refused, and the stats kept counting until
  the end time. Now the session's end is the earlier of its end time and the start of the next pending occurrence of its
  todo after it began (`SessionOccurrence.NextStartAsync`, `PomodoroRun.EndsAt`): it ends there as completed, its focus
  counting up to then, and asks "Done with…?" about its own occurrence, never marking it. The next one rings as usual,
  heads-up and "starts now" with "Start focus" (a session quiets its own occurrence only), so someone really still at
  work taps once and each occurrence keeps its session. No "session ended" on top of that "starts now"; one whose next
  occurrence has no reminders says "The session ended at 14:00, as the next one began." The end is computed, not stored:
  an occurrence moved, added or done meanwhile moves it, and the response's `FinishAt` is that end, with
  `EndsWithNextOccurrence` ("Ends at 14:00, when the next one starts"). Starting a session ends one that is due first, so
  a "Start focus" tapped before the watcher's pass is not refused. The todo's screen says so before a session starts
  ("…, or sooner when the next one starts") rather than guessing which occurrence the server will pick.
- A session that ends by itself counts a phase left at 0:00 (a manual run nobody came back to) up to its timer, not up to
  the end: nobody was there. Finish pressed late still counts the time kept going.
- A session ends by itself at its end time (`FinishBy`), as completed, as if Finish were pressed: a looping session
  left running overnight would otherwise notify all night. The end is 12 hours after the start unless one is picked
  when starting or moved while running, between 1 minute and 24 hours ahead. The app picks a clock time ("until
  17:00"), its next occurrence. `PomodoroAutoFinish` runs first in each watcher pass, and the auto-advance never
  steps a run past its end. An end more than 15 minutes in the past (server downtime, or sessions running when
  this was deployed, given an end 12 hours after their start) finishes silently.
- Stats count the time a phase really took: from its start to its end, minus its pauses (`PausedSeconds`). A
  phase skipped after 5 minutes counts 5, and one kept going past its timer counts the whole time. Finishing a
  session ends the phase under way, so the focus it holds counts.
- A cycle has 1 to 24 phases of 1 to 240 minutes (`PomodoroTemplate.MaxPhases`, `MaxPhaseMinutes`). The editor
  builds the classic one from four numbers: focus, short break, rounds, long break.
- Run state changes are broadcast over SignalR so phone and desktop stay in sync.
- A session is for an occurrence (`PomodoroRun.OccurrenceId`, set null if that row goes): the one a reminder's "Start
  focus" named, else a one-time todo's, else the pending one nearest the start within 12 hours either side
  (`SessionOccurrence`). A session that completes (finished, or ended by itself) asks about it while it is pending:
  "Done with ...?" marks it there (`GET /occurrences/{id}` reads one of any status). Not after "End session", which says
  nothing was finished. A focus link (`kadans://todos/{id}/focus`) shows the session running, or the last one, and never
  starts another.

### Notifications

- `Device` (user, platform, push token, the session that registered it) registered from the client after
  each sign-in; it goes when that session ends.
- Scheduled job selects occurrences where `scheduled_at - lead_time <= now AND notified_at IS NULL`,
  dispatches, stamps `notified_at` (idempotent); then those whose start has come (`start_notified_at IS NULL`) for
  "starts now" (Reminders ring on the phone → two reminders per occurrence).
- Channels: FCM (Android/iOS), SignalR for connected clients (desktop is long-running, so the
  persistent connection is the primary channel there), Web Push later if a web client appears. A hub
  connection is authorized once, when it opens. It closes when its access token expires and at once when
  its session ends. The app reconnects with a fresh token and catches up on the unread notifications that
  arrived during the gap, so a reminder never falls into it. The server pings every 15 s; an app that hears nothing
  for 30 s reconnects (a connection Android froze in the background looks open long after the server dropped it).
- Durable scheduling via Quartz.NET (in-memory job store: every job is an idempotent periodic
  scan, so nothing needs to survive a restart). Implemented in Phase 4: `OccurrenceReminderJob` scans
  `notify_at <= now AND notified_at IS NULL`, builds the message in the user's time zone
  (`IUserDirectory`) and hands it to `INotificationDispatcher`, which stores it, publishes it on the
  hub and queues the push. `PushWorker` drains that in-memory `PushQueue` in the background
  (`IDevicePushTargets` → `IPushSender`, dead tokens retired): a provider call takes up to seconds and
  no request or scheduler pass should wait for it; a push lost to a restart is a missed banner, the
  notification itself is already stored.
- Reminder timing: the job runs every 5 s (`Tasks:ReminderIntervalSeconds`, a code default, so
  production and Development agree; 5 s is also the floor), which bounds how late a reminder can be. The scan reads a filtered
  index, so the short interval is cheap. Because a pass lands a few seconds after the notify time, the
  "in 15 min" text rounds the time left up to the minute. A lead is at most 30 days
  (`Todo.MaxNotifyBeforeMinutes`): occurrences only exist 30 days ahead.

### Budget (Phase 7, implemented)

Accounts (computed balances), income/expense categories with spending limits, transactions incl.
transfers, recurring rules, `/budget/summary` per month in the user's time zone. `Money` is a value
object inside the module: currencies never mix implicitly (HTG, USD, EUR, CAD, DOP, MXN). A
cross-currency transfer carries the received amount – that pair *is* the rate for that date.
The user has a base currency and indicative per-currency rates (1 unit = X base, refreshable
daily) used for estimates and transfer pre-fill only; stored amounts never move, and the combined
estimate reports currencies without a rate instead of guessing. Recurring transactions run on the
shared recurrence engine: `RecurringTransactionJob` (Quartz, `Budget:RecurringIntervalMinutes`,
15 by default) materializes due rules into real transactions, so salary lands on the 1st with no
client running; exhausted rules self-deactivate. Money rules repeat daily at most and start at most
a year back. Otherwise they take a todo rule's day parts (`BudgetRecurrence`: days of the week, days of
the month with -1 the last, BYSETPOS for "the last Friday", months), and the app builds both with one
builder (`ui/todos/RepeatSection`, under the todo's `WhenSection` and in the movement form, without the
hourly frequencies or several times a day): "the 15th and the 30th" is the same rule for a salary as
for a todo. A day some month lacks falls on that month's last day: "the 15th and the 30th" pays on 28
February (calendar apps would skip it). RFC 5545 skips such months, so the app says it with set positions
(`LateMonthDays`): BYMONTHDAY=15,28,29,30;BYSETPOS=1,-1 keeps the 15th and the latest of the 28th to
the 30th each month has; the 31st missing somewhere is simply the last day (-1). The rule's words and the
edit form read it back as the days picked. Rules stored before 2026-10-17 with a plain BYMONTHDAY=30
still skip February until edited. A pass creates up to 100 transactions per rule; a longer backlog resumes on the next
pass. A "pay rent" task and a recurring transaction are the same cadence seen from two modules.
A rule is an income, an expense or a transfer ("100 USD to savings every month"); between two
currencies it books the same pair each time, and the person corrects a booked movement when the
day's rate differed (stored amounts never follow indicative rates). Changing a rule changes what its
next bookings carry (amount, received amount, category, note), never its accounts or dates.
- A category's limit (`CategoryBudget`, `PUT /budget/categories/{id}/budget`): one per expense category, by the
  week (from Monday), the month or the year, in the person's time zone. Its currency is fixed when it is set (another
  means removing it), so nothing stored is ever converted; spending in other currencies counts at the person's
  indicative rates through the base currency (`RateBook`), and a currency without a rate is left out and said. The
  summary gives each limit in the period the month on screen covers (that month, the year through it, or this week)
  with its whole percent. `CategoryLimitAlerts` checks after every expense that adds to a limited category, recorded,
  edited or booked by a recurring rule, never on a change of rates alone: at 80% (fixed) and at 100%, once each per
  period (`AlertedPeriodStart`, `AlertedLevel`; a new amount or period starts afresh). The alert ("budget.limit") is an
  ordinary notification: the notification centre, the hub (a desktop system notification), a push; it carries
  `link: kadans://budget`, which a tap opens wherever it is shown.
- Correcting a movement (`PUT /budget/transactions/{id}`): amounts, day, category, note, and the
  account(s) it touches – the currency follows the account – but never its kind (delete it and record
  it again). It may stay on an archived account (fixing an old one) but never move onto one; one a rule
  booked changes alone. Every movement, new, edited or a rule's template, passes the one set of rules
  in `Transaction.Check` (a note is at most 1,000 characters). The app opens a movement from the
  month's list, which shows the month on screen in the account's time zone, as the totals do.
