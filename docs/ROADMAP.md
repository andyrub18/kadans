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

- [ ] `ApplicationUser : IdentityUser` (display name, timezone, profile fields)
- [ ] Response DTOs for every endpoint; stop returning entities
- [ ] `FallbackPolicy = RequireAuthenticatedUser`; `AllowAnonymous` only on auth endpoints
- [ ] Route cleanup: occurrence actions under `/occurrences/{id}/...`
- [ ] Module split: Identity, Tasks (+Pomodoro), Notifications, each with its own DbContext/schema.
      Migration history is reset (dev DB dropped) – agreed.

## Phase 2 – Identity flows

- [ ] Change password (requires current password), revoke sessions
- [ ] Forgot / reset password via email (`IEmailSender` + SMTP/Resend implementation)
- [ ] Email confirmation; email change with verification
- [ ] `POST /auth/external { provider, idToken }` – Google, then Apple (native ID-token verification)
- [ ] Hash refresh tokens at rest; token family / reuse detection
- [ ] TOTP MFA enrol/verify (needed before Budget ships)
- [ ] Device registration (push tokens)

## Phase 3 – Recurrence done right

- [ ] Timezone on rule; RRULE string + Ical.Net expansion
- [ ] Rolling-horizon generation job (now + 30 days)
- [ ] Occurrence overrides (cancel/reschedule/complete on rows, not on the rule)
- [ ] Rule change = regenerate untouched future rows
- [ ] Test suite covering DST, intervals > 1, BYSETPOS, month-end clamping, exceptions

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

## Known bugs in the current code (fix during Phases 1–3, most vanish with the redesign)

| Where | Problem |
|-------|---------|
| `Models/RecurrenceRule.cs` `Create` / `GenerateCandidates` | `ByHour = [startDate.Hour]` uses the client's offset, candidates are built as UTC → wrong hour for non-UTC offsets; DST not representable |
| `RecurrenceRule.GetNextOccurrence` | Iterates from `now` instead of stepping from `StartDate` → wrong results for `Interval > 1` |
| `Services/TodoCreation.cs` | Indefinite rules materialize 1 year then silently stop; `Minutely` = 525k rows |
| `Services/TodoUpdate.cs` `RescheduleNextOccurrence` (recurring) | New one-time `Todo` has no `UserId` (FK violation); background job filters on the *new* todo id so the original occurrence is never cancelled |
| `Services/TodoUpdate.cs` `CompleteOccurrence` | Overwrites `OccurrenceDate` with now instead of setting `CompletedAt` |
| `Services/TodoUpdate.cs` `UpdateTodo` | Lets the client set `Status` directly; orphans the old `RecurrenceRule` row |
| `Services/UserManagement.cs` `UpdateCurrentUser` | Password change without current password; email change without verification |
| `Security/RefreshToken.cs` | Refresh tokens stored in plaintext |
| `Routes/PomodoroRoutes.cs` | No `RequireAuthorization()` (saved only by query filters) |
| `Routes/TodoRoutes.cs` | `/todos/{id}/cancel`, `/complete`, `/remark` take an *occurrence* id |
| `BackgroundTasks/BackgroundTaskQueue.cs` | In-memory; lost on restart; `TryWrite` drops silently when full |
| `Models/Pomodoro.cs` | Pause/resume does not track remaining time |
| Endpoints | Return EF entities with `IdentityUser` navigations |
| `Models/RecurrenceRule.cs` `CreateOneTimeRule` | ~~NRE in `GetOccurrences` (no ByHour/ByMinute)~~ fixed in Phase 0 |
