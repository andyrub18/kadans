# Load test

The last gate before release (ROADMAP → Phase 8 → Performance at scale): Kadans on its production server (2 vCPU,
4 GB RAM) with the data of 50,000 accounts, under the traffic of a busy hour, and the hardest moment the design
allows: 20,000 reminders due in the same minute. Measured with the API's own metrics (ARCHITECTURE →
Observability), so the network between the load generator and the server is not part of the numbers.

## Targets

| Target | Measured by |
|--------|-------------|
| Every reminder of the peak handed to push within a minute, none dropped | `kadans_reminder_lateness_seconds` p99, `kadans_push_dropped_total` |
| The main screens under 200 ms at the 95th percentile | `http_server_request_duration_seconds` by route |
| Every scheduled job's pass fits its interval | `kadans_job_duration_seconds` by job |
| No hot query scans a whole table | `pg_stat_statements` and `EXPLAIN` of the slowest |

## The data

`tools/Kadans.LoadTest.Seeder` writes it straight into a throwaway database with Postgres `COPY` (an API would spend
hours hashing passwords), with the app's own recurrence engine computing every occurrence:

- 50,000 accounts `lt0` … `lt49999` (password `LoadTest123!`), each with a live session and a phone with a push
  token; one in ten also has a desktop. Time zones: Port-au-Prince mostly, then New York, Toronto, Paris. Languages
  ht, fr, en.
- About 5 active todos each (250,000), on real rules: daily, weekdays, one to three days a week, every two days,
  monthly, one-time. Times are picked as people do, on the hour or the half hour, so reminders cluster. 60% remind,
  5 to 60 minutes ahead.
- Their occurrences over the last 90 days and the next 30 (the horizon): about 9.5 million rows. The past is mostly
  done, some cancelled, some untouched, and its reminders already sent.
- 30 notifications each in the notification centre (1.5 million), 70% read.
- A budget each: HTG base, cash and bank accounts (some USD savings), eight categories, three monthly limits, a
  salary and a rent that recur monthly (100,000 rules), about 60 transactions over three months (3 million).
- The peak, added for a run: 20,000 accounts each get a one-time todo whose reminder falls in the same minute.

## The traffic

`tools/loadtest/app.js` (k6), from a GitHub runner (`.github/workflows/loadtest.yml`): a home connection lacks the
bandwidth, and a generator on the server would take its CPU. Each virtual user is one account resuming its session.

- **People using the app**: Home (todos, the week, the bell), then one of: the month's calendar (30%), the budget
  (20%, sometimes adding an expense), the notification centre (15%), completing something due (15%), a new todo
  (10%), a todo's page (10%), with 3 to 20 seconds of reading between screens.
- **Apps left open**: one hub connection each for the whole run, receiving their notifications live. Opened as the
  app opens it (straight to the WebSocket), and when refused or dropped, opened again after 1, 2, 5, 10, then 30
  seconds, as the app does: a generator that reconnects at once would measure its own storm.
- **Push**: a simulated provider with Firebase's timing (500 messages per call, 150 ms a call): real Firebase would
  refuse the seeded tokens, and nothing must reach a real phone. It refuses to start outside a load test.

## Running it

On the server, from a checkout of the branch next to production's (`~/kadans-loadtest`):

```bash
cd ~/kadans-loadtest/deploy/loadtest
./loadtest.sh up                       # image, throwaway database, migrations
./loadtest.sh seed 50000               # ~15 minutes
./loadtest.sh sessions run1            # sessions for this run (a run's refreshes rotate them)
./loadtest.sh peak 2026-10-08T01:00:00Z 20000
./loadtest.sh switch-in                # production's API stops; this one takes its place behind Caddy
```

Then start the workflow (Actions → Load test, with the session tag), watch the Kadans dashboard (its instance is
`loadtest`), and afterwards:

```bash
./loadtest.sh report 25                # the verdict over the last 25 minutes
./loadtest.sh switch-out               # production's API is back
./loadtest.sh down                     # everything removed, data included
```

Alerts fire during a run (reminders late, pushes dropped): they are part of what is tested.

## Results

Times are UTC. "People" are virtual users going through the app's screens, "open apps" hold one hub connection each;
100% is 2,000 people and 3,000 open apps, the busy hour of 50,000 accounts.

### Run 1, 2026-10-07: the reminder peak loses pushes

