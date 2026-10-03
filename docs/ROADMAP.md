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
      (replaced in Phase 8 by `PomodoroDeadlineWatcher` + `PomodoroAutoAdvancer`, same stepping rules)
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
      switch; since Phase 8 also at sign-up and at every start) drives localized emails and localized
      reminder/pomodoro notifications.
      Server-side error and validation texts followed in Phase 8.
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
- [x] Pomodoro notifications arrived a few seconds late – fixed server-side, measured by the smoke script:
      phase change visible 59 ms after the deadline, notification created 26 ms after it (before: up to 5 s).
      (1) `PomodoroDeadlineWatcher` (a hosted service) replaced the 5 s Quartz poll: it sleeps until the exact
      moment the nearest hands-free phase ends and is pulsed awake whenever a run starts, resumes or advances;
      `Tasks:PomodoroAutoAdvanceSeconds` is now only its longest sleep. (2) `PomodoroRun` got Postgres `xmin`
      as row version: a watching client and the watcher both advance at the deadline, exactly one wins, so
      there is one notification and a looping run never gets its lap appended twice (that race existed
      before, the poll just made it rare). (3) Push left the request path: `NotificationDispatcher` stores,
      publishes to the hub and queues; `PushWorker` calls FCM in the background.
      Still there: FCM's own delivery delay to a phone (1–3 s, more in Doze).
- [ ] Optional, only if the phone still feels late: schedule a local exact alarm on Android for the
      current phase end, and dedupe the pushed notification.
- [x] Recurring "ends on" is a date **and an optional time**: the create-todo form shows "Last time at"
      next to the date, defaulting to "End of the day" (23:59 in the user's zone, what daily-and-slower
      rules want); picking a time ends the rule at that exact, inclusive moment ("every 2 hours until
      Friday 18:00"), and an end before the first occurrence is refused. No server change – `until` was
      always an instant. Budget recurring rules keep a plain end date: money rules are day-granular.
- [x] Notification centre: a 🔔 with an unread badge on Home (`/notifications/unread-count`, bumped live by
      the hub) opens the list of everything the server sent (reminders, phase changes), newest first,
      paged. Unread rows are highlighted; tapping one marks it read and opens its todo (`data.todoId`);
      "Mark all read" in one call. Reads are optimistic and resync from the server if the call fails;
      a notification arriving while the list is open lands on top, once.
- [x] Focus stats screen (Home → Stats): 7 / 30 / 90 days, tiles for focus and break time, sessions
      completed and ended early, daily average (idle days included) and best day, then one bar per
      calendar day in the account's time zone with idle days shown as gaps in the bars, not in the dates.
      Todo detail lists the five latest focus sessions (status, laps, focus minutes actually completed).
      Found on the way: `StringsCatalog` hit the JVM's 255-parameter limit (compiles, then
      `ClassFormatError` at startup). New areas use nested string groups (`FocusStatsStrings`); a size
      guard test fails with instructions before the wall.
- [x] Settings → **Email**: the address with its confirmed / not confirmed state, "send the confirmation link
      again", and an email change (a link goes to the new address; nothing changes until it is opened).
      Settings → **Devices**: every install signed in to the account with platform, push on/off and last
      seen, this device first; any other device can be removed. Building it exposed a dead server flow –
      see the known-bugs table – now fixed: the emailed link opens an anonymous, localized page
      (`GET /auth/confirm-email-change`), a second click is harmless, the token only works for the address
      it was sent to, and the previous address is told about the change.
- [x] Server error and validation texts speak en / fr / ht. The client sends its in-app language as
      `Accept-Language` on every call (so it works on the anonymous register and login screens too); the
      server words `detail` and every validation `message` in that language at the HTTP boundary, while
      `errorCode` and each entry's `code` stay the contract. Three layers in `ErrorTexts`: the exact English
      sentence, else one sentence per error type (for messages that carry an id), else English. ASP.NET
      Identity's password and username rules go through `LocalizedIdentityErrorDescriber`, which keeps their
      numbers and names. Guards: every error type must have its sentence, every Identity message must be
      translated, and a scan of `src/` fails the build on a static message without a translation (97 today).
      The Kreyòl wording is a first pass and wants the owner's native review.
