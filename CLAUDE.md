# Kadans

Personal "life cadence" app: scheduled & recurring tasks with attached Pomodoro sessions,
notifications, and a personal budget (multi-currency, HTG/USD first). Backend is ASP.NET Core
(.NET 10) + Postgres; the client is a Compose Multiplatform app (Android, iOS, desktop) in
`clients/app`. All feature phases are done; the project is in V1 release hardening (see
`docs/ROADMAP.md`, Phase 8).

Read `docs/ARCHITECTURE.md` (target design and the rules that keep it a modular monolith) and
`docs/ROADMAP.md` (phases, known bugs, what is next) before making structural changes.

## Layout

- `src/Kadans.Api` – host only: Serilog, OpenAPI/Scalar, JSON, authorization fallback, module wiring.
- `src/Kadans.Modules.Identity` – users, auth, tokens, profile (`identity` schema).
- `src/Kadans.Modules.Tasks` – todos, occurrences, pomodoro, Quartz jobs (`tasks` schema).
- `src/Kadans.Modules.Notifications` – notification log, SignalR hub `/hubs/kadans`, push (FCM) (`notifications` schema).
- `src/Kadans.Modules.Budget` – accounts, categories, transactions/transfers, recurring money, monthly
  summary, base currency + rates (`budget` schema). Its migrations live in `Migrations/` at the module
  root (the other modules use `Persistence/Migrations/`) – pass that `--output-dir` when adding one.
- `src/Kadans.SharedKernel` – errors/ProblemDetails, `ICurrentUserService`, snake_case naming,
  and the recurrence engine (`Recurrence/RecurrenceSchedule`, RRULE + IANA tz via Ical.Net).
- `tests/Kadans.Tasks.Tests`, `tests/Kadans.Budget.Tests`, `tests/Kadans.Identity.Tests`, `tests/Kadans.Notifications.Tests`,
  `tests/Kadans.SharedKernel.Tests` – TUnit unit tests (modules expose internals via `InternalsVisibleTo`). Identity
  (Google sign-in) and Notifications (push worker) are thin so far: the smoke scripts cover the rest.
- `clients/app` – Compose Multiplatform client (Gradle project, opened separately in Android Studio/Fleet).
- `docs/` – architecture, roadmap, decisions, and `OWNER-CHECKLIST.md` (accounts/keys only the owner can set up).

## Commands

```bash
dotnet build Kadans.slnx
dotnet test --solution Kadans.slnx          # MTP mode; opt-in lives in global.json ("test.runner")
dotnet run --project src/Kadans.Api         # Scalar UI at /scalar in Development
# one DbContext per module: always pass --project (module) --startup-project (host) --context
dotnet ef database update --project src/Kadans.Modules.Identity --startup-project src/Kadans.Api --context IdentityModuleDbContext
dotnet ef database update --project src/Kadans.Modules.Tasks --startup-project src/Kadans.Api --context TasksDbContext
dotnet ef database update --project src/Kadans.Modules.Notifications --startup-project src/Kadans.Api --context NotificationsDbContext
dotnet ef database update --project src/Kadans.Modules.Budget --startup-project src/Kadans.Api --context BudgetDbContext
dotnet ef migrations add <Name> --project src/Kadans.Modules.Tasks --startup-project src/Kadans.Api --context TasksDbContext --output-dir Persistence/Migrations
dotnet ef migrations add <Name> --project src/Kadans.Modules.Budget --startup-project src/Kadans.Api --context BudgetDbContext --output-dir Migrations
docker start kadans-postgres                # local Postgres 17 (created with POSTGRES_DB=kadans, password 'password')
dotnet user-secrets list --project src/Kadans.Api
```

Dev secrets (`ConnectionStrings:kadans`, `Jwt:Key`, `InitialAdmin:Password`, and when needed
`Email:Resend:ApiKey`, `ExternalAuth:Google:Desktop:ClientId` / `:ClientSecret`, `ExternalAuth:Google:WebClientId`)
live in `dotnet user-secrets`, never in
`appsettings*.json`. Development uses `Email:Provider=Log`: emails (with their links) go to the log.

