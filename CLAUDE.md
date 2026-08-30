# Kadans

Personal "life cadence" app: scheduled & recurring tasks with attached Pomodoro sessions,
notifications, and (planned) a personal budget. Backend is ASP.NET Core (.NET 10) + Postgres;
the client is a Compose Multiplatform app (Android, iOS, desktop) in `clients/app`.

Read `docs/ARCHITECTURE.md` (target design and the rules that keep it a modular monolith) and
`docs/ROADMAP.md` (phases, known bugs, what is next) before making structural changes.

## Layout

- `src/Kadans.Api` – host only: Serilog, OpenAPI/Scalar, JSON, authorization fallback, module wiring.
- `src/Kadans.Modules.Identity` – users, auth, tokens, profile (`identity` schema).
- `src/Kadans.Modules.Tasks` – todos, occurrences, pomodoro (`tasks` schema).
- `src/Kadans.SharedKernel` – errors/ProblemDetails, `ICurrentUserService`, snake_case naming,
  and the recurrence engine (`Recurrence/RecurrenceSchedule`, RRULE + IANA tz via Ical.Net).
- `tests/Kadans.Tasks.Tests`, `tests/Kadans.SharedKernel.Tests` – TUnit unit tests (modules expose internals via `InternalsVisibleTo`).
- `clients/app` – Compose Multiplatform client (Gradle project, opened separately in Android Studio/Fleet).
- `docs/` – architecture, roadmap, decisions.

## Commands

```bash
dotnet build Kadans.slnx
dotnet test --solution Kadans.slnx          # MTP mode; opt-in lives in global.json ("test.runner")
dotnet run --project src/Kadans.Api         # Scalar UI at /scalar in Development
# one DbContext per module: always pass --project (module) --startup-project (host) --context
dotnet ef database update --project src/Kadans.Modules.Identity --startup-project src/Kadans.Api --context IdentityModuleDbContext
dotnet ef database update --project src/Kadans.Modules.Tasks --startup-project src/Kadans.Api --context TasksDbContext
dotnet ef migrations add <Name> --project src/Kadans.Modules.Tasks --startup-project src/Kadans.Api --context TasksDbContext --output-dir Persistence/Migrations
docker start kadans-postgres                # local Postgres 17 (created with POSTGRES_DB=kadans, password 'password')
dotnet user-secrets list --project src/Kadans.Api
```

Dev secrets (`ConnectionStrings:kadans`, `Jwt:Key`, `InitialAdmin:Password`, and when needed
`Email:Resend:ApiKey`, `ExternalAuth:Google:ClientIds:0`) live in `dotnet user-secrets`, never in
`appsettings*.json`. Development uses `Email:Provider=Log`: emails (with their links) go to the log.

Running the API by hand for a smoke test: start it in the background, and stop it with `pkill -x Kadans.Api`
(the apphost's process name) – killing the `dotnet run` parent leaves the server alive on its port.
`python3 tools/smoke/identity_flows.py <api log>` checks every Identity flow end to end;
`python3 tools/smoke/task_flows.py` does the same for todos/occurrences (horizon, overrides, previews).

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
- Recurrence: never hand-roll date math. Build a `RecurrenceSpec`, create a `RecurrenceSchedule`,
  and ask it for occurrences. Clients send a structured rule plus an IANA `TimeZone`.

## Client

`clients/app` is untouched from its original template so far (root project name still `todo`,
package `com.example.todo` or similar). Enable the desktop (JVM) target before building UI.