- [x] Layout nits: Budget migrations moved to `Persistence/Migrations/` like every other module (EF keys
      migrations by id, so the move is invisible to the database) and the Budget project sits in the
      `src/modules` solution folder.

2. Before installing on your own devices

- [ ] Secure token storage: `SettingsTokenStore` keeps tokens in plain preferences → Keystore/Keychain
- [ ] Android release build: signing config + release keystore; align versions (Android `0.1.0` vs desktop
      `packageVersion 1.0.0`)
- [ ] iOS: builds only on a Mac, push deferred – V1 is realistically Android + desktop

3. Hosting – code side done, the rest is the owner's (docs/DEPLOYMENT.md)

- [x] Container image (`Dockerfile`, non-root, port 8080, trusts the proxy's forwarded headers) and
      `deploy/`: Docker Compose with Caddy (automatic HTTPS), the API, Postgres 17 and a nightly dump
      with two weeks kept. Verified by running the image against an empty throwaway database.
- [x] Production logging: `appsettings.json` has a Serilog console sink (the file only had the
      Microsoft `Logging` section, which Serilog ignores – production would have logged nothing).
- [x] Production settings that only existed in the Development file: `Jwt:Issuer`, `Jwt:Audience` and the
      token lifetimes (without them a production host issues tokens nobody can validate). Refresh tokens
      last 30 days in production, 7 in dev.
- [x] Migrations at deploy time: `Database:MigrateOnStartup` (on in the image, off in dev) applies every
      module's pending migrations as the single instance starts, before the admin seeding.
- [x] Fail fast: outside Development the API refuses to start on an incomplete configuration and lists
      everything missing at once (`ProductionConfiguration`, unit-tested in `tests/Kadans.Api.Tests`).
- [x] `/health/live` and `/health/ready` (Postgres round trip), anonymous.
- [x] Release builds talk to production: `-Pkadans.apiBaseUrl=https://api.<domain>` is baked in at build
      time (typed address → built-for address → dev default). The client code hardcodes no domain.
- [x] CI builds the image on every backend change (not pushed anywhere).
- [x] Owner: domain `kadansplanning.com`, Resend domain verified, a server, DNS `api` → it.
- [x] `deploy/.env.example` filled in for `api.kadansplanning.com` (domain, sender, public Google ids);
      DEPLOYMENT.md creates `.env` with secrets generated on the server and covers Cloudflare DNS (the
      `api` record must be DNS only). The config guard also refuses a Firebase key path that is not a
      file (Docker mounts a missing file as an empty directory).
- [x] First start on the server surfaced three problems, all fixed: the guide's `chmod 600` locked the API
      (uid/gid 1654) out of the Firebase key – now `chown $USER:1654` + `640`, and the config guard names an
      unreadable key; Data Protection keys lived inside the container, so every update would have broken
      the emailed links already sent – now in `identity.data_protection_keys`, encrypted under a key derived
      from `Jwt:Key`; Npgsql's Kerberos probe logged a scary `libgssapi_krb5` line – `GSS Encryption
      Mode=Disable` in the compose connection string. Rehearsed on a local copy of the server setup: a
      confirmation link sent before a container rebuild still works after it.
- [ ] Owner: `api` record DNS only, `deploy/.env` on the server, first start – see OWNER-CHECKLIST →
      Domain and hosting.
- [ ] After the first deploy: `android:usesCleartextTraffic="false"` for release builds (dev needs http),
      and https App Links / Universal Links for the emailed pages now that there is a domain.

4. Before opening sign-up to others. These come from the pre-launch review of 2026-09-30 and ship one
   pull request per step, in this order. Steps 1–4 are server-only and go live as each merges; 5–7 ship
   with the next app build.

- [x] Reminders on time and worded right. Production checked for due reminders every 60 s: only the
      Development file set 10 s, so a reminder came up to a minute late (measured: 48.7 s). The job now
      runs every 10 s everywhere, as the code default. The text rounded the time left down, so a 15-minute
      lead read "in 14 min" and an hour "in 59 min"; it now rounds up to the minute. A lead is at most
      30 days (a huge one was a 500), and an update can no longer blank the title. Measured after: the
      reminder 2–6 s after its notify time, reading "in 1 min", "in 15 min", "in 1 h".
- [x] Recurrence limits. A new rule repeats at most 5,000 times, ends within 10 years and fires at most
      every 5 minutes; hourly and minute rules take no hour, day or month parts. A calendar request
      covers at most a year. The engine stops expanding at what a caller keeps, and no longer walks a
      bounded rule to its last instance to learn whether it is finished. Budget rules repeat daily at most
      and start at most a year back. Measured: creating an hourly rule that ends in 10 years went from
      0.43 s to 0.05 s, and a year-wide calendar request takes 0.06 s. Found on the way, both in the
      known-bugs table: hourly and minute rules hung the server across an autumn DST change, and Budget
      skipped the backlog of rules backdated by more than 100 occurrences.
- [x] Google sign-in: linking rules for an account that already uses the same email. A sign-in lands on
      an existing account only through a verified address. An account whose address was never confirmed is
      taken over by the verified owner. Everything the unproven registrant set up (password, 2FA and
      recovery codes, other logins, sessions, devices, any lockout) goes first, in one transaction with
      the link. A sign-in without a verified address links and creates nothing. The first in-process
      integration tests run these flows on the real `UserManager` over in-memory SQLite (native SQLite
      pinned past GHSA-2m69-gcr7-jv3q), and `dotnet list package --vulnerable` is clean for every project.
- [x] Rate limiting on the anonymous and email-sending endpoints (moved up from nice-to-have). ASP.NET
      Core's limiter, per client address (IPv6 per /64), set in `RateLimiting`:
      - mail to a chosen address (sign-up, forgot password, resend confirmation, email change): 5 per
        15 minutes;
      - passwords and codes (sign-in, 2FA, Google, password change, 2FA settings): 20 a minute;
      - everything else: 300 a minute; health checks never.

      Rejections are a translated 429 (code 10053) with `Retry-After`. On top of the limits, one account
      mail per address every 2 minutes. The compose file now caps container logs at 5 × 10 MB per service;
      Docker kept them forever. Measured with production limits: the 6th mail request is refused with
      `Retry-After: 180`, the 21st sign-in attempt too, 50 health checks pass, and a second reset mail to
      the same address within 2 minutes is held back.
- [x] The profile follows the device (design: ARCHITECTURE → "The account's time zone and language follow
      the device"):
      - **New accounts.** A new account starts in the device's time zone and the app's language. Sign-up
        sends both, and so does a Google sign-in that creates the account. The server keeps a zone its tz
        database knows and en, fr or ht, else UTC and English.
      - **Every sign-in and app start.** The account follows the device's zone while "Follow this device"
        is on (per install, on by default). With it off, Settings has a searchable list of zones (city,
        region, current offset). The language follows the latest explicit choice, and a fresh install
        takes the account's language instead of the phone's.
      - **Todo form.** "Notify me" offers the start, 5, 10, 15 or 30 minutes, 1 hour or 1 day before, in
        create and edit. An older custom lead stays selectable.
      - **Recurrence limits.** The form keeps within step 2's limits: every 5 minutes at the least, at most
        5,000 repeats (said under the field), and an end date within 10 years in the date picker.
- [x] Sign-out and sessions (design: ARCHITECTURE → "A session ends everywhere at once"):
      - **Access ends with the session.** Access tokens name their session, and a token whose session has
        ended is refused on its next request. Before, it worked until it expired, up to 60 minutes: after
        a sign-out, a password change or a takeover. The check reads memory; measured, a request takes
        1.8 ms at the median.
      - **Every ending goes through one place.** That covers sign-out, sign out everywhere, a password
        change or reset, deactivation, a withdrawn role and a replayed refresh token. Each removes the
        device the session registered (no more pushes to a signed-out phone) and closes its live
        connections (0.04 s after "sign out everywhere").
      - **One account per phone.** An installation or push token belongs to one account at a time, so a
        phone signed into another account stops getting the first one's reminders.
      - **A deploy signs nobody out.** The app signs out only when the server refuses a refresh (400,
        401, 403). A restart, a rate limit or no network keeps the session. When the server does end it
        (signed out elsewhere), the app goes to sign-in from any screen and says why.
      - **Found on the way.** Refreshing with a token that was already signed out was logged as token
        theft. Now only a replayed rotated token counts as theft.
- [x] Account hardening (design: ARCHITECTURE → "Locks, and proving it is you again"):
      - **Email changes take the password.** Changing the email asks for the current password; accounts
        that only use Google have none to give.
      - **Wrong guesses count everywhere.** Wrong passwords and 2FA codes count toward the lock wherever
        they are typed: sign-in, changing the password or email, turning 2FA off, new recovery codes.
      - **Usernames and `@`.** Sign-in looks up an `@` as an address first. A username holds an `@` only
        when it is the account's own address, and follows it when it changes.
      - **Smaller changes.** Passwords need 8 characters. Lists answer 400 for page 0 or more than 100 a
        page (page 0 was a 500).
      - **Live connections end with their token.** A hub connection closes when its token expires
        (measured 0.5 s after, with 1-minute tokens). The app catches up on the notifications that arrived
        while it reconnected, so the desktop no longer loses a reminder pop-up to any gap: the hourly
        expiry, a deploy, or a network blip.
      - **Found on the way.** The lock after 5 wrong passwords was handled as a deactivation. Anyone
        typing wrong passwords against a username could sign its owner out of every device, block
        their Google sign-in and their reset email, and get them told the account was "deactivated".
        Now that lock only guards sign-in for 15 minutes (429 / 10054), and the reset link lifts it.
- [x] Pomodoro choices:
      - **Hands-free by default.** New sessions are hands-free by default, and each device remembers the
        last choice.
      - **Time's up.** Manual runs get one "time's up" notification at the end of each phase, naming
        what comes next ("Next: a 5-minute break, when you're ready"), and keep waiting for the person.
        Measured: 17 ms after the phase end. A phase that ended more than 15 minutes earlier is marked
        silently: the server was down, or the run was left at 0:00.
      - **The server's clock decides.** The app's "this phase ran out" (`onlyIfEnded`) advances a
        hands-free run only once the server's clock agrees. Asked early, the run comes back unchanged
        with no notification, so a phone whose clock runs fast no longer shortens phases. "Next phase"
        still skips at once.
- [x] Pomodoro polish, out of a review of looping sessions (decided 2026-10-03):
      - **A session ends by itself.** A looping session ends at its end time: 12 hours after the start by
        default, or a time picked when starting ("until 17:00"), and it can be moved while running. Before,
        a session nobody finished kept cycling, and a hands-free one notified all night. Measured: finished
        on its end time to the second, with one "the session ended at 08:35, as planned" notification. A
        session whose end passed long ago finishes silently.
      - **Stats count real time.** The time a phase really took, without its pauses. Skipping early counted
        the full planned length, and the phase under way when a session was finished counted nothing.
      - **A cycle builder.** "Focus 15, short break 5, 4 rounds, long break 30" fills in the eight phases.
      - **Cycle limits.** At most 24 phases of 1 to 240 minutes; before, any size was accepted.
- [x] Data retention (policy: ARCHITECTURE → "Data retention"). Nothing was ever deleted; one reminder every 5
      minutes alone wrote about 210,000 rows a year (occurrences plus notifications). Now one nightly job per
      module (07:30 UTC, and two minutes after a start) deletes 5,000 rows at a time. The day counts are
      configuration, and each run logs what it removed:
      - **Occurrences.** Those nobody acted on go 90 days after they were due. Completed, cancelled, moved or
        annotated ones stay as history.
      - **Cancelling a todo** deletes its untouched future occurrences, since they never happened. Moved or
        annotated ones are kept, cancelled. Missed past ones stay pending, without reminders, and age out
        like the rest.
      - **Notifications** go after 30 days.
      - **Sign-in tokens** go 7 days after they expire.
      - **Devices.** Those that cannot receive pushes go after 180 days unseen. A phone with a live push
        token stays, since someone may only ever see the reminders; Google reports an uninstalled app's token
        as dead.
      - **Kept until the person deletes them:** todos, Pomodoro history and every Budget record.

      Measured on seeded data: each rule removed exactly its old row and kept the recent, annotated, completed
      and reachable ones. Deleted data leaves the nightly backups within their 14 days.
- [x] Delete my account (design: ARCHITECTURE → "Account deletion"; decided 2026-10-03: a 7-day grace period):
      - **Closing.** Asked in Settings (with the password; an account that only uses Google confirms by an emailed
        link) or from the web page `/account/delete`, which Google Play requires: an address, the emailed link,
        and its button. The account closes at once: signed out everywhere, no devices, no live connections.
      - **The grace period.** A sign-in during the 7 days opens nothing but "Keep my account". Then everything
        is erased: every module's data, then the account, then a last email. The record of the erasure (id and
        dates) stays 30 days, so a restored backup can be cleaned again.
      - **Subscriptions.** The app and the emails say a store subscription must be cancelled in the store.
      - **One todo.** Deleting a single todo removes it with its history; cancelling stays for keeping it.
      - **Measured.** A fresh account with 720 occurrences, a Pomodoro run and cycle, budget rows, notifications
        and a session token was erased to zero rows in every module, with no orphans; the record and the last
        email remained.
- [ ] Subscriptions, decided 2026-10-02: the Android and iPhone apps are free to download and need a
      subscription, USD 0.99 a month after a 14-day free trial (the stores' own trial offer); the desktop app
      stays free. Not a paid download: it would charge twice for the same thing, allow no trial, and put the
      card problem at the door. A hard paywall (free download, subscription required) converts about 12% of
      downloads against about 2% for freemium (RevenueCat, 2025). Fallback if card friction blocks trials:
      freemium phones, free to use with the subscription unlocking phone reminders, which the server already
      gates. Both stores allow this model: an app may be free to download and need a subscription bought in
      it, and a subscription bought on one platform may be used on another as long as it is also sold in the
      app there.
      - **A `Billing` module** (`billing` schema) keeps one subscription state per account, fed only by the
        stores. The app's word is never enough: it hands over the purchase, and the server asks the store.
        Google: the Play Developer API, plus real-time notifications through Pub/Sub. Apple: the App Store
        Server API, plus signed server notifications checked against Apple's root certificate.
      - **Tied to the account.** Purchases carry the account (Google's obfuscated account id, Apple's app
        account token), so the subscription follows the person to a new phone, or from Android to iPhone.
      - **Mobile apps.** A paywall after sign-in until the account is subscribed, showing what both stores
        require: price, period, renewal terms, Terms and Privacy links, Restore purchases, and Manage
        subscription. The desktop app never shows it.
      - **On the server.** Reminders are pushed to phones only for subscribed accounts. The paywall alone
        could be patched out of an app package; the reminders are what a phone subscription buys. The
        desktop app and the live connection are unaffected.
      - **When it lapses.** The stores' grace period and account hold are honoured. After them, phones show
        the paywall again. No data is deleted, and the desktop app keeps working.
      - **Stores and Haiti.** Google Play sells in Haiti, both to buyers and from a Haitian seller account
        paid in USD. Haitian buyers probably need an international card, which is a conversion risk to
        measure. Apple has no App Store in Haiti: iPhone users there use another country's store, so the
        iPhone subscription is sold where Apple is (the diaspora's stores: United States, Canada, France).
      - **Decisions when the step starts.** Prices per country and Apple Family Sharing. Store trials ask
        for a card at the start and bill when the trial ends, so the Haiti card risk moves to the trial start.
        Owner setup: OWNER-CHECKLIST → Subscriptions.
- [ ] Performance at scale, the last gate before release: a load test on the finished backend, against a
      target to confirm (proposed: 50,000 accounts, 250,000 active todos, about 10 million occurrence rows,
      and 20,000 reminders due in the same minute, on the production server: 2 vCPU, 4 GB RAM). Pass when every
      reminder of that peak is stored and handed to push within a minute with none dropped, the main
      screens answer in under 200 ms at the 95th percentile, every job pass fits its interval, and no hot
      query scans a whole table. The seeding script and the measurements stay in the repo. Already
      visible in the code:
      - push goes out one user at a time and its queue drops the oldest beyond 1,000, so a peak loses
        pushes (Firebase accepts 500 messages per call);
      - a reminder pass sends at most 500 and then waits for the next pass;
      - the horizon job revisits every endless todo every hour to add at most a few rows;
      - the Budget job reads every active rule on every pass, 500 at a time, so with many rules a salary
        can be booked days late;
      - the calendar reads occurrences by todo and start instant, but the index is on the original
        instant.
- [x] Decided 2026-10-01: reminders every 5 s (was 10 s). Measured before the change with a 5-minute lead:
      one-time, daily and lead-edited todos were reminded 0.5 to 5.5 s after their moment, and a todo
      created inside its lead within one pass.
- [x] Out of the review, decided 2026-09-30: a dependency with a known high or critical vulnerability,
      even a transitive one, fails the restore (NuGet audit; NU1903/NU1904 as errors), in CI, in the
      image build and locally. Proven with the vulnerable SQLite library step 3 had to pin: restore fails
      on 2.1.11 and passes on 2.1.13.

Nice-to-have hardening

- [ ] Integration tests with Testcontainers (Phase 3 leftover); unit tests for Identity and Notifications

## Fixed along the way

- Npgsql rejects any `DateTimeOffset` with a non-zero offset (`timestamp with time zone`), so a client sending
  `09:00-05:00` produced a 500. Every DbContext now applies `StoreDateTimeOffsetsAsUtc()` (SharedKernel) and
  `RecurrenceSchedule` normalizes start/exceptions to UTC. Found by the Phase 1 smoke test, 2026-08-30.

## Known bugs in the current code

| Where | Problem |
|-------|---------|
| `clients/app`: sign-up and profile | ~~Sign-up sent neither the device time zone nor the app language, and nothing synced them later (Google-created accounts included), so the account stayed on UTC and English: reminders showed the start in UTC ("Starts at 01:28" for a 21:28 start in Port-au-Prince), server texts and emails were English, and focus-stats days and Budget month boundaries followed UTC~~ fixed 2026-10-01: new accounts start with the device's zone and the app's language, and the app brings existing accounts in line at its next start (ships with the next app build) |
| `clients/app` `ui/todos/EditTodoViewModel.kt` | ~~`save()` leaves `isSaving = true` on success; with ViewModels outliving nav entries the second edit of a todo shows a stuck spinner and re-sends a stale `pomodoroTemplateId`~~ fixed 2026-09-17: ViewModels are scoped to their nav entry (`rememberViewModelStoreNavEntryDecorator`) |
| `tools/smoke/*_flows.py` | ~~task, notification and pomodoro scripts logged in as `admin`, which has MFA in the dev database, and crashed on the challenge~~ fixed 2026-09-17: they default to the `smoke` user like the budget script, `[username] [password]` override |
| `tools/smoke/identity_flows.py` | ~~Not re-runnable: it registers `alice` and never removes her, so a second run against the same database fails at step one (`DuplicateUserName`)~~ fixed 2026-09-18: a fresh `alice<timestamp>` per run |
| Budget: `RecurringTransactionJob` | ~~Took 500 due rules per pass with no order (EF warned "row limiting operator without an 'OrderBy'" in the production log). Every active rule is due on every pass, so past 500 rules the same ones could be skipped each time~~ fixed 2026-09-30: least recently materialized first, never-materialized before all |
| Identity: emailed-link keys | ~~ASP.NET Core Data Protection kept its key ring in the container's home folder: every `docker compose up -d --build` would have generated new keys and made every confirmation, reset and email-change link already sent invalid~~ fixed 2026-09-30: keys in `identity.data_protection_keys`, encrypted (`KeyRingEncryption`) |
| Identity: `GET /auth/confirm-email` | ~~A broken or expired confirmation link answered with a JSON problem – what a person clicking it in a mail client saw~~ fixed 2026-09-30: a page in the account's language, like the email-change link |
| Tasks: reminder timing | ~~Production reminders came up to 60 s late: only `appsettings.Development.json` set `Tasks:ReminderIntervalSeconds` (10), and the code default was 60~~ fixed 2026-09-30: the default is 10 s |
| Tasks: reminder text | ~~A 15-minute lead read "in 14 min" and an hour "in 59 min": the time left was rounded down, and a pass always lands a few seconds after the notify time~~ fixed 2026-09-30: rounded up to the minute |
| Tasks: `PUT /todos/{id}` | ~~`notifyBeforeInMinutes` had no upper bound (a huge value overflowed the date arithmetic into a 500), and an update could blank the title~~ fixed 2026-09-30: a lead is at most 30 days (code 10052, create and update), and the title is required |
| SharedKernel: `RecurrenceSchedule` | ~~Ical.Net 5.2.3 never returns when it expands an hourly or minute rule across an autumn DST change in local time (Port-au-Prince 2026-11-01, Paris 2026-10-25; spring changes and daily rules are fine). The horizon job, creating such a todo, or a calendar spanning the change would hang with a CPU core spinning, and the 30-day horizon would have reached 1 November on 2 October~~ fixed 2026-09-30: hourly, minute and one-time rules are expanded in UTC; the smoke script checks the next fall-back |
| SharedKernel: `RecurrenceSchedule` | ~~A one-time todo at the second 01:30 of a fall-back night (01:30 EST, after 01:30 EDT) never materialized: local time named the first 01:30, an hour before the start, and it was filtered out~~ fixed 2026-09-30: a one-time rule is its own instant |
| Budget: `RecurringTransactionJob` | ~~A pass created at most 100 transactions per rule, then moved the marker to now: a rule backdated by more than 100 occurrences (the app's date picker allows past dates) silently lost the rest~~ fixed 2026-09-30: the next pass resumes after the last one created, and exhaustion is judged from there |
| Identity: lockout | ~~The 15-minute lock after 5 wrong passwords was handled as a deactivation: the next refresh of each of the owner's sessions ended it, Google sign-in and the reset email were refused, and the owner was told the account was deactivated. Anyone who knew a username could sign its owner out of every device that way~~ fixed 2026-10-02: that lock only guards sign-in (429 / 10054), and the reset link lifts it |
| Identity: external sign-in | ~~Account takeover prepared in advance. A Google sign-in with a verified address was linked to any account with that address, even one whose address was never confirmed, and password login does not require confirmation. So someone could register a victim's address with their own password, wait for the victim's first Google sign-in, and keep reading the victim's data with the password (2FA, devices and sessions they had set up stayed too). A sign-in with an unverified address was also stored on a new account, squatting that address~~ fixed 2026-09-30: the verified owner takes the unconfirmed account over after everything the registrant set up is removed; without a verified address nothing is linked or created |
| Identity: email change | ~~The emailed link pointed at `POST /users/me/email/confirm`, which needs a bearer token – no mail client or browser could ever complete it, so an email change could not be finished by a person. Unnoticed because the smoke script scraped the token from the log and called the API itself~~ fixed 2026-09-18: anonymous landing page, and the smoke script now opens the link like a browser |
| `Models/RecurrenceRule.cs` (old engine) | ~~Wrong hour for non-UTC offsets, DST not representable, `Interval > 1` misaligned~~ replaced by `RecurrenceSchedule` (Ical.Net) in Phase 0 |
| `Models/RecurrenceRule.cs` `CreateOneTimeRule` | ~~NRE in `GetOccurrences` (no ByHour/ByMinute)~~ fixed in Phase 0 |
