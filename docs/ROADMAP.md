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

## Phase 2 – Identity flows ✅ (2026-08-30)

- [x] Change password (requires current password); `POST /users/me/sessions/revoke-all`
- [x] Forgot / reset password via email – `IEmailSender` with Resend (prod) and a log sender (dev)
- [x] Email confirmation on register (+ resend); email change with verification to the new address
- [x] `POST /auth/external { provider, idToken }` – Google and Apple ID tokens verified via OIDC discovery
- [x] Refresh tokens hashed at rest; one family per login session; reuse revokes the family
- [x] TOTP MFA: enrol → enable (recovery codes) → login returns an MFA challenge → `/auth/mfa/verify`
- [x] Device registration: `PUT /users/me/devices/{installationId}` (upsert push token), list, delete
- [x] `tools/smoke/identity_flows.py` exercises all of the above against a running API
- [ ] Deep links: the client must handle `/auth/confirm-email`, `/auth/reset-password`, `/users/me/email/confirm`
      (App Links / Universal Links); until then `Email:LinkBaseUrl` points at the API and only confirm-email works from a browser

Config: `Email:Provider` (`Resend`|`Log`), `Email:From`, `Email:LinkBaseUrl`, secret `Email:Resend:ApiKey`;
`ExternalAuth:Google:ClientIds`, `ExternalAuth:Apple:ClientIds` (one per client platform).
Security notes: MFA challenge tokens use audience `<Jwt:Audience>:mfa` so the bearer handler rejects them;
`User.RequireUniqueEmail = true`; the `IdentityFlows` migration drops all pre-existing refresh tokens.

## Phase 3 – Recurrence done right ✅ (2026-08-30)

- [x] Timezone on rule; RRULE string + Ical.Net expansion (`SharedKernel/Recurrence`)
- [x] Engine test suite: DST, intervals > 1, BYSETPOS, month-end, exceptions, round-trip
- [x] Rolling horizon: `OccurrencePlanner` (pure, tested) + `OccurrenceGenerator` + `OccurrenceHorizonJob`
      (`Tasks:OccurrenceHorizonDays`, batch cap `Tasks:MaxOccurrencesPerBatch`); creation materializes synchronously
- [x] `Todo.OccurrencesGeneratedThrough` (null = never, MaxValue = bounded rule exhausted); bounded todos auto-complete
- [x] Occurrence overrides on rows: `Status`, `ScheduledAt` vs `OriginalScheduledAt` (identity), reschedule/complete/cancel
      with proper state errors; `PUT /occurrences/{id}/reschedule`; `PUT /todos/{id}/reschedule` = next pending
- [x] Rule change (`PUT /todos/{id}` with `recurrenceRule`) drops untouched future rows the new rule does not produce,
      keeps touched ones, materializes the rest; rule omitted = details only
- [x] `GET /occurrences?from&to` fills the window past the horizon with computed previews (`isPreview`, no id)
- [x] In-memory background queue removed (nothing used it any more)
- [x] `tools/smoke/task_flows.py` exercises all of the above
- [ ] Integration tests with Testcontainers (the smoke script covers the DB paths for now)

## Phase 4 – Scheduler, notifications, real-time ✅ (2026-08-30)

- [x] Quartz.NET hosts the module jobs (`OccurrenceHorizonJob` hourly, `OccurrenceReminderJob` every
      `Tasks:ReminderIntervalSeconds`); each module registers its own jobs via `AddQuartz` (additive)
- [x] `TodoOccurrence.NotifyAt` (= scheduled − lead, null when off) is what the reminder job scans; `NotifiedAt`
      makes delivery once-only; reschedule re-arms; lead/enabled changes refresh pending rows; stale ones are skipped
- [x] `Kadans.Modules.Notifications` (`notifications` schema): notification log + `GET /notifications`,
      `/unread-count`, `PUT /{id}/read`, `/read-all`; `INotificationDispatcher` = store → SignalR → push
