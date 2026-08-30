# Kadans – Roadmap

## Phase 0 – Repository & foundation ✅ (2026-08-30)

- [x] New root `Kadans/`, git, `.gitignore`, `Directory.Build.props`, central package management
- [x] API moved to `src/Kadans.Api`, namespaces `Kadans.Api.*`
- [x] `Kadans.SharedKernel` extracted (errors, `ICurrentUserService`, snake_case naming)
- [x] TUnit test project with first recurrence tests (`dotnet test` in MTP mode)
- [x] Dev secrets moved to `dotnet user-secrets`
- [x] Compose Multiplatform client relocated to `clients/app`
- [x] Docs: CLAUDE.md, ARCHITECTURE.md, this file

## Phase 1 – Cheap-now, expensive-later

- [x] `ApplicationUser : IdentityUser` (display name, time zone) – exposed on register/update/me
- [x] Response DTOs for every endpoint (`Contracts/`); entities never leave a service
- [x] `FallbackPolicy = RequireAuthenticatedUser`; `AllowAnonymous` only on auth + docs endpoints
- [x] Route cleanup: `/occurrences` group, `/todos/{id}/cancel|history|remarks`, `Status` no longer client-settable
- [x] Cross-module user navigations/FKs removed (`Todo`, `PomodoroTemplate`, `PomodoroRun` keep a plain `UserId`)
- [x] Migration history reset to a single `Init`
- [x] Module split: `Kadans.Modules.Identity` (`identity` schema) and `Kadans.Modules.Tasks` (`tasks` schema),
      each with its own DbContext and migrations; host only wires `IModule`s. Notifications module comes with Phase 4.

## Phase 2 – Identity flows

- [ ] Change password (requires current password), revoke sessions
- [ ] Forgot / reset password via email (`IEmailSender` + SMTP/Resend implementation)
- [ ] Email confirmation; email change with verification
- [ ] `POST /auth/external { provider, idToken }` – Google, then Apple (native ID-token verification)
- [ ] Hash refresh tokens at rest; token family / reuse detection
- [ ] TOTP MFA enrol/verify (needed before Budget ships)
- [ ] Device registration (push tokens)

## Phase 3 – Recurrence done right

- [x] Timezone on rule; RRULE string + Ical.Net expansion (`SharedKernel/Recurrence`, 2026-08-30)
- [x] Engine test suite: DST, intervals > 1, BYSETPOS, month-end, exceptions, round-trip
- [ ] Rolling-horizon generation job (now + 30 days)
- [ ] Occurrence overrides (cancel/reschedule/complete on rows, not on the rule)
- [ ] Rule change = regenerate untouched future rows

## Phase 4 – Scheduler, notifications, real-time

- [ ] Quartz.NET (or Hangfire) replaces `BackgroundTaskQueue`
- [ ] Notification dispatch job + `notified_at` idempotency
- [ ] FCM + SignalR hub; run-state broadcast

## Phase 5 – Pomodoro model

- [ ] `PhaseEndsAt` / `RemainingSeconds` on pause; auto-advance option
- [ ] Session history & stats per todo

## Phase 6 – Client

- [ ] Enable desktop target in `clients/app`, rename project/package to Kadans
- [ ] Auth screens, todo list, occurrence calendar, pomodoro countdown, notifications

## Phase 7 – Budget module

- [ ] `Money` value object, accounts, categories, transactions, budgets, HTG/USD
- [ ] Recurring transactions on the shared recurrence engine

## Fixed along the way

- Npgsql rejects any `DateTimeOffset` with a non-zero offset (`timestamp with time zone`), so a client sending
  `09:00-05:00` produced a 500. Every DbContext now applies `StoreDateTimeOffsetsAsUtc()` (SharedKernel) and
  `RecurrenceSchedule` normalizes start/exceptions to UTC. Found by the Phase 1 smoke test, 2026-08-30.

## Known bugs in the current code (fix during Phases 1–3, most vanish with the redesign)

| Where | Problem |
|-------|---------|
| `Models/RecurrenceRule.cs` (old engine) | ~~Wrong hour for non-UTC offsets, DST not representable, `Interval > 1` misaligned~~ replaced by `RecurrenceSchedule` (Ical.Net) in Phase 0 |
| `Services/TodoCreation.cs` | Indefinite rules materialize 1 year then silently stop; `Minutely` = 525k rows |
| `Services/TodoUpdate.cs` `RescheduleNextOccurrence` (recurring) | New one-time `Todo` has no `UserId` (FK violation); background job filters on the *new* todo id so the original occurrence is never cancelled |
| `Services/TodoUpdate.cs` `CompleteOccurrence` | Overwrites `OccurrenceDate` with now instead of setting `CompletedAt` |
| `Services/TodoUpdate.cs` `UpdateTodo` | ~~Lets the client set `Status` directly~~ (fixed Phase 1); still orphans the old `RecurrenceRule` row |
| `Services/UserManagement.cs` `UpdateCurrentUser` | Password change without current password; email change without verification |
| `Security/RefreshToken.cs` | Refresh tokens stored in plaintext |
| `BackgroundTasks/BackgroundTaskQueue.cs` | In-memory; lost on restart; `TryWrite` drops silently when full |
| `Models/Pomodoro.cs` | Pause/resume does not track remaining time |
| `Models/RecurrenceRule.cs` `CreateOneTimeRule` | ~~NRE in `GetOccurrences` (no ByHour/ByMinute)~~ fixed in Phase 0 |
