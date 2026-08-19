# Testing DevLoom on another PC

The short version of your hunch is right: the backend and frontend images are published to
GitHub's container registry on every push to `main`, so the other PC pulls them instead of
building anything. Two corrections to it, both already handled or handled below:

- The images publish **private** by default, so a fresh machine can't pull them until you either
  make them public (one-time, recommended) or log Docker in with a token.
- The **ollama image had never been built** — CI only rebuilds what changed, and nothing had ever
  touched `ollama/**`. A manual full run was triggered on 2026-08-15; once it's green under
  **Actions → publish-images**, all three images exist with `qwen2.5-coder:7b` baked in.

The prebuilt stack deliberately has no edge proxy, no TLS and no `.dev` domains — it is the
simple portable flavour. The app lives at **http://localhost:8088** there, unlike the dev stack.

---

## One-time, on GitHub (from any machine)

Make the three packages public so the other PC needs no credentials — they contain the app and
open-weight models, no secrets:

1. github.com/sarasnt → your profile → **Packages**
2. For each of `devloom-backend`, `devloom-frontend`, `devloom-ollama`:
   **Package settings → Danger Zone → Change visibility → Public**

Prefer to keep them private? Then on the other PC, before pulling:

```powershell
# a classic PAT with the read:packages scope
echo <PAT> | docker login ghcr.io -u sarasnt --password-stdin
```

---

## On the other PC

### 1. Install

- **Docker Desktop** (with WSL2 backend on Windows)
- **Node.js ≥ 20** — for the host agent
- **git** — the app's Repos/Fleet features work on local clones

### 2. Get the repo

You only strictly need `docker-compose.prebuilt.yml` + `docker-compose.gpu.yml` for the
containers — but the **host agent is a Node script that lives in the repo**, and without it the
Repos, Fleet-terminal, Capabilities and notification features all show empty. So clone:

```powershell
git clone https://github.com/sarasnt/dev-loom.git
cd dev-loom
```

### 3. Minimal secrets file (optional but recommended)

Create `backend/.env` (gitignored) with just:

```
# lets you save provider/connector keys from the Settings UI (encrypted at rest);
# any long random string, keep it stable
DEVLOOM_SECRET=<paste something long and random>
TZ=Europe/Lisbon
```

Everything else (GitHub token, Jira, Notion, calendar URLs) is configured later in
**Settings → Sources** and stored encrypted in the database — no file editing needed.

### 4. Bring the stack up

```powershell
docker compose -f docker-compose.prebuilt.yml pull
docker compose -f docker-compose.prebuilt.yml up -d

# on a machine with an NVIDIA GPU (needs the NVIDIA Container Toolkit):
docker compose -f docker-compose.prebuilt.yml -f docker-compose.gpu.yml up -d
```

First `pull` downloads ~6–7 GB (the ollama image carries the baked model). When it's up:

- App: **http://localhost:8088**
- API health: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

### 5. Start the host agent

```powershell
node agent/devloom-agent.mjs
```

Foreground, on the machine itself — it is everything the containers can't reach: your git repos,
your `claude` CLI, desktop notifications. For the interactive terminal feature, once:
`cd agent && npm install` (pulls `node-pty`/`ws`; everything else is dependency-free).

**If a repo, terminal, capability or notification feature does nothing, this agent isn't running.**
That is the number-one support question on any machine.

### 6. Carry your settings over (optional)

DevLoom has config backup built for exactly this — **Settings → Workspace → Backup**:

- On this PC: point it at a private git repo you own and run a backup.
- On the other PC: configure the same repo and **Restore**.

It carries sources (minus secrets), repo list, preferences and screen-model choices. Secrets are
deliberately excluded — re-enter API tokens once in **Settings → Sources / Models** on the new
machine.

Or skip it and just click through onboarding — an empty DevLoom is honest about being empty.

---

## What to expect on the test PC

| Thing | State |
| --- | --- |
| Local models | `qwen2.5-coder:7b` baked in, ready at first boot |
| More models | **Settings → Models → Install** (or `docker exec` + `ollama pull`); they persist in the `devloom_ollama` volume |
| Speed | CPU-only by default — the 7b is usable, not fast; add the GPU override where there's an NVIDIA card |
| Langfuse | `docker compose -f docker-compose.prebuilt.yml --profile obs up -d` → http://localhost:3000 — a fresh instance (new account, new keys) |
| The `.dev` domains / TLS | Not part of the prebuilt stack on purpose; it's plain http://localhost:8088 |
| Eval harness (`eval/`) | Works from the clone, but note it targets `http://localhost/api/v1` (the dev edge) — run it with `DEVLOOM_API=http://localhost:8080/api/v1 node eval/run.mjs …` against the prebuilt stack |

## Keeping it current

Every push to `main` republishes `:latest` for whatever changed, so updating the other PC is:

```powershell
docker compose -f docker-compose.prebuilt.yml pull
docker compose -f docker-compose.prebuilt.yml up -d
git pull   # keeps the host agent + eval scripts in step
```

More depth (tags/releases, baking bigger models, forks): `docs/DEPLOY.md`.
