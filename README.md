# Kadans

*Kadans* (Kreyòl: cadence) is a personal life-management app built around rhythms: scheduled and
recurring tasks, Pomodoro sessions attached to them, notifications when a task is about to start,
and – later – a personal budget. One backend, one client codebase for phone and desktop.

## Repository

| Path | What |
|------|------|
| `src/Kadans.Api` | ASP.NET Core minimal API (host + features) |
| `src/Kadans.SharedKernel` | Cross-module building blocks |
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

dotnet ef database update --project src/Kadans.Api
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
