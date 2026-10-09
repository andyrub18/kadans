# Kadans – Deployment

How the API gets from this repository to a public `https://api.<your-domain>` that the apps talk to.
Everything the code can do is done (`Dockerfile`, `deploy/`); what is left needs accounts, money and DNS,
which only the owner has. Accounts and keys themselves are tracked in [OWNER-CHECKLIST.md](OWNER-CHECKLIST.md).

## What is being deployed

One container (the API) and one Postgres database. **Exactly one API instance, always on** – this is a
constraint, not a preference: the Quartz scheduler, the pomodoro deadline watcher, the push queue and the
SignalR hub all live in that process, in memory. That rules out anything that scales to zero or runs
several replicas (serverless containers in their default mode), and it is why migrations can safely run
at startup.

The image is host-neutral. Configuration is environment variables only; nothing secret is in the image.

## Where to host it

Recommendation: **a small VPS in the eastern US (Miami if offered, else Virginia / New York) running
`deploy/docker-compose.yml`**. Roughly 2 GB RAM is comfortable for the API plus Postgres; production runs on 2 vCPU and 4 GB.

| Option | Fits Kadans? | Trade-off |
|---|---|---|
| **VPS + Docker Compose** (Hetzner, DigitalOcean, Vultr, …) | Yes – always on, WebSockets, Postgres on the same box, lowest price of the three | You are the operator: OS updates, backups off the box (both covered below) |
| **Container PaaS** (Fly.io, Render, Railway) | Yes, if pinned to one always-on instance | Less to operate, TLS and deploys handled; managed Postgres usually costs more than the whole VPS |
| **Big cloud** (Cloud Run + Cloud SQL, Azure App Service + Postgres) | Works, but needs "min instances = 1 / always allocated CPU" | Several times the price for nothing a personal app needs yet |

