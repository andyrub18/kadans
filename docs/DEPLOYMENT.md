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
addresses, not the server's, and Cloudflare answers in front of it. Caddy expects to be reached directly
to obtain and renew its certificate, so set the `api` record to **DNS only** (grey cloud) – then the name
resolves to the server itself (`getent hosts api.<domain>` shows the server's address).

- A `521` with `Server: cloudflare` means Cloudflare cannot reach the server: the stack is not running
  yet, or ports 80/443 are closed. It is not a Kadans error.
- Nothing in Kadans needs the proxy. To add it later anyway: SSL/TLS mode **Full (strict)**, set only
  after Caddy has its certificate. Never "Flexible" – Cloudflare would talk plain HTTP to Caddy, which
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
#    firebase-admin.json (the compose file mounts that name). From your computer:
#      scp ~/.kadans/firebase-admin.json <user>@<server>:kadans/deploy/secrets/firebase-admin.json
#    (or `echo '{}' > secrets/firebase-admin.json` and PUSH_PROVIDER=Log to start without push).
#    It must exist before step 3: Docker turns a missing file into an empty directory.
chmod 600 .env
#    The API runs as uid/gid 1654 inside the container, not as you: give that group read access
#    (600 locks it out – "Access to the path '/run/secrets/firebase-admin.json' is denied").
sudo chown "$USER":1654 secrets/firebase-admin.json && chmod 640 secrets/firebase-admin.json

# 3. Start. The DNS record must already point here (DNS only on Cloudflare): Caddy fetches the
#    certificate on first start.
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

## Updating

```bash
cd kadans && git pull && cd deploy && docker compose up -d --build
```

Pending migrations are applied as the new container starts (`Database:MigrateOnStartup`, set by the
image; the log names each one). Migrations only go forward: to undo a bad release, check out the previous
commit, and if its schema is older, restore the last dump first.

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

## Operating

- Logs: `docker compose logs -f api` (Serilog writes to the console). Docker keeps 5 × 10 MB per service
  (`x-logging` in the compose file), so a flood of requests cannot fill the disk.
- Rate limits, per client address (the real one: Caddy sets `X-Forwarded-For` itself; IPv6 counts per /64).
  Sign-up, forgot password, resend confirmation and email change: 5 per 15 minutes. Sign-in, 2FA codes and
  password change: 20 a minute. Everything else: 300 a minute. Health checks are never limited. On top of
  that, one confirmation, reset or email-change mail per address every 2 minutes. Over a limit the API answers
  429 with `Retry-After` and logs `Rate limit reached by <client> on <request>`
  (`docker compose logs api | grep "Rate limit"`). To change a number, add it to the `api` service's
  `environment` in the compose file, e.g. `RateLimiting__CredentialsPerMinute: "40"`.
- OS updates: `sudo apt update && sudo apt upgrade`, and `unattended-upgrades` for security patches.
- Certificates: Caddy renews them; keep the `caddy_data` volume.
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
