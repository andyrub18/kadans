# Kadans – Architecture

## Shape: modular monolith

One deployable API, one Postgres database, hard module boundaries inside the codebase.
Not microservices, and not a four-layer "clean architecture" per module – vertical slices inside
modules are enough.

### Layout (all four modules exist)

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
tests/
  Kadans.<Module>.Tests/         TUnit unit tests (Tasks, Budget, Identity, Notifications, SharedKernel)
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
   and `IRealtimePublisher` (implemented by Notifications), and `ISessionEndListener` (called by Identity
   when sessions end; Notifications closes their live connections). Tasks and Budget only consume them.
4. **Everything is `internal`** except `Contracts` and the `IModule` implementation.
5. **Per-user isolation via EF global query filters** on `UserId == ICurrentUserService.UserId`.
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
headers it trusts); `deploy/` is the reference setup for a single VPS (Caddy, API, Postgres, nightly
dump). Outside Development the process refuses to start on an incomplete configuration. The only state
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

### Client: Compose Multiplatform

Already started (`clients/app`). Covers Android, iOS and desktop (Windows/macOS/Linux) from one
codebase, which matches the requirement of reliable background timers + OS notifications on
desktop and real push on mobile. Web is a possible later bonus (Wasm target).

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
  arrived during the gap, so a reminder never falls into it.
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
