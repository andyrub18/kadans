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
- [x] Deep links (Phase 6): the API serves localized landing pages for the emailed links and offers a
      `kadans://` link handled on Android. Verified https App Links / Universal Links wait for the domain.

Config: `Email:Provider` (`Resend`|`Log`), `Email:From`, `Email:LinkBaseUrl`, secret `Email:Resend:ApiKey`;
`ExternalAuth:Google:Desktop:ClientId` / `:ClientSecret`, `ExternalAuth:Google:WebClientId`,
`ExternalAuth:Google:ClientIds` (extra audiences, e.g. iOS), `ExternalAuth:Apple:ClientIds`.
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
- [x] Desktop OS notifications done in Phase 6 (hub connection → notify-send / tray balloon); Web Push
      only if a web client ever appears

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
- [x] Server-side loop mode: `?loop=true` repeats the cycle (fresh lap phases, so history and
      stats count every one) until `PUT …/finish` completes the session; auto-advance loops too
      and notifies with the lap number. Client: loop on by default, hands-free optional, lap-aware
      countdown, "Finish session" vs "Discard". Default template fixed to the real pomodoro
      (4×25/5 with a 30-minute long break).
- [x] Pomodoro cycle management: template update/delete endpoints; client Templates screen
      (create/edit/delete phases) reachable from Home; per-todo cycle picker on the detail screen