200 people and 200 open apps (a mistake in the workflow ran k6's defaults), and the peak of 20,000 reminders.
Requests were easy (23 a second, p95 17 ms); the peak failed both targets. 19,912 of the 20,000 reminders went out,
half of them over 70 s late, the last 141 s late (11,388 later than a minute). Push dropped 18,615 messages and
delivered 2,796, the 95th percentile after 198 s. The code did what ROADMAP had predicted: a reminder pass sent at
most 500 and then waited for the next one, push called the provider once per user, and its queue kept 1,000.

Changed: a reminder pass drains everything due in batches of 500 (one query for the users, one save per batch, at
most two minutes a pass), and push sends 500 messages per provider call, Firebase's maximum, from four workers and a
queue of 50,000.

### Run 2, 2026-10-08: the API saturates

The target traffic at once (one runner: only 55 of its hub connections got through Azure's outbound address
translation, hence the eight runners since). With the new reminder path, the peak's 21,225 reminders and 21,239
pushes went out with none dropped, push 4.9 s from queue to provider at the 95th percentile. But the API was
saturated: 231 requests a second, p95 5.8 s, p99 10 s, the server's CPU at 99.8%, all 100 database connections in
use with 496 requests waiting for one, and so the reminders were late anyway (p50 140 s, p99 300 s).

Changed: prepared statements (Npgsql's automatic preparation: planning was two thirds of a typical query's time), a
pool of 40 connections instead of 100 (more connections cost Postgres more than they brought), Postgres sized for
the server (`shared_buffers`, SSD costs, no JIT), and Budget's recurring job reads only the rules that are due (an
indexed next date) instead of every active rule on every pass.

### Run 3, 2026-10-08: stepped load, interrupted

The load in steps of a quarter, to find where it breaks. The first step: 143 requests a second, p95 19 ms. Stopped
for the night.

### Run 4, 2026-10-08: the knee, and the collapse past it

Steps of 25% (3 minutes up, 4 held), the peak due at 18:57. Per minute, server side:

| Step | Requests/s | p95 | p99 | Server CPU | Memory available | Open apps |
|------|-----------:|----:|----:|-----------:|-----------------:|----------:|
| 25% | 122–130 | 15–18 ms | 24 ms | 50% | 40% | 752 |
| 50% | 247–262 | 25–56 ms | 49–190 ms | 77–83% | 18–24% | 1,504 |
| ramp to 75% | 288 | 814 ms | 2.2 s | 95% | 13% | 1,839 |
| a minute later | 255 | 9.4 s | 10 s | 99.9% | 6.9% | 2,141 |
| three minutes later | 27 | 10 s | 10 s | 99.4% | 7.9% | 1 |

Up to 50% everything met its target. Past about 280 requests a second the API took on more than it could finish:
every request slowed every other, the thread pool's queue reached 2,016 items, requests waited 15 s for a database
connection and failed (1,239 errors, many of them in the session check that runs before any endpoint), memory ran
low, and the open apps lost their connections and all came back at once. The run was stopped at 18:53, before the
peak, and production's API put back. Where the CPU went at 50%: the API about one core of the two, Caddy (TLS) 0.3 to
0.4, Postgres 0.25. Postgres was not the bottleneck: its costliest statement, the calendar's occurrences, averaged
1.7 ms (`pg_stat_statements`; none of the hot ones scans a table), and the most frequent was Npgsql resetting each
connection it took back (`SET SESSION AUTHORIZATION DEFAULT`, 352,530 times). One query took 18 s, once: the
nightly notification cleanup, two minutes after the API started, when Postgres planned it with the primary key's
index (`ORDER BY id LIMIT`); planned with its values, it takes the `created_at` index and 4 ms.

### What changed after run 4

The API's CPU per request, profiled on a workstation: the API pinned to two cores, the database seeded like the
server's, the same k6 load (150 people, 250 open apps) after a warm-up, CPU time divided by requests answered.

| | ms of CPU per request |
|---|---:|
| before | 3.99 |
| each DbContext's options built once instead of per request; the read-only queries untracked; no reset of a connection returned to the pool | 3.66 |
| and the thread pool's idle threads waiting without spinning first | 2.44 |
| (workstation instead of server GC: no gain, not taken) | 3.68 |

The profile (`dotnet-trace`) showed why the last one counts on two cores: idle worker threads spun for work, and
that spinning was about a quarter of the API's CPU.

And admission control (ARCHITECTURE → Rate limiting, admission control): at most 32 requests in progress, up to 128
waiting at most 2 s, the rest answered at once with 503 and `Retry-After`. Past capacity the server now refuses the
excess and keeps answering the rest at full speed, instead of accepting everything and answering nothing.

### Run 5

(To come: the same steps with these changes, the peak in the 50% step.)
