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
   user's data when Identity erases the account), and `IMobileAccess` (implemented by Billing; Notifications asks
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
exchanged with a TOTP or recovery code. External login verifies Google/Apple ID tokens obtained natively
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
nightly dump). Outside Development the process refuses to start on an incomplete configuration. The only state
besides Postgres would have been ASP.NET Core's Data Protection key ring (it protects the tokens in emailed
links, and its default home is a folder inside the container, lost on every update); it lives in the
Identity schema instead (`data_protection_keys`), encrypted with AES-GCM under a key derived from `Jwt:Key`
(`KeyRingEncryption`), so it is in the backups without making a backup enough to forge a link. If Kadans ever
needs a second instance, the things to externalize are exactly that list: a persistent Quartz store,
a Redis backplane for SignalR, a real queue for push, and shared rate-limit counters.

Rate limiting is the host's (ASP.NET Core's limiter, per client address: IPv4, or the IPv6 /64). There
is a global limit, plus two named policies modules put on endpoints (`RateLimitPolicies`). **Email** covers
endpoints that mail an address the caller chooses; **Credentials** covers those that check a password or a
code. Both are token buckets, so a rejection says when to retry. The numbers are configuration
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
language from `Accept-Language` (`RequestLanguage`: en, fr or ht; anything else is English) and
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

### The account's time zone and language follow the device

The server works in the account's time zone and language: reminder texts, focus-stats days, Budget months and
emails. Both come from the device, not from a form. A new account starts with the device's IANA zone and the
app's language: sign-up sends them, and so does a Google sign-in that creates the account (the server keeps a
zone its tz database knows and a language among en, fr and ht, otherwise UTC and English; linking leaves an
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

Deleting one todo (`DELETE /todos/{id}`), unlike cancelling it, takes its occurrences, remarks and focus history
along, and its stats with them.

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

Android (built): `reminders/LocalReminders` (common) keeps the window in the settings store and decides what rings;
`ReminderScheduler` is the OS's side (Android: `AndroidReminderScheduler`; desktop below; iOS has none yet and stays on
the push and the hub). What rings is always read from the stored window:
- **One exact alarm**, the next reminder's (`setExactAndAllowWhileIdle`, far from Android's 500 per app). When it
  rings, every reminder due and not rung here is checked with the server (two seconds at most, in parallel), shown
  unless no longer due (a moved one fetches the window again), and the next alarm is set. A reminder rings late (a
  phone that was off) until its event starts, or ten minutes after its time when that is later; never twice
  (`occurrence@notifyAt` remembered three days, so a moved one rings again).
- **Notifications**: their own channel, `kadans.reminders` (urgent: heads-up, sound; named in the app's language),
  tagged with the occurrence as Firebase tags the push it shows, so one replaces the other. A system-shown reminder
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
app runs, the hub keeps the window current (each change, each reconnect, at least hourly when its token is renewed),
and Settings has no reminders section. Reminders show as the desktop's other notifications (`notify-send` or D-Bus
on Linux, the tray elsewhere); a click opening the todo would need a listener per desktop and is left for later.
Checked against a local API by `RealDesktopRemindersSmokeTest` (opt-in, `KADANS_API_URL`): a reminder reaches the
window over the hub, then rings from the timer with the server out of reach (112 ms after its time).

Order: the server first (3–4 days; on its own it changes nothing until an app says it schedules), then Android
(about a week), desktop (1–2 days), and iOS with the iPhone app on a Mac (3–4 days). Pomodoro's "time's up" could
move the same way later.

### Client: Compose Multiplatform

Already started (`clients/app`). Covers Android, iOS and desktop (Windows/macOS/Linux) from one
codebase, which matches the requirement of reliable background timers + OS notifications on
desktop and real push on mobile. Web is a possible later bonus (Wasm target).

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
- **One Kadans per user** (`desktop/SingleInstance`): a lock file the system releases however the process ends, and a
  Unix socket beside it, in a folder only the user can open (`$XDG_RUNTIME_DIR/kadans` on Linux). A second start, such
  as the launcher clicked while Kadans sits in the tray, connects, the first shows its window, and the second exits.
  Without it, a second copy would hold a second live connection and ring every reminder twice. Dev and installed builds
  share their settings, so they share the lock. Nothing about it can stop Kadans from starting: no usable folder, no
  lock.
- **The installed app** (`packageDeb`): the runtime image adds `jdk.security.auth` and `jdk.net` (dbus-java's login and
  socket options). The main class is `kadans.Kadans`, so the window's X11 class is `kadans-Kadans`, the name of the
  launcher jpackage installs (it writes no `StartupWMClass`): that is how docks match the window to its icon.

Checked on COSMIC (Pop!_OS 24.04), from Gradle and from the packaged runtime: the icon in the panel, close hides, the
icon's click and the menu bring the window back, Quit ends the app and removes the icon, and a second start shows the
first. `StatusNotifierTrayTests` replays it against a private bus (dbus-java's own daemon and a fake panel), so CI needs
no desktop. Not done: starting Kadans with the session, so reminders ring after a reboot without opening it.

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
- **Materialized occurrences with a rolling horizon**: a scheduled job guarantees every active
  rule has occurrences generated through `now + 30 days`. Past and near future = table (truth);
  far future = computed preview only.
- **Per-occurrence overrides**: cancel / reschedule / complete act only on occurrence rows
  (`Status`, `ScheduledAt` vs `OriginalScheduledAt`, `RescheduledAt`, `CompletedAt`, `CancelledAt`,
  reasons, `Remarks`, `NotifiedAt`). Changing the rule regenerates untouched future rows and keeps
  touched ones. This is the Google Calendar model. (Implemented in Phase 3: `OccurrencePlanner` is the
  pure part, `OccurrenceGenerator` writes rows, `OccurrenceHorizonJob` keeps every active todo ahead of
  `Tasks:OccurrenceHorizonDays`; `Todo.OccurrencesGeneratedThrough` records progress, `MaxValue` meaning a
  bounded rule is exhausted.)

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

### Notifications

- `Device` (user, platform, push token, the session that registered it) registered from the client after
  each sign-in; it goes when that session ends.
- Scheduled job selects occurrences where `scheduled_at - lead_time <= now AND notified_at IS NULL`,
  dispatches, stamps `notified_at` (idempotent).
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

Accounts (computed balances), income/expense categories with monthly limits, transactions incl.
transfers, recurring rules, `/budget/summary` per month in the user's time zone. `Money` is a value
object inside the module: currencies never mix implicitly (HTG, USD, EUR, CAD, DOP, MXN). A
cross-currency transfer carries the received amount – that pair *is* the rate for that date.
The user has a base currency and indicative per-currency rates (1 unit = X base, refreshable
daily) used for estimates and transfer pre-fill only; stored amounts never move, and the combined
estimate reports currencies without a rate instead of guessing. Recurring transactions run on the
shared recurrence engine: `RecurringTransactionJob` (Quartz, `Budget:RecurringIntervalMinutes`,
15 by default) materializes due rules into real transactions, so salary lands on the 1st with no
client running; exhausted rules self-deactivate. Money rules repeat daily at most and start at most
a year back. A pass creates up to 100 transactions per rule; a longer backlog resumes on the next
pass. A "pay rent" task and a recurring transaction are the same cadence seen from two modules.