Why the VPS: Kadans is personal-first and low-traffic, the one-instance constraint makes a PaaS's main
strength (scaling) irrelevant, and the compose file below is the entire operations story. Latency
matters for the pomodoro hub: from Haiti, Miami is the closest major region, then the US east coast.
Moving later is cheap – any other host takes the same `Dockerfile` and the same variables
(see [Other hosts](#other-hosts)).

Check current prices yourself; at the time of writing a suitable VPS is in the range of a few dollars
to about ten a month.

## Names and DNS

- **`api.<domain>`** → the API. It is what the apps are built for, what emailed links point at
  (`Email:LinkBaseUrl` – the API serves those pages) and what the Google consent screen can list.
  Keep the bare domain free for a future website or web app.
- One `A` record (and `AAAA` if the server has IPv6): `api` → the server's address.
- Resend: verify the domain in Resend and add the DNS records it shows (SPF/DKIM, optionally DMARC);
  then `EMAIL_FROM` can be `Kadans <no-reply@<domain>>`.

Kadans lives at **`api.kadansplanning.com`** (`deploy/.env.example` is filled in for it).

### DNS on Cloudflare: the `api` record is "DNS only"

Cloudflare creates records **proxied** (orange cloud) by default: the name then resolves to Cloudflare's
addresses, not the server's, and Cloudflare answers in front of it. Let's Encrypt must reach the server
directly to issue and renew its certificate, so set the `api` record to **DNS only** (grey cloud) – then the name
resolves to the server itself (`getent hosts api.<domain>` shows the server's address).

- A `521` with `Server: cloudflare` means Cloudflare cannot reach the server: the stack is not running
  yet, or ports 80/443 are closed. It is not a Kadans error.
- Nothing in Kadans needs the proxy. To add it later anyway: SSL/TLS mode **Full (strict)**, set only
  after the server has its certificate. Never "Flexible" – Cloudflare would talk plain HTTP to nginx, which
  redirects to HTTPS, an endless loop. Behind the proxy the API logs Cloudflare's addresses instead of
  the users', and its per-client rate limits would count every user behind the same Cloudflare address as
  one client. So stay DNS only, or first teach the API Cloudflare's client-address header.

## First deployment (VPS)

On a fresh Ubuntu LTS server, as a non-root user with sudo:

```bash
# 1. Docker + firewall
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER" && newgrp docker
sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable

# 2. The code and its configuration. deploy/.env is not in the repository (it holds the secrets):
#    it is made here from the template, with random secrets generated on the server itself.
git clone https://github.com/andyrub18/kadans.git && cd kadans/deploy
sed -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 32)/" \
    -e "s/^JWT_KEY=.*/JWT_KEY=$(openssl rand -hex 32)/" .env.example > .env
nano .env                       # RESEND_API_KEY, GOOGLE_DESKTOP_CLIENT_SECRET (domain and ids are filled in)
mkdir -p secrets backups
#    The Firebase service-account key – the same file as ~/.kadans/firebase-admin.json in dev. Firebase
#    names the download kadans-420a7-firebase-adminsdk-….json; on the server it must be named exactly
#    firebase-admin.json (the compose file mounts the secrets folder; that is the name it reads). From your computer:
#      scp ~/.kadans/firebase-admin.json <user>@<server>:kadans/deploy/secrets/firebase-admin.json
#    (or `echo '{}' > secrets/firebase-admin.json` and PUSH_PROVIDER=Log to start without push).
#    It must exist before step 3: the API refuses to start without it.
chmod 600 .env
#    The API runs as uid/gid 1654 inside the container, not as you: give that group read access
#    (600 locks it out – "Access to the path '/run/secrets/firebase-admin.json' is denied").
sudo chown "$USER":1654 secrets/firebase-admin.json && chmod 640 secrets/firebase-admin.json

# 3. Start. The DNS record must already point here (DNS only on Cloudflare): certbot fetches the
#    certificate on first start, and nginx serves HTTPS about a minute later (Proxy and certificates).
docker compose up -d --build
docker compose logs -f api      # "applying N migration(s)" on the first start, then "Now listening on"
```

The API refuses to start with an incomplete configuration and lists **everything** that is missing in
one message (including a Firebase key path that is not a file, or a key the container cannot read), so
there is no restart-per-mistake loop. If `secrets/firebase-admin.json` did turn into a directory,
`sudo rm -r secrets/firebase-admin.json`, put the file there, and `docker compose up -d` again.

On the very first start only, each module logs `[ERR] Failed executing DbCommand … __ef_migrations_history`
twice before "applying N migration(s)": EF Core looks for its history table before creating it. Expected,
not an error; it does not come back once the schema exists.

First start only: set `INITIAL_ADMIN_ENABLED=true` with an email and a strong password, start, sign in,
turn on two-factor authentication, then set it back to `false` and `docker compose up -d`.

### Check it

```bash
curl https://api.<domain>/health/ready     # Healthy  (503 when Postgres is unreachable)
curl https://api.<domain>/health/live      # Healthy  (the process answers)
curl https://api.<domain>/auth/providers   # the Google client ids – never a secret
```

The interactive API reference (`/scalar`) is Development-only and is not served in production.
Point an uptime monitor at `/health/ready`.

## Building the apps for this server

Dev builds talk to localhost. A release build is told where production is, once, at build time:

```bash
cd clients/app
./gradlew :androidApp:assembleRelease  -Pkadans.apiBaseUrl=https://api.<domain>
./gradlew :desktopApp:packageDeb       -Pkadans.apiBaseUrl=https://api.<domain>   # or packageMsi / packageDmg
```

Put `kadans.apiBaseUrl=https://api.<domain>` in `~/.gradle/gradle.properties` on the release machine to
stop typing it. The Login screen's server field still overrides it on a device (order: typed address →
built-for address → dev default).

Google Play takes an Android App Bundle signed with your **upload key** (Play App Signing keeps the real app
signing key). Create the upload key once, keep it and its password out of the repository and backed up (losing it
means asking Google to reset it), and tell Gradle where it is in `~/.gradle/gradle.properties`:

```bash
keytool -genkeypair -keystore ~/.kadans/upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

```properties
kadans.upload.storeFile=/home/<you>/.kadans/upload.jks
kadans.upload.storePassword=…
kadans.upload.keyAlias=upload
kadans.upload.keyPassword=…
```

```bash
./gradlew :androidApp:bundleRelease -Pkadans.apiBaseUrl=https://api.<domain> -Pkadans.versionCode=1
# → androidApp/build/outputs/bundle/release/androidApp-release.aab, uploaded in Play Console
```

Every upload needs a higher `kadans.versionCode` (1, 2, 3, …). Without the upload properties the bundle comes out
unsigned and Play Console refuses it.

## Updating

```bash
cd kadans && git pull && cd deploy && docker compose up -d --build
```

Pending migrations are applied as the new container starts (`Database:MigrateOnStartup`, set by the
image; the log names each one). Migrations only go forward: to undo a bad release, check out the previous
commit, and if its schema is older, restore the last dump first.

## Proxy and certificates

nginx terminates TLS in front of the API (`deploy/nginx/`), and certbot gets the Let's Encrypt certificate and
renews it; both are services in the compose file. Until October 2026 it was Caddy: the load test measured nginx doing
the same work (TLS, HTTP/2, gzip, WebSockets) with half of Caddy's CPU per request (docs/LOADTEST.md).

- **First start.** nginx answers on port 80 only (Let's Encrypt's challenge, and a redirect to HTTPS for everything
  else) until certbot has the certificate; within a minute of it, nginx serves HTTPS
  (`docker compose logs nginx` → `kadans: new certificate, nginx reloaded`). It needs the DNS record pointing here
  and port 80 open; until then certbot retries every 15 minutes (`docker compose restart certbot` tries at once), and
  `docker compose logs certbot` says why.
- **Renewal.** certbot checks twice a day and renews well before the end; nginx reloads by itself. The expiry date is
  a metric (`kadans_tls_certificate_expiry_timestamp_seconds`, the dashboard's "Certificate expires in"), and the
  alert "Certificate expiring" fires 14 days before it or when there is no certificate at all.
- **Keep the `letsencrypt` volume**: the certificate and the Let's Encrypt account. Re-issuing the same name is
  rate-limited (5 a week).
- **What nginx does**: TLS 1.2 and 1.3 (Mozilla's intermediate profile), HTTP/2, gzip for the apps' JSON, HSTS,
  WebSockets for the hub, no version in the `Server` header, no access log (the metrics count requests). Its main
  configuration (`deploy/nginx/nginx.conf`) allows 16,384 connections per worker: each open app holds one, and nginx
  a second to the API. The image's default, 1,024, refused new connections past about 1,000 open apps. It puts the client's own address in `X-Forwarded-For`,
  replacing whatever the client sent: the rate limits are per client, and a client must not pick its own. It asks
  Docker's DNS for the API's address every 10 s, so a recreated API container is found again.
- **Moving from Caddy** (once, on a server that ran it): `git pull`, `docker rm -f kadans-caddy-1`, then
  `docker compose up -d --build --remove-orphans`. Remove Caddy first: it holds ports 80 and 443, and on the first
  server nginx, started while Caddy still had them, came up running but without its network (no ports, no DNS).
  `docker port kadans-nginx-1` must list 80 and 443; if it lists nothing,
  `docker compose up -d --force-recreate --no-deps nginx`. HTTPS is back about a minute later. Once it is,
  `docker volume rm kadans_caddy_data kadans_caddy_config`.

## Subscriptions

Off until the Play product is live (`BILLING_REQUIRED=false`): no paywall, and every phone gets its reminders. To
switch them on, once OWNER-CHECKLIST → Subscriptions is done:

```bash
cd deploy
# the Play Developer API service account key, readable by the API like the Firebase key
scp play-developer-api.json <user>@<server>:kadans/deploy/secrets/play-developer-api.json
sudo chown "$USER":1654 secrets/play-developer-api.json && chmod 640 secrets/play-developer-api.json
nano .env   # BILLING_REQUIRED=true
            # PLAY_SERVICE_ACCOUNT_FILE=/run/secrets/play-developer-api.json
            # PLAY_NOTIFICATIONS_SERVICE_ACCOUNT=<the Pub/Sub push subscription's service account>
docker compose up -d
```

With `BILLING_REQUIRED=true` the API refuses to start if the key or the notification settings are missing. The
Pub/Sub push subscription points at `https://<domain>/billing/google/notifications`, with authentication on (that
service account) and the same URL as audience.

### Free accounts

Some accounts get their phones free, with no store involved: the closed test's testers, the account Google's
reviewers sign in with (Play Console → App access), family. The person signs up (and confirms their address) first;
then, from any machine with the repository:

```bash
python3 tools/admin/free_accounts.py add marie              # a username, or a confirmed email address
python3 tools/admin/free_accounts.py list
python3 tools/admin/free_accounts.py remove marie@example.com
```

It signs in as `admin` (`--admin` for another admin account), asking for the password and the two-factor code, does
that one thing and signs out. It talks to `https://api.kadansplanning.com` (`--api` or `KADANS_API` for another
server). A change applies at once, without a restart: the person opens the app again, or taps "Restore my purchase"
on the paywall, and is in; Settings then says "Free access". An address only finds an account once it is confirmed,
since anyone can sign up with an address that is not theirs. Removing an account takes the access away at once.

The list lives in the database (`billing.free_accounts`), so the backups keep it. (A first version read it from
`BILLING_FREE_ACCOUNTS` in `deploy/.env`; that is no longer read and can be deleted from it.)

## Backups

The `backup` service writes one compressed dump a day to `deploy/backups/` and keeps two weeks, so data the nightly
retention deletes (ARCHITECTURE → Data retention) is gone from the server two weeks later. That
protects against a bad migration or a mistake – **not** against losing the server. Copy the folder off
the machine on a schedule (provider snapshots, `rclone` to object storage, or `scp` from another computer).

```bash
# restore the newest dump into a running stack (replaces the data)
cd deploy && docker compose stop api
gunzip -c backups/$(ls -t backups | head -1) | docker compose exec -T db psql -U kadans -d kadans -v ON_ERROR_STOP=1 --single-transaction
docker compose start api
```

The dump drops and recreates every Kadans table before loading, inside one transaction: either the
whole restore applies or nothing changes. Test a restore once before you need one.

A dump can hold accounts erased since it was taken (ARCHITECTURE → Account deletion). Save the list of erased
accounts **before** restoring, and hand it back after: the erasure job erases them again within 15 minutes.

```bash
# before the restore
docker compose exec -T db psql -U kadans -d kadans -At -c "SELECT user_id FROM identity.account_deletions WHERE erased_at IS NOT NULL" > erased.txt
# after the restore (and after `docker compose start api`)
while read id; do
  docker compose exec -T db psql -U kadans -d kadans -c "INSERT INTO identity.account_deletions (user_id, requested_at, erase_after) VALUES ('$id', now(), now()) ON CONFLICT (user_id) DO UPDATE SET erased_at = NULL, erase_after = now();"
done < erased.txt
```

## Monitoring

Prometheus, Loki and Grafana run next to the API (ARCHITECTURE → Observability). They need three lines in
`deploy/.env` (an `.env` made before them lacks them, and `docker compose up` says so):

```bash
cd deploy
echo "GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 24)" >> .env
echo "ALERT_EMAIL=you@example.com" >> .env            # where alerts go
echo "ALERT_FROM_ADDRESS=alerts@kadansplanning.com" >> .env   # on the Resend-verified domain; a bare address
docker compose up -d
```

Grafana is never on the internet: it listens on the server's loopback. From your machine:

```bash
ssh -L 3000:127.0.0.1:3000 <you>@<server>     # then http://localhost:3000, user admin, GRAFANA_ADMIN_PASSWORD
```

- **Dashboard:** the home page after signing in (also Dashboards → Kadans → Kadans).
- **Alerts:** Alerting → Alert rules (twelve, provisioned from `deploy/observability/grafana/provisioning/alerting/`).
  Alerting → Contact points → owner → Test sends a test email: do it once after the first start.
- **Searching logs:** Explore → Loki. Every property of an event is a field, nested ones joined with `_`:

  ```logql
  {service_name="kadans-api"} | detected_level="error"
  {service_name="kadans-api"} | RequestPath="/todos" | StatusCode >= 500
  {service_name="kadans-api"} | UserId="<id>"
  sum by (RequestPath) (count_over_time({service_name="kadans-api"} | StatusCode >= 400 [1h]))
  ```

- **Numbers over time:** Explore → Prometheus, e.g. the p95 of each route over 5 minutes:

  ```promql
  histogram_quantile(0.95, sum by (le, http_route) (rate(http_server_request_duration_seconds_bucket[5m])))
  ```

If the tunnel answers "connect failed: Connection refused", Grafana is not running on the server: `docker compose ps
grafana` and `docker compose logs --tail 30 grafana` say why (its last line names a bad setting, e.g. an
`ALERT_FROM_ADDRESS` written as `Name <address>`).

Retention is 30 days for both (Prometheus also stops at 2 GB). The dashboard and the alerts are files in the
repository: edit them there and `docker compose up -d` (Grafana does not keep changes made in its UI to them).

On a development machine the same stack runs with `docker compose -f deploy/observability/compose.dev.yml up -d`:
Grafana at http://localhost:3000 without a login, alert emails caught by Mailpit at http://localhost:8025, and the API
started with `dotnet run` (Development) already sends there.

## Operating

- Logs: Grafana → Explore → Loki (Monitoring above), or `docker compose logs -f api`: Serilog writes to the console
  too, and Docker keeps 5 × 10 MB per service (`x-logging` in the compose file), so a flood cannot fill the disk.
  Successful, fast requests are not logged; the metrics count them.
- Rate limits, per client address (the real one: nginx replaces `X-Forwarded-For` with it; IPv6 counts per /64).
  Sign-up, forgot password, resend confirmation and email change: 5 per 15 minutes. Sign-in, 2FA codes and
  password change: 20 a minute. Everything else: 300 a minute. Health checks are never limited. On top of
  that, one confirmation, reset or email-change mail per address every 2 minutes. Over a limit the API answers
  429 with `Retry-After` and logs `Rate limit reached by <client> on <request>`
  (`docker compose logs api | grep "Rate limit"`). To change a number, add it to the `api` service's
  `environment` in the compose file, e.g. `RateLimiting__CredentialsPerMinute: "40"`.
- Server busy (admission control, ARCHITECTURE → Rate limiting): the API works on 32 requests at a time and lets 128
  more wait up to 2 s; past that it answers 503 "The server is busy" with `Retry-After: 5`, logs `Server busy: <n>
  request(s) turned away` at most every 10 s, and the "Server busy" alert fires. Once after a burst is survivable;
  daily means the server is too small for its users (the load test's capacity: docs/LOADTEST.md). The numbers are
  `Admission__MaxConcurrentRequests`, `Admission__QueueLimit`, `Admission__QueueTimeoutMilliseconds` and
  `Admission__RetryAfterSeconds` in the `api` service's `environment`. Keep `MaxConcurrentRequests` below the database
  pool (40): above it, requests wait for a connection instead, up to 15 s, and fail there.
- Postgres is tuned for this server in the compose file (`db` → `command`: cache, SSD costs, no JIT). With more memory,
  raise `shared_buffers` to a quarter of it and `effective_cache_size` to three quarters. The costliest queries:

  ```bash
  docker compose exec db psql -U kadans -d kadans -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"   # once
  docker compose exec db psql -U kadans -d kadans -c "SELECT round(total_exec_time) AS ms, calls, left(query, 120)
    FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10"
  ```
- OS updates: `sudo apt update && sudo apt upgrade`, and `unattended-upgrades` for security patches.
- Certificates: certbot renews them (Proxy and certificates above); keep the `letsencrypt` volume.
- Secrets live only in `deploy/.env` and `deploy/secrets/` on the server (both git-ignored). Changing
  `JWT_KEY` signs everyone out and invalidates emailed links not yet opened; changing `POSTGRES_PASSWORD`
  in `.env` does not change the password already stored in the database volume.
- Emailed links (confirm email, reset password, confirm a new email) carry tokens protected by ASP.NET
  Core Data Protection. Its key ring is in the database (`identity.data_protection_keys`), encrypted with
  a key derived from `JWT_KEY`: links survive updates, and a database dump alone cannot forge them.

## Other hosts

Any platform that runs a container works with the same `Dockerfile`. Requirements to carry over:

- one instance, always on, no scale-to-zero; WebSockets allowed;
- the platform terminates TLS and forwards to port `8080` (the image already trusts `X-Forwarded-*`);
- a Postgres 16+ database, its connection string in `ConnectionStrings__kadans` (append
  `;GSS Encryption Mode=Disable` unless the database uses Kerberos – otherwise Npgsql logs
  "Cannot load library libgssapi_krb5.so.2" at start, harmless but alarming);
- the variables listed in `deploy/docker-compose.yml` under `api.environment` (double underscore = `:`);
- the Firebase key as `Push__Firebase__CredentialsJson` (the whole JSON in one secret) when mounting a
  file is not possible;
- a health check on `/health/ready`.
