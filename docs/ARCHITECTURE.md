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
                                 (migrations in Migrations/ at the module root)
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
   and `IRealtimePublisher` (implemented by Notifications). Tasks and Budget only consume them.
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
by the client against the provider's JWKS (OIDC discovery) and links by verified email or creates the
account. Every emailed link opens an anonymous page served by the API (confirm email, reset password,
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

### Tests: TUnit on Microsoft.Testing.Platform

Opt-in for `dotnet test` is `"test": { "runner": "Microsoft.Testing.Platform" }` in `global.json`.
Domain rules are unit-tested as pure code. Integration tests with Testcontainers against real Postgres
are still to come (recurrence and query filters must be tested on the real provider); the smoke
scripts in `tools/smoke/` cover those paths against a running Development API for now.

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
- Run state changes are broadcast over SignalR so phone and desktop stay in sync.

### Notifications

- `Device` (user, platform, push token) registered from the client.
- Scheduled job selects occurrences where `scheduled_at - lead_time <= now AND notified_at IS NULL`,
  dispatches, stamps `notified_at` (idempotent).
- Channels: FCM (Android/iOS), SignalR for connected clients (desktop is long-running, so the
  persistent connection is the primary channel there), Web Push later if a web client appears.
- Durable scheduling via Quartz.NET (in-memory job store: every job is an idempotent periodic
  scan, so nothing needs to survive a restart). Implemented in Phase 4: `OccurrenceReminderJob` scans
  `notify_at <= now AND notified_at IS NULL`, builds the message in the user's time zone
  (`IUserDirectory`) and hands it to `INotificationDispatcher`, which stores it, publishes it on the
  hub and queues the push. `PushWorker` drains that in-memory `PushQueue` in the background
  (`IDevicePushTargets` → `IPushSender`, dead tokens retired): a provider call takes up to seconds and
  no request or scheduler pass should wait for it; a push lost to a restart is a missed banner, the
  notification itself is already stored.

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
client running; exhausted rules self-deactivate. A "pay rent" task and a recurring transaction are
the same cadence seen from two modules.
