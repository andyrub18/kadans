# Kadans

*Kadans* (Kreyòl: cadence) is a personal life-management app built around rhythms: scheduled and
recurring tasks, Pomodoro sessions attached to them, notifications when a task is about to start,
and – later – a personal budget. One backend, one client codebase for phone and desktop.

## Repository

| Path | What |
|------|------|
| `src/Kadans.Api` | ASP.NET Core host (wires the modules) |
| `src/Kadans.Modules.*` | Identity, Tasks – one schema and DbContext each |
| `src/Kadans.SharedKernel` | Cross-module building blocks (errors, recurrence engine, module contract) |
| `tests/` | TUnit test projects |
| `clients/app` | Compose Multiplatform client (Android, iOS, desktop) |
| `docs/` | Architecture, roadmap, decisions |

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
dotnet run --project src/Kadans.Api      # then open https://localhost:<port>/scalar
```

Tests:

```bash
dotnet test --solution Kadans.slnx
```

## Client

Open `clients/app` in Android Studio (or Fleet) as a Gradle project.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Owner checklist](docs/OWNER-CHECKLIST.md) – accounts, keys and settings to create by hand
