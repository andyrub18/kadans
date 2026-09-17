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
`deploy/docker-compose.yml`**. Roughly 2 GB RAM is comfortable for the API plus Postgres.

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

## First deployment (VPS)

On a fresh Ubuntu LTS server, as a non-root user with sudo:

```bash
# 1. Docker + firewall
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER" && newgrp docker
sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable

# 2. The code and its configuration
git clone https://github.com/andyrub18/kadans.git && cd kadans/deploy
cp .env.example .env
nano .env                       # KADANS_DOMAIN, POSTGRES_PASSWORD and JWT_KEY (openssl rand -hex 32), Resend, Google ids
mkdir -p secrets backups
nano secrets/firebase-admin.json   # paste the Firebase service-account JSON (or `{}` with PUSH_PROVIDER=Log)
chmod 600 .env secrets/firebase-admin.json

# 3. Start. The DNS record must already point here: Caddy fetches the certificate on first start.
docker compose up -d --build
docker compose logs -f api      # "applying N migration(s)" on the first start, then "Now listening on"
```

The API refuses to start with an incomplete configuration and lists **everything** that is missing in
one message, so there is no restart-per-mistake loop.

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

The `backup` service writes one compressed dump a day to `deploy/backups/` and keeps two weeks. That
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

## Operating

- Logs: `docker compose logs -f api` (Serilog writes to the console; Docker keeps and rotates it).
- OS updates: `sudo apt update && sudo apt upgrade`, and `unattended-upgrades` for security patches.
- Certificates: Caddy renews them; keep the `caddy_data` volume.
- Secrets live only in `deploy/.env` and `deploy/secrets/` on the server (both git-ignored). Changing
  `JWT_KEY` signs everyone out; changing `POSTGRES_PASSWORD` in `.env` does not change the password
  already stored in the database volume.

## Other hosts

Any platform that runs a container works with the same `Dockerfile`. Requirements to carry over:

- one instance, always on, no scale-to-zero; WebSockets allowed;
- the platform terminates TLS and forwards to port `8080` (the image already trusts `X-Forwarded-*`);
- a Postgres 16+ database, its connection string in `ConnectionStrings__kadans`;
- the variables listed in `deploy/docker-compose.yml` under `api.environment` (double underscore = `:`);
- the Firebase key as `Push__Firebase__CredentialsJson` (the whole JSON in one secret) when mounting a
  file is not possible;
- a health check on `/health/ready`.
