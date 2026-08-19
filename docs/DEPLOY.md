# Deploying DevLoom

There are two ways to bring up the stack. They serve the app at different addresses: the source
stack fronts everything with the edge proxy (**http://localhost**, plus the `.dev` names once
trusted — see the README), while the prebuilt stack skips the edge on purpose and serves plainly
at **http://localhost:8088**.

| | From source (`docker-compose.yml`) | Prebuilt (`docker-compose.prebuilt.yml`) |
|---|---|---|
| Backend / frontend | built from `./backend`, `./frontend` | pulled from GHCR |
| Ollama models | pulled at `up` by `ollama-init` | **baked into the image** |
| Needs source checkout to build | yes | no (just the compose files) |
| Best for | developing DevLoom | testing on a fresh PC |
| GPU | reserved by default | CPU by default; add the GPU override |

---

## 1. From source (development)

Builds everything locally and pulls the local models into Ollama on first `up`.

```bash
docker compose up -d --build          # core: db + backend + frontend
docker compose --profile ai up -d     # + Ollama (auto-pulls DEVLOOM_OLLAMA_MODELS)
docker compose --profile obs up -d    # + Langfuse UI → http://localhost:3000
```

## 2. Prebuilt (portable — run on a new PC)

Runs published images from GHCR. No build, no model pull.

```bash
docker compose -f docker-compose.prebuilt.yml pull
docker compose -f docker-compose.prebuilt.yml up -d

# with an NVIDIA GPU (needs the NVIDIA Container Toolkit):
docker compose -f docker-compose.prebuilt.yml -f docker-compose.gpu.yml up -d

# + Langfuse:
docker compose -f docker-compose.prebuilt.yml --profile obs up -d
```

The only files you need on the target machine are `docker-compose.prebuilt.yml` and
`docker-compose.gpu.yml` (plus an optional `backend/.env` for secrets). Ollama runs
**CPU-only** by default so it starts anywhere; add the GPU override on machines that
have one.

### Private vs public images

Images publish **private** by default. To `pull` on a new PC either:

- make them public: GitHub → repo → **Packages** → each `devloom-*` package →
  *Package settings* → **Change visibility → Public**; or
- log in first: `echo $GH_PAT | docker login ghcr.io -u <user> --password-stdin`
  (a classic PAT with `read:packages`).

Override the registry/tag if you forked:
`DEVLOOM_IMAGE_PREFIX=ghcr.io/<you>/devloom DEVLOOM_TAG=v1.0.0 docker compose -f docker-compose.prebuilt.yml up -d`.

---

## Publishing the images (CI)

`.github/workflows/publish-images.yml` keeps the GHCR images current:

- **On every push to `main`:** rebuilds + publishes `:latest` (and `:main-<sha>`) for
  **only the image whose files changed** — a `backend/**` commit rebuilds
  `devloom-backend` alone and leaves the multi-GB `devloom-ollama` untouched. This is
  what keeps `docker-compose.prebuilt.yml` (which pulls `:latest`) working everywhere.
- **On a version tag:** `git tag v1.0.0 && git push origin v1.0.0` → a full release of
  **all three** images, tagged `:1.0.0`, `:1.0`, `:latest`.
- **Manual:** GitHub → **Actions → publish-images → Run workflow** → all three; pick the
  extra tag and which models to bake into `devloom-ollama`.

Builds are `linux/amd64`. For Apple Silicon, add `linux/arm64` to each job's `platforms`
(slower — it builds under QEMU emulation).

> First run: GitHub Actions needs to be enabled for the repo, and the `GITHUB_TOKEN` has
> `packages: write` (already set in the workflow). After the first successful run the
> three `devloom-*` packages appear under the repo's **Packages**; flip them to Public
> there if you want key-less `pull` on other machines.

### Baked Ollama models — mind the size

Each baked model adds its full weight to the image: `qwen2.5-coder:7b` ≈ 4.7 GB,
`gpt-oss:20b` ≈ 13 GB. CI bakes **`qwen2.5-coder:7b`** by default so the image stays
pullable and fits the GitHub runner's disk. To ship a bigger model:

- bake it via the manual run's **models** input (the workflow frees runner disk first), or
- build locally on a beefy machine and push:
  ```bash
  docker build --build-arg MODELS="qwen2.5-coder:7b gpt-oss:20b" \
    -t ghcr.io/sarasnt/devloom-ollama:latest ./ollama
  docker push ghcr.io/sarasnt/devloom-ollama:latest
  ```
- or just `ollama pull gpt-oss:20b` at runtime — it lands in the mounted
  `devloom_ollama` volume and persists.
