#!/usr/bin/env bash
# The load test on the production server (docs/LOADTEST.md). Run from deploy/loadtest of a checkout of the branch.
#
#   ./loadtest.sh up                  build the image, start the throwaway database, let the API migrate it
#   ./loadtest.sh seed [users] [peak-at] [peak-count]    fill it (the API must be stopped: `up` leaves it so)
#   ./loadtest.sh sessions <tag>      a fresh session per account for the next run (k6's SESSION_TAG)
#   ./loadtest.sh peak <ISO instant> [count]   reminders due within that minute
#   ./loadtest.sh switch-in           production's API stops; this one takes its place behind Caddy
#   ./loadtest.sh switch-out          and back: this one stops, production's API starts again
#   ./loadtest.sh report [minutes]    the verdict from Prometheus over the last N minutes (default 20)
#   ./loadtest.sh down                everything here goes, data included; production's API runs
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../.. && pwd)"
PROD="$(cd ../../../kadans/deploy 2>/dev/null && pwd || true)" # the production checkout, next to this one
compose() { docker compose --env-file .env -f compose.yml "$@"; }
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
  switch-in)
    docker compose --project-directory "$PROD" -p kadans stop api
    compose up -d loadtest-api
    docker network disconnect kadans_default kadans-loadtest-loadtest-api-1
    docker network connect --alias api kadans_default kadans-loadtest-loadtest-api-1
    echo "Caddy now sends https://api.kadansplanning.com to the load-test API";;
  switch-out)
    compose stop loadtest-api
    docker compose --project-directory "$PROD" -p kadans start api
    echo "production's API is back";;
  report)
    python3 report.py "${2:-20}";;
  down)
    compose down -v || true
    docker compose --project-directory "$PROD" -p kadans start api
    rm -f .env;;
  *)
    sed -n '2,12p' "$0"; exit 2;;
esac
