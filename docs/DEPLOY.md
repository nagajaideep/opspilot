# Deploying OpsPilot

The simplest production-like setup is one small VM running Docker Compose behind HTTPS.

## AWS EC2 (about 20 minutes)

1. **Launch an instance:** Ubuntu 24.04, `t3.small` (2 GB RAM is the practical minimum for the JVM + Postgres + Grafana).
   Security group inbound: **22** (your IP only), **80** and **443** (anywhere). Do **not** open 8080, 9090 or 3001.
2. **Install Docker:**
   ```bash
   curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER && newgrp docker
   ```
3. **Get the code and configure secrets:**
   ```bash
   git clone https://github.com/<you>/opspilot.git && cd opspilot
   cp .env.example .env
   # In .env set at least:
   #   JWT_SECRET=$(openssl rand -base64 48)
   #   DB_PASSWORD=<random>
   #   GRAFANA_PASSWORD=<random>
   #   LLM_ENABLED=true, LLM_API_KEY=...   (optional)
   ```
4. **Start the stack:** `docker compose up -d --build`
5. **Add HTTPS with Caddy** (automatic Let's Encrypt certificates). Point a DNS name at the instance, then create
   `Caddyfile`:
   ```
   opspilot.example.com {
       reverse_proxy localhost:3000
   }
   ```
   and run `docker run -d --network host -v $PWD/Caddyfile:/etc/caddy/Caddyfile -v caddy_data:/data caddy:2`.

## Production checklist

| Item | Why |
|---|---|
| `JWT_SECRET`, `DB_PASSWORD`, `GRAFANA_PASSWORD` set to random values | The compose defaults are demo values |
| `DEMO_USERS_ENABLED=false` once real users exist | Demo users have a published password |
| Only 80/443 public; 8080, 9090, 3001 bound to localhost or a private network | `/actuator/prometheus` and Grafana are internal tools |
| `springdoc.api-docs.enabled=false` (or protect it) | Don't publish the API surface publicly |
| Postgres backups (`pg_dump` cron to S3, or Amazon RDS with automated backups) | The volume on one VM is a single point of failure |
| A distributed lock (ShedLock) before running more than one backend replica | Otherwise each replica runs the nightly scan |
| Move the login lockout state to Redis before scaling out | It's in memory per instance today |

## Render (one click, free) - used for the live demo

`render.yaml` at the repo root is a Render Blueprint. It creates a free PostgreSQL database, the API (Docker, from
`backend/Dockerfile`) and the frontend (static site that forwards `/api/*` to the API).

1. Sign in at https://render.com with GitHub.
2. Open https://render.com/deploy?repo=https://github.com/nagajaideep/opspilot and click **Apply**.
   Leave `LLM_API_KEY` empty unless you want the LLM.
3. Wait for the three resources to go live (the API's first Docker build takes ~5-10 minutes).
4. If the API's URL is not exactly `https://opspilot-api.onrender.com` (Render adds a suffix when a name is taken),
   edit the `/api/*` rewrite destination in `render.yaml` (or in the static site's Redirects/Rewrites settings).
5. To enable the LLM: in the `opspilot-api` service, set `LLM_ENABLED=true` and `LLM_API_KEY`, then redeploy.

Free-tier limits: the API sleeps after 15 minutes idle (first request ~1 minute), and the free database expires after
30 days unless upgraded. Memory was tested locally with a 512 MB cap: ~270 MB used.
