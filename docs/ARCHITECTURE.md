# Kadans – Architecture

## Shape: modular monolith

One deployable API, one Postgres database, hard module boundaries inside the codebase.
Not microservices, and not a four-layer "clean architecture" per module – vertical slices inside
modules are enough.

### Layout (Identity and Tasks exist; Budget and Notifications are planned)

```
src/
  Kadans.Api/                    host only: Program.cs, middleware, module registration
  Kadans.SharedKernel/           errors/ProblemDetails, OneOf helpers, ICurrentUserService,
                                 Money, audit/entity base, IModule, naming conventions,
                                 recurrence engine (shared by Tasks and Budget)
  Kadans.Modules.Identity/       users, auth, tokens, external logins, profile, devices
  Kadans.Modules.Tasks/          todos, occurrences, pomodoro
  Kadans.Modules.Budget/         accounts, categories, transactions, budgets
  Kadans.Modules.Notifications/  scheduler jobs, push/SignalR dispatch, hub (Phase 4)
tests/
  Kadans.<Module>.Tests/         TUnit unit tests
  Kadans.Api.IntegrationTests/   TUnit + Testcontainers (real Postgres)
clients/
  app/                           Compose Multiplatform (Android, iOS, desktop JVM)
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
3. **Modules depend only on SharedKernel.** They communicate through SharedKernel abstractions
   or in-process domain events (e.g. Tasks raises `OccurrenceDue`, Notifications handles it).
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
account. Emails go through `Kadans.SharedKernel.Email.IEmailSender` (Resend in production, log in dev).

### Tests: TUnit on Microsoft.Testing.Platform

Opt-in for `dotnet test` is `"test": { "runner": "Microsoft.Testing.Platform" }` in `global.json`.
Integration tests use Testcontainers against real Postgres – recurrence and query filters must be
tested on the real provider.

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
- Store an absolute `PhaseEndsAt`; on pause store `RemainingSeconds`; on resume set
  `PhaseEndsAt = now + remaining`. Clients count down to a timestamp, which survives reconnects
  and multiple devices. `ExpectedPhaseIndex` optimistic concurrency stays.
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
  hub and pushes it to the user's devices (`IDevicePushTargets` → `IPushSender`).

### Budget (later)

Accounts, Categories, Transactions, Budgets (category × period). `Money = (decimal Amount,
string Currency)` in SharedKernel from day one – HTG and USD coexist. Budget periods and recurring
bills reuse the recurrence engine; a "pay rent" task and a recurring transaction are the same
cadence seen from two modules.
