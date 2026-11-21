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

## Alternative: Render

Create a **PostgreSQL** instance, then two **Web Services** from the repo: `backend/` (Docker, set `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE=prod`) and `frontend/` (Docker). In `frontend/nginx.conf`, change
`proxy_pass http://backend:8080` to the backend service's internal URL. The free tier sleeps when idle, so the first
request after a while is slow.
