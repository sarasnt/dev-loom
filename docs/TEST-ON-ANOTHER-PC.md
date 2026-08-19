# Testing DevLoom on another PC

The backend, frontend and ollama images are published to GHCR on every push to `main`, so the
other PC pulls them instead of building anything. The packages must be **public** (one-time:
github.com/sarasnt → profile → Packages → each `devloom-*` → Package settings → Change
visibility → Public) or Docker must be logged in there with a `read:packages` PAT.

Both stacks now front everything with the **edge** proxy: the only host ports the app binds are
80/443 (movable via `DEVLOOM_HTTP_PORT` / `DEVLOOM_HTTPS_PORT`). Postgres, the backend, the
frontend and Ollama are compose-internal — a native Postgres on 5432 or a native Ollama on 11434
can no longer stop DevLoom from starting, which is exactly what happened on the first Kubuntu
attempt.

---

## On the other PC (Linux — Kubuntu/Debian shown; Windows notes at the end)

### 1. Install

- Docker Engine (or Docker Desktop) with the compose plugin
- Node.js ≥ 20 — for the host agent
- git

### 2. Clone

The containers only need the compose files, but the **host agent is a Node script in the repo**
(Repos, Fleet terminals, Capabilities and notifications all go through it), and the edge needs
`edge/nginx.conf`. So clone:

```bash
git clone https://github.com/sarasnt/dev-loom.git
cd dev-loom
```

### 3. Certificates and names (one-time)

`.dev` is HSTS-preloaded in every browser, so the domain names only work over trusted TLS —
without these steps the names simply do not load. (`http://localhost` needs none of this.)

The certificates themselves are generated **automatically on first `up`** (the `edge-certs`
one-shot service writes them to `edge/certs/`), so there is nothing to generate by hand — these
steps just make your OS and browser trust them:

```bash
# resolve the names to this machine
echo "127.0.0.1 mycompanion-devloom.dev api.mycompanion-devloom.dev portal.mycompanion-devloom.dev" \
  | sudo tee -a /etc/hosts

# trust the CA — system store (curl etc.); the file exists after the first `up`
sudo cp edge/certs/devloom-local-ca.crt /usr/local/share/ca-certificates/devloom-local-ca.crt
sudo update-ca-certificates

# browsers keep their own store (NSS) — without this curl works and the browser refuses
sudo apt install -y libnss3-tools
certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "DevLoom Local CA" -i edge/certs/devloom-local-ca.crt
for prof in ~/.mozilla/firefox/*/; do
  certutil -d sql:"$prof" -A -t "C,," -n "DevLoom Local CA" -i edge/certs/devloom-local-ca.crt
done
```

Restart the browser afterwards — trust stores are read at startup.

### 4. Minimal secrets file (recommended)

```bash
cat > backend/.env <<EOF
# lets you save provider/connector keys from the Settings UI (encrypted at rest); keep it stable
DEVLOOM_SECRET=$(openssl rand -hex 32 2>/dev/null || head -c 48 /dev/urandom | base64)
TZ=Europe/Lisbon
EOF
```

Connectors (GitHub, Bitbucket, Jira, Notion, calendars) are configured later in
**Settings → Sources** and stored encrypted in the database.

### 5. Up

Pin the compose file once, so plain `docker compose up`/`down` always means the prebuilt stack —
mixing an `up -f docker-compose.prebuilt.yml` with a bare `down` evaluates the *dev* file, which
profile-gates ollama and leaves it running:

```bash
echo "COMPOSE_FILE=docker-compose.prebuilt.yml" > .env    # repo root (gitignored)
```

```bash
docker compose pull        # ~6–7 GB first time
docker compose up -d

# NVIDIA GPU (needs the NVIDIA Container Toolkit):
COMPOSE_FILE=docker-compose.prebuilt.yml:docker-compose.gpu.yml docker compose up -d
```

- App: **https://portal.mycompanion-devloom.dev** (or the bare domain, or plain
  `http://localhost` with no hosts/CA setup)
- API: `curl https://api.mycompanion-devloom.dev/api/v1/settings` → JSON

### 6. The host agent

```bash
node agent/devloom-agent.mjs
```

On Linux it binds **127.0.0.1 and the docker0 bridge IP** — both lines print at startup. The
second one is not optional decoration: on native Linux Docker, `host.docker.internal` resolves to
the bridge, and an agent bound only to loopback is unreachable from the backend container — the
app then claims the agent isn't running while the agent says it is. (Docker Desktop on
Windows/macOS hides this by routing to host loopback.) Never bind `0.0.0.0`: this process reads
and writes your repositories, and the LAN is not invited. `DEVLOOM_AGENT_HOST` (comma-separated)
overrides the guess.

Verify from inside a container once:

```bash
docker compose -f docker-compose.prebuilt.yml exec backend curl -s http://host.docker.internal:8765/health
```

For the interactive terminal feature, once: `cd agent && npm install` (pulls `node-pty`/`ws`).

### 7. Carry your settings over (optional)

**Settings → Workspace → Backup** on the main PC (points at a private git repo you own),
**Restore** on this one. Secrets are deliberately excluded — re-enter tokens once in
**Settings → Sources / Models**. Or just click through onboarding; an empty DevLoom is honest
about being empty.

---

## What to expect on the test PC

| Thing | State |
| --- | --- |
| Local models | `qwen2.5-coder:7b` baked into the image, ready at first boot |
| More models | **Settings → Models → Install**; they persist in the `devloom_ollama` volume |
| Speed | CPU-only by default; add the GPU override where there's an NVIDIA card |
| Host ports | only 80/443 (`DEVLOOM_HTTP_PORT`/`DEVLOOM_HTTPS_PORT` to move), plus 3000 if you start the Langfuse profile (`DEVLOOM_LANGFUSE_PORT`) |
| Langfuse | `--profile obs` → http://localhost:3000 — a fresh instance (new account, new keys) |
| Builds screen | GitHub Actions failures only for now — Bitbucket Pipelines isn't wired into Builds yet |
| Eval harness | `node eval/run.mjs …` from the clone works as-is (it targets `http://localhost/api/v1`, which is the edge) |

## Keeping it current

```bash
git pull                   # compose files, edge config, host agent, eval
docker compose pull        # images rebuilt by CI on every main push
docker compose up -d
# restart the host agent too — it runs from the checkout you just pulled
```

If a compose file changed the port layout since your last pull, do a one-time
`docker compose down` before `up` so old published ports are released. `down` must always get
the same file/profile flags as the `up` did — the COMPOSE_FILE pin above makes that automatic.

---

## Windows notes

Same flow; differences only where the OS shows:

- hosts file: an **Administrator** PowerShell —
  `Add-Content -Path "$env:WINDIR\System32\drivers\etc\hosts" -Encoding ascii -Value "127.0.0.1 mycompanion-devloom.dev api.mycompanion-devloom.dev portal.mycompanion-devloom.dev"`
- trust the CA: `Import-Certificate -FilePath .\edge\certs\devloom-local-ca.crt -CertStoreLocation Cert:\LocalMachine\Root`
  (one store — browsers follow it; no NSS step)
- Windows `curl` may report `CRYPT_E_NO_REVOCATION_CHECK` against the local CA while the browser
  is happy — pass `--ssl-no-revoke`.
- The agent's loopback-only default is fine here: Docker Desktop routes
  `host.docker.internal` to host loopback.

More depth (tags/releases, baking bigger models, forks): `docs/DEPLOY.md`.
