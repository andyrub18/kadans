#!/usr/bin/env bash
# The load test on the production server (docs/LOADTEST.md). Run from deploy/loadtest of a checkout of the branch.
#
#   ./loadtest.sh up                  build the image, start the throwaway database, let the API migrate it
#   ./loadtest.sh seed [users] [peak-at] [peak-count]    fill it (the API must be stopped: `up` leaves it so)
#   ./loadtest.sh sessions <tag>      a fresh session per account for the next run (k6's SESSION_TAG)
#   ./loadtest.sh peak <ISO instant> [count]   reminders due within that minute
#   ./loadtest.sh warmup              start this API off to the side (Caddy cannot reach it) to catch up its jobs
#   ./loadtest.sh switch-in           production's API stops; this one takes its place behind Caddy
#   ./loadtest.sh switch-out          and back: this one stops, production's API starts again
#   ./loadtest.sh report [minutes]    the verdict from Prometheus over the last N minutes (default 20)
#   ./loadtest.sh record <minutes> <file>   in the background: a one-minute report and docker stats every minute,
#                                     then the costliest queries (pg_stat_statements) when the time is up
#   ./loadtest.sh queries             the costliest queries since the last reset (pg_stat_statements)
#   ./loadtest.sh down                everything here goes, data included; production's API runs
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../.. && pwd)"
PROD="$(cd ../../../kadans/deploy 2>/dev/null && pwd || true)" # the production checkout, next to this one
compose() { docker compose --env-file .env -f compose.yml "$@"; }
API=kadans-loadtest-loadtest-api-1
# This API off production's "api" name, still on its network for Prometheus and Loki. A container keeps the aliases it
# was given: without this, a stopped API that once took production's place takes a share of its traffic when it starts.
unpublish() {
  docker inspect "$API" > /dev/null 2>&1 || return 0
  docker network disconnect kadans_default "$API" 2> /dev/null || true
  docker network connect --alias loadtest-api kadans_default "$API"
}
published() { docker inspect "$API" --format '{{json (index .NetworkSettings.Networks "kadans_default").Aliases}}' 2> /dev/null | grep -q '"api"'; }
seeder() {
  docker run --rm --network kadans-loadtest_default -v "$REPO":/src -v kadans-loadtest-nuget:/root/.nuget -w /src \
    mcr.microsoft.com/dotnet/sdk:10.0 dotnet run --project tools/Kadans.LoadTest.Seeder -c Release -- "$@" \
    --connection "Host=loadtest-db;Database=kadans_loadtest;Username=kadans;Password=$(grep ^LOADTEST_DB_PASSWORD= .env | cut -d= -f2)"
}

case "${1:-}" in
  up)
    [ -f .env ] || printf 'LOADTEST_DB_PASSWORD=%s\nLOADTEST_JWT_KEY=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 32)" > .env
    docker build -t kadans-api:loadtest "$REPO"
    compose up -d loadtest-db loadtest-api
    echo "waiting for the API to migrate the database..."
    until compose logs loadtest-api 2>/dev/null | grep -q "Application started"; do sleep 2; done
    compose stop loadtest-api
    echo "ready to seed";;
  seed)
    seeder seed --users "${2:-50000}" --history-days 90 ${3:+--peak-at "$3"} ${4:+--peak-count "$4"};;
  sessions)
    seeder sessions --tag "${2:?a tag, e.g. run1}";;
  peak)
    seeder peak --at "${2:?an ISO instant, e.g. 2026-10-08T01:00:00Z}" --count "${3:-20000}";;
  warmup)
    unpublish
    compose up -d loadtest-db loadtest-api
    unpublish
    if published; then echo "refusing: the load-test API still answers to \"api\"" >&2; compose stop loadtest-api; exit 1; fi
    until compose logs --since 2m loadtest-api 2> /dev/null | grep -q "Application started"; do sleep 2; done
    echo "warming up, out of Caddy's reach";;
  switch-in)
    docker compose --project-directory "$PROD" -p kadans stop api
    compose up -d loadtest-api
    docker network disconnect kadans_default kadans-loadtest-loadtest-api-1
    docker network connect --alias api kadans_default kadans-loadtest-loadtest-api-1
    echo "Caddy now sends https://api.kadansplanning.com to the load-test API";;
  switch-out)
    compose stop loadtest-api
    unpublish
    docker compose --project-directory "$PROD" -p kadans start api
    echo "production's API is back";;
  report)
    python3 report.py "${2:-20}";;
  record)
    minutes="${2:?minutes}"; file="${3:?a file}"
    setsid nohup bash -c "
      end=\$(( \$(date +%s) + $minutes * 60 ))
      while [ \$(date +%s) -lt \$end ]; do
        { echo \"===== \$(date -u +%T)\"; python3 report.py 1; docker stats --no-stream --format 'STAT {{.Name}} {{.CPUPerc}} {{.MemUsage}}'; } >> '$file' 2>&1
        sleep 50
      done
      { echo '===== costliest queries'; ./loadtest.sh queries; } >> '$file' 2>&1
    " > /dev/null 2>&1 < /dev/null &
    echo "recording to $file for $minutes minutes";;
  queries)
    docker exec -i kadans-loadtest-loadtest-db-1 psql -U kadans -d kadans_loadtest -P pager=off <<'SQL'
SELECT round(total_exec_time::numeric / 1000, 1) AS total_s, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       round(total_plan_time::numeric / 1000, 1) AS plan_s, rows, shared_blks_read AS disk_blocks,
       left(regexp_replace(query, '\s+', ' ', 'g'), 180) AS query
FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'kadans_loadtest')
ORDER BY total_exec_time DESC LIMIT 15;
SQL
    ;;
  down)
    compose stop loadtest-api || true
    unpublish
    compose down -v || true
    docker compose --project-directory "$PROD" -p kadans start api
    rm -f .env;;
  *)
    sed -n '2,12p' "$0"; exit 2;;
esac