- [x] SignalR hub at `/hubs/kadans` (JWT via `?access_token=`); events `notification`, `pomodoro.run.changed`
      (every pomodoro mutation is broadcast to all of the user's devices)
- [x] Push: `IPushSender` – FCM (`Push:Provider=Fcm`, service-account JSON) or log sender in dev; dead tokens
      are removed from the device via `IDevicePushTargets`
- [x] Cross-module contracts in SharedKernel: `IUserDirectory`, `IDevicePushTargets` (implemented by Identity),
      `INotificationDispatcher`, `IRealtimePublisher` (implemented by Notifications)
- [x] `tools/smoke/notification_flows.py`
- [ ] Web Push / desktop OS notifications are the client's job (desktop stays on the hub connection)

## Phase 5 – Pomodoro model ✅ (2026-08-30)

- [x] `PomodoroRun` is a domain state machine: `PhaseEndsAt` while active (clients count down to it),
      `PausedRemaining` while paused, resume re-anchors; pause after the deadline freezes zero
- [x] Auto-advance opt-in per run (`POST …/pomodoro/start?autoAdvance=true`): `PomodoroAutoAdvanceJob`
      steps overdue runs on their own schedule (not job time), broadcasts and sends a
      `pomodoro.phase.completed` notification ("Break — 5 min" / "Pomodoro complete")
- [x] `GET /todos/{id}/pomodoro/runs` (history) and `GET /pomodoro/stats?from&to`
      (focus/break minutes + run counts, per day in the user's time zone)
- [x] `tools/smoke/pomodoro_flows.py`

## Phase 6 – Client

- [x] Re-scaffold `clients/app` in the current JetBrains template structure: `shared` KMP library
      (all UI, AGP 9 `androidMultiplatformLibrary`) + thin `androidApp` / `desktopApp` / `iosApp`
      launchers; package `app.kadans`, Kotlin 2.4.10 / Compose MP 1.11.1 / AGP 9.1 / Gradle 9.6.1;
      Ktor + kotlinx-serialization + Koin + navigation in the catalog. Gradle kept over Amper
      (alpha; ecosystem/IDE risk) – migrating a young Gradle project later is cheap.
- [x] API client (Ktor): typed DTOs for every contract, bearer + refresh-token rotation on 401,
      ProblemDetails → typed `KadansApiException`; MockEngine tests + env-gated live smoke (`KADANS_API_URL`)
- [x] Auth flow: login → MFA code → register, session persisted via `SettingsTokenStore`
      (multiplatform-settings; move to Keychain/Keystore before release), Koin DI; first Home
      screen (next-7-days occurrences + todo list, refresh/sign-out)
- [x] Navigation 3 (stable, multiplatform: androidx `navigation3-runtime` 1.1.1 + JetBrains
      `navigation3-ui`): owned back stack + `NavDisplay`, replacing navigation-compose 2.x
- [x] Light/dark theme following the system on all platforms (`KadansTheme`, M3 baseline
      schemes; the Kadans palette slots in there later)
- [x] Create-todo screen (one-time + recurring: frequency/interval/count, M3 date & time pickers;
      wall-clock picks are converted to instants in the user's zone and the zone rides on the rule)
- [x] Todo detail: pending/history occurrences with complete/skip, cancel todo, entry to the focus session
- [x] Pomodoro countdown bound to `phaseEndsAt` (server-authoritative: ticks to the deadline while
      active, shows the frozen remainder while paused; pause/resume/skip/end; auto-attaches a
      Classic 25+5+25 template when the todo has none)
- [x] First manual test feedback (owner, 2026-08-31): sessions are cyclic ("Start another cycle"
      after completion; re-entering a finished session no longer sticks and never auto-starts),
      "N times a day" via a times list (BYHOUR list, same-minute constraint surfaced in the UI),
      interval as a stepper reading "Every 2 days", all six frequencies exposed (hourly water
      plans work)
- [ ] Server-side cyclic mode ("repeat template until ended") so auto-advance loops too
- [ ] Occurrence calendar; edit-todo & reschedule UI; account/MFA settings screen
- [ ] SignalR connection (`/hubs/kadans`) so run state and notifications arrive live
- [ ] FCM registration on Android/iOS; deep links for the emailed URLs
- [ ] Client CI job (Gradle build) – backend CI ignores `clients/**`

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
| `Models/RecurrenceRule.cs` `CreateOneTimeRule` | ~~NRE in `GetOccurrences` (no ByHour/ByMinute)~~ fixed in Phase 0 |
