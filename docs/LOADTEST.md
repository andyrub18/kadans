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
- **Apps left open**: one hub connection each for the whole run, receiving their notifications live.
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

(Filled in by each run.)
