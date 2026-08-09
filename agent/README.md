# DevLoom host agent

A tiny local process that gives the (containerised) DevLoom backend access to things a Linux
container can't reach on its own — your logged-in **Claude Code** CLI (subscription), and soon
`git` / `gh` / `glab` for the repositories manager.

The backend calls it over `host.docker.internal` (see `DEVLOOM_HOST_AGENT_URL`).

## Run it

Prereqs: **Node 18+**, and for the Claude bridge the **`claude` CLI logged in**
(`claude` 2.x — run `claude` once and sign in with your normal Anthropic account).

```bash
node agent/devloom-agent.mjs
# → DevLoom host agent listening on http://127.0.0.1:8765
```

Cross-platform: works on **Windows**, **WSL**, and **Debian/Linux**. On Windows run it from
PowerShell; it resolves `claude.cmd`/`claude.ps1` automatically. If you run the backend under
Docker Desktop, `host.docker.internal` already points at the host; on plain Linux the compose
file adds a `host-gateway` mapping so it resolves there too.

Change the port with `DEVLOOM_AGENT_PORT=9000` (and set `DEVLOOM_HOST_AGENT_URL` on the backend
to match).

## Endpoints

- `GET /health` → `{ ok, platform, claude, git, gh, glab }` (CLI versions, or null if absent)
- `POST /claude` `{ system?, prompt }` → `{ text, model }` — runs `claude -p --output-format json`
  using your subscription (no API key, no per-token charge)
- *(coming next)* repositories endpoints: scan folders, git identity, pull/push, open PR/MR

## Using Claude Code in DevLoom

Once the agent is running and `claude` is logged in, **`claude-code`** appears in the model
picker (rail + Brainstorm). Selecting it routes generations through your subscription. It still
sends context to Anthropic, so the boundary shows **remote**.

Security: the agent listens only on `127.0.0.1`. It never exposes your credentials — it shells
out to CLIs that are already authenticated on your machine.