Running the API by hand for a smoke test: start it in the background, and stop it with `pkill -x Kadans.Api`
(the apphost's process name) – killing the `dotnet run` parent leaves the server alive on its port.
`python3 tools/smoke/identity_flows.py <api log>` checks every Identity flow end to end (not re-runnable:
it fails at step one if an earlier run left the user `alice` in the database);
`python3 tools/smoke/task_flows.py` does the same for todos/occurrences (horizon, overrides, previews);
`python3 tools/smoke/notification_flows.py <api log>` for reminders, push (logged) and the notification centre;
`python3 tools/smoke/pomodoro_flows.py` for the pomodoro timing model (pause/resume, stats, and the hands-free
deadline: it races a client against the server and fails if the phase change is more than 1 s late);
the task, notification, pomodoro and budget scripts log in as `smoke` / `Smoke123!` (register that user once;
`admin` has MFA in dev and cannot run them) – pass `[username] [password]` to override;
`python3 tools/smoke/budget_flows.py` for accounts, transfers with exchange, category limits, summary and
recurring rules (restart the API right before: the recurring job's first pass runs ~15 s after boot).
Nothing applies migrations at startup – run the four `dotnet ef database update` commands above first.

## Conventions

- Tests use **TUnit** (not xUnit/NUnit). `[Test]` + `await Assert.That(...)`.
- Services return `OneOf<ApplicationError, T>`; endpoints map errors with
  `error.ToProblemDetails(path)` to RFC 9457 ProblemDetails. Error codes are `ErrorTypes` SmartEnums.
- Minimal APIs, one `Map*Routes` extension per feature area, every endpoint has
  `WithName/WithSummary/Produces*` for OpenAPI.
- Database names are snake_case via `ModelBuilder.UseSnakeCaseNames()`; timestamps are `DateTimeOffset` UTC.
- Per-user data isolation is done with EF global query filters on `UserId == ICurrentUserService.UserId`.
  Keep that pattern; do not add manual `Where(UserId == ...)` checks instead of it.
- Modules must only depend on `Kadans.SharedKernel`, never on each other. Cross-module
  references are by id (no foreign keys, no navigation properties to another module's entities).
- Inside a module everything is `internal` except `Contracts/` (request/response records, enums they
  expose) and the `IModule` class. Layout: `Domain/`, `Persistence/` (DbContext + Migrations),
  `Contracts/`, `Features/<Area>/` (services, routes, validators).
- Domain rules (recurrence, pomodoro state machine) are pure code with unit tests; EF-only behaviour
  goes in integration tests.
- The app is trilingual (en/fr/ht). Every user-visible client string goes through
  `app.kadans.i18n.StringsCatalog` (add the key to the data class and all three instances —
  the compiler enforces completeness); never hardcode UI text in composables. Server-rendered
  texts (emails, notifications) go through `EmailTexts`/`LocalizedTexts` keyed by the user's
  `PreferredLanguage`.
- Recurrence: never hand-roll date math. Build a `RecurrenceSpec`, create a `RecurrenceSchedule`,
  and ask it for occurrences. Clients send a structured rule plus an IANA `TimeZone`.

## Client

`clients/app` is a Compose Multiplatform app in the current JetBrains template structure:
everything lives in `shared` (KMP library, package `app.kadans`); `androidApp`, `desktopApp`
and `iosApp` are thin launchers. Build with the wrapper from `clients/app`:
`./gradlew :desktopApp:run`, `:androidApp:assembleDebug`, `:shared:jvmTest`.
iOS needs a Mac (open `iosApp/iosApp.xcodeproj`). The app talks to `http://localhost:5199`
(desktop; Android emulator uses `10.0.2.2:5199`) – start the backend first. `local.properties`
(untracked) points at the
Android SDK. Versions are pinned in `gradle/libs.versions.toml` to the combo the official
KMP-App-Template tests together – bump them as a set, not piecemeal. Gradle stays (Amper is
alpha); revisit when Amper is stable.