- [x] Recurring end choice in the client: Never / after a number of times / on a date (inclusive
      end-of-day `until` in the user's zone — the backend supported until XOR count all along)
- [x] Trilingual (en / fr / ht Kreyòl Ayisyen): client `StringsCatalog` data class (a missing
      translation is a compile error) with `LocalStrings`; in-app picker (Login chips, Home cycle
      button), persisted, first run follows the device language; API errors localized by
      `errorCode` with server-detail fallback. Server: `PreferredLanguage` on the user (synced on
      switch) drives localized emails and localized reminder/pomodoro notifications.
      Still English: server-side validation detail texts (later pass).
- [x] Occurrence calendar (month grid, Monday-first, dots for pending/planned/done, day detail);
      edit-todo & move-occurrence UI; settings screen (profile, language, password, TOTP MFA
      enrol/disable/recovery codes, sign out everywhere)
- [x] SignalR connection (`/hubs/kadans`): hand-rolled JSON hub protocol over Ktor websockets,
      reconnect with backoff; live `pomodoro.run.changed` adoption + notification snackbars;
      hub payloads now serialize enums as strings to match the REST contract
- [x] Instant hands-free cadence: the watching client advances the phase itself the moment it
      runs out (domain steps on the schedule, so the cadence never drifts); the job (5s) only
      covers runs nobody is watching, and every hands-free advance dispatches a notification
- [x] Desktop background mode: OS notifications (notify-send / tray balloon + beep) for every
      pushed notification; closing the window hides to the system tray and keeps counting
      (guarded: quits normally when the desktop has no tray support)
- [x] Android FCM: google-services wired (plugin applied only when the gitignored json exists),
      channel + foreground handler + POST_NOTIFICATIONS prompt; every sign-in upserts the device
      with its push token. iOS push deferred (needs Mac + Apple Developer account).
- [x] Password reset end-to-end: localized HTML landing pages for the emailed links (the reset
      link used to 404), in-app Forgot/Reset screens, and a kadans:// deep link on Android;
      client CI workflow (jvmTest + desktop + Android assemble) on clients/** changes
- [x] Client CI job: `.github/workflows/client.yml` (jvmTest + desktop + Android assemble) on `clients/**`;
      backend CI (`ci.yml`) ignores `clients/**`

## Phase 7 – Budget module

- [x] Backend (`Kadans.Modules.Budget`, `budget` schema): `Money` value object (currencies never
      mix implicitly — HTG, USD, EUR, CAD, DOP, MXN), accounts (computed balances), income/expense
      categories, transactions incl. transfers — a cross-currency transfer carries the received
      amount (that pair IS the rate, for that date), monthly category limits, `/budget/summary`
      per month in the user's time zone
- [x] Base currency per user + indicative per-currency rates (1 unit = X base, refreshable daily):
      estimates and transfer pre-fill only, stored amounts never move; the combined estimate
      excludes and reports currencies without a rate instead of guessing
- [x] Recurring transactions on the shared recurrence engine (`RecurrenceSchedule`): a Quartz job
      materializes due rules into real transactions (salary lands on the 1st with no client
      running); exhausted rules self-deactivate
- [x] Daily exchange rate as a user parameter (`/budget/exchange-rate`): pre-fills cross-currency
      transfers and powers the "everything in HTG, at your rate" estimate in the summary —
      stored amounts never move
- [x] Client: Budget screen (month pager, at-your-rate card, accounts & balances, category
      spend-vs-limit bars, recurring list with pause/delete, recent movements) and an Add screen
      (income/expense/transfer with rate pre-fill, repeat switch on the recurrence engine),
      trilingual like everything else

## Phase 8 – V1: screens first, then release hardening (current)

Every feature phase is done. Order agreed 2026-09-17: what the owner sees and feels comes first,
installing on real devices second, hosting last.

1. Screens and feel

- [x] Connect with Google in the clients. Android: Credential Manager (`GetSignInWithGoogleOption`, the
      server's Web client id as `serverClientId`) → `POST /auth/external`. Desktop: loopback OAuth with
      PKCE in the system browser → `POST /auth/external/google/code`, where the **server** trades the code
      for the ID token, so the Desktop client's secret never ships in the app. `GET /auth/providers`
      (anonymous, ids only) tells the clients what is configured; the button exists only when it can work
      and re-asks when the server address changes. iOS deferred with the rest of iOS. New
      `tests/Kadans.Identity.Tests` (first Identity unit tests). Code-complete and tested against fakes and
      Google's real refusal of a junk code; the real end-to-end run waits for the owner's OAuth clients
      (OWNER-CHECKLIST → Google Sign-In).
- [ ] Pomodoro notifications arrive a few seconds late. Causes: (1) a run nobody watches is only
      stepped by `PomodoroAutoAdvanceJob`, floored at 5 s; (2) the advance request waits for the FCM
      call inside `NotificationDispatcher` before answering, so the screen lags behind the OS alert;
      (3) FCM itself adds 1–3 s on a phone. Plan: fire the OS notification locally on the client the
      moment its own countdown hits zero (desktop now, Android exact alarm later) and dedupe the pushed
      one; server side, take push off the request path and step overdue runs on a precise per-run
      trigger (or a 1 s scan) instead of the 5 s poll. Note: after the ViewModel scoping fix a run is only
      "watched" while its screen is open, so the server-side part matters more, not less.
- [ ] Recurring "ends on": today a date at 23:59 in the user's zone. Add a time picker so hourly rules
      can end at a precise time ("every 2 hours until Friday 18:00"); daily-and-slower keep end of day.
- [ ] Notification centre with unread badge (`GET /notifications`, `/unread-count`, mark read) – the client
      only shows live snackbars / OS notifications today
- [ ] Pomodoro stats and run history screens (`/pomodoro/stats`, `/todos/{id}/pomodoro/runs`)
- [ ] Email change and device list in Settings
- [ ] Server-side validation detail texts are English only (client localizes by `errorCode` first)
- [ ] Layout nits: Budget migrations live in `Migrations/` (others: `Persistence/Migrations/`) and the
      Budget project sits outside the `src/modules` solution folder in `Kadans.slnx`

2. Before installing on your own devices

- [ ] Secure token storage: `SettingsTokenStore` keeps tokens in plain preferences → Keystore/Keychain
- [ ] Android release build: signing config + release keystore; align versions (Android `0.1.0` vs desktop
      `packageVersion 1.0.0`)
- [ ] iOS: builds only on a Mac, push deferred – V1 is realistically Android + desktop

3. Before hosting

- [ ] Production logging: `appsettings.json` has no `Serilog` section and Serilog reads only that section,
      so a production host logs nothing until sinks are configured
- [ ] Migrations at deploy time: nothing applies them at startup and there are four contexts – migrate on
      startup or ship a migration bundle in the deploy script
- [ ] Deploy story: Dockerfile/compose or host config, health endpoint, forwarded headers behind a reverse
      proxy (HTTPS redirection is on); domain, production Postgres, `Jwt:Key`, Resend domain, FCM service
      account are on `OWNER-CHECKLIST.md`

Nice-to-have hardening

- [ ] Integration tests with Testcontainers (Phase 3 leftover); unit tests for Identity and Notifications
- [ ] Rate limiting on `forgot-password` / `resend-confirmation` (lockout already covers login)

## Fixed along the way

- Npgsql rejects any `DateTimeOffset` with a non-zero offset (`timestamp with time zone`), so a client sending
  `09:00-05:00` produced a 500. Every DbContext now applies `StoreDateTimeOffsetsAsUtc()` (SharedKernel) and
  `RecurrenceSchedule` normalizes start/exceptions to UTC. Found by the Phase 1 smoke test, 2026-08-30.

## Known bugs in the current code

| Where | Problem |
|-------|---------|
| `clients/app` `ui/todos/EditTodoViewModel.kt` | ~~`save()` leaves `isSaving = true` on success; with ViewModels outliving nav entries the second edit of a todo shows a stuck spinner and re-sends a stale `pomodoroTemplateId`~~ fixed 2026-09-17: ViewModels are scoped to their nav entry (`rememberViewModelStoreNavEntryDecorator`) |
| `tools/smoke/identity_flows.py` | Not re-runnable: it registers `alice` and never removes her, so a second run against the same database fails at step one (`DuplicateUserName`) |
| `Models/RecurrenceRule.cs` (old engine) | ~~Wrong hour for non-UTC offsets, DST not representable, `Interval > 1` misaligned~~ replaced by `RecurrenceSchedule` (Ical.Net) in Phase 0 |
| `Models/RecurrenceRule.cs` `CreateOneTimeRule` | ~~NRE in `GetOccurrences` (no ByHour/ByMinute)~~ fixed in Phase 0 |
