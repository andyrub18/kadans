# Kadans

*Kadans* (Kreyòl: cadence) is a personal life-management app built around rhythms: scheduled and
recurring tasks, Pomodoro sessions attached to them, notifications when a task is about to start,
and a personal budget (multi-currency, HTG/USD first). One backend, one client codebase for phone
and desktop. Trilingual: English, French, Haitian Creole.

## Repository

| Path | What |
|------|------|
| `src/Kadans.Api` | ASP.NET Core host (wires the modules) |
| `src/Kadans.Modules.*` | Identity, Tasks, Notifications, Budget – one schema and DbContext each |
| `src/Kadans.SharedKernel` | Cross-module building blocks (errors, recurrence engine, module contract) |
| `tests/` | TUnit test projects |
| `tools/smoke/` | End-to-end smoke scripts (Python, stdlib only) against a running dev API |
| `clients/app` | Compose Multiplatform client (Android, iOS, desktop) |
| `docs/` | Architecture (with decisions), roadmap, owner checklist |

## Backend – getting started

Prerequisites: .NET 10 SDK, PostgreSQL 16+.

```bash
# secrets are stored per-machine, never committed
dotnet user-secrets set "ConnectionStrings:kadans" "Host=localhost; Database=kadans; Username=postgres; Password=<pw>" --project src/Kadans.Api
dotnet user-secrets set "Jwt:Key" "<at least 32 random characters>" --project src/Kadans.Api
dotnet user-secrets set "InitialAdmin:Password" "<strong password>" --project src/Kadans.Api

docker run -d --name kadans-postgres -e POSTGRES_PASSWORD=<pw> -e POSTGRES_DB=kadans -p 5432:5432 -v kadans_postgres-data:/var/lib/postgresql/data postgres:17
dotnet ef database update --project src/Kadans.Modules.Identity --startup-project src/Kadans.Api --context IdentityModuleDbContext
dotnet ef database update --project src/Kadans.Modules.Tasks --startup-project src/Kadans.Api --context TasksDbContext
dotnet ef database update --project src/Kadans.Modules.Notifications --startup-project src/Kadans.Api --context NotificationsDbContext
dotnet ef database update --project src/Kadans.Modules.Budget --startup-project src/Kadans.Api --context BudgetDbContext
dotnet run --project src/Kadans.Api      # http://localhost:5199 – Scalar UI at /scalar in Development
```

Migrations are not applied at startup: run the four `database update` commands after every pull that
adds one. In Development emails go to the log (`Email:Provider=Log`) and push is logged too.

Tests:

```bash
dotnet test --solution Kadans.slnx            # TUnit unit tests (Tasks, Budget, Identity, SharedKernel)
python3 tools/smoke/identity_flows.py <log>   # end-to-end flows against a running API; see also
                                              # task_flows, notification_flows, pomodoro_flows, budget_flows
```

## Client

Open `clients/app` in Android Studio (or Fleet) as a Gradle project, or use the wrapper from there:

```bash
./gradlew :desktopApp:run            # desktop – talks to http://localhost:5199 (start the backend first)
./gradlew :androidApp:assembleDebug  # Android emulator reaches the backend at 10.0.2.2:5199
./gradlew :shared:jvmTest
```

Details in [clients/app/README.md](clients/app/README.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Owner checklist](docs/OWNER-CHECKLIST.md) – accounts, keys and settings to create by hand
