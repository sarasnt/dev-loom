# SPEC — Multi-source configuration (v1, draft)

Status: **proposed** · Author: DevLoom · Supersedes the hardcoded, `.env`-configured single
connectors with **named, user-configured source instances**.

## 1. Goal & principles

Today each integration is a singleton wired from `.env` (one Jira, one GitHub, one Notion, one
calendar). The user should instead be able to **add any number of named sources** of each type
from Settings — Jira (Cloud or on-prem), GitHub (Cloud or Enterprise), Bitbucket (Cloud or
Server/DC), calendars, Notion — each with its own credential, stored **encrypted at rest** using
the existing `SecretCipher`/`DEVLOOM_SECRET` mechanism.

Principles:
- **Named instances.** Every source has a user-given name, e.g. *"CSW Jira"*. That name is the
  item's `source`, so every task/PR/event from it reads *"CSW Jira"* and is **filterable** by it
  in the Work source dropdown (which already keys off `source`).
- **Local-first & encrypted.** Credentials never leave the machine and are never returned to the
  client (only presence + a masked hint), exactly like the model-provider keys shipped in §20.
- **Incrementally extensible.** Adding a new source type = a descriptor + a connector factory; no
  UI or schema changes. New compatibility ships as small, isolated additions.
- **No fixtures, honest empties** (per the project's standing rule).

## 2. Concepts

- **SourceType** — a kind of integration: `jira`, `github`, `bitbucket`, `calendar`, `notion`
  (and future `gitlab`, `outlook`, …). Each type declares a **setup descriptor** (the fields the
  UI must collect) and one or more **deployments** (`cloud` | `onprem`).
- **SourceInstance** — a configured, named integration of a type: `{ id, type, deployment, name,
  baseUrl?, config…, enabled }` + a **credential** (encrypted).
- **Credential** — the secret(s) for an instance (token / PAT / app-password / email+token),
  stored encrypted, one blob per instance.

## 3. Data model

```sql
CREATE TABLE source_instance (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(32)  NOT NULL,          -- jira | github | bitbucket | calendar | notion
    deployment  VARCHAR(16)  NOT NULL DEFAULT 'cloud', -- cloud | onprem
    name        VARCHAR(80)  NOT NULL UNIQUE,    -- "CSW Jira" — becomes item.source
    base_url    VARCHAR(512),                    -- on-prem / enterprise / ICS URL
    config_json TEXT,                            -- non-secret per-type settings (project keys, workspace, JQL, horizon…)
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE source_credential (
    instance_id BIGINT PRIMARY KEY REFERENCES source_instance(id) ON DELETE CASCADE,
    enc_secret  TEXT NOT NULL,                   -- base64(iv||ciphertext); a JSON map of secret fields
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE work_item ADD COLUMN source_instance_id BIGINT;  -- provenance (nullable during migration)
-- `source` (display name) stays denormalized on work_item for display + filtering.
```

Notes:
- `name` is **unique** (so `source` is unambiguous). Renaming an instance updates its items'
  `source` on next sync (replace-on-sync).
- `config_json` holds only **non-secret** knobs (e.g. Jira JQL, GitHub repo scope, calendar
  horizon). Secrets live only in `source_credential`, encrypted.
- `source_credential.enc_secret` decrypts to a small JSON object so multi-field creds (e.g. Jira
  Cloud's `email` + `apiToken`) fit one blob.

## 4. Naming → `source` → filtering

- On ingest, each `WorkItem.source = instance.name` and `source_instance_id = instance.id`.
- The Work view's **source dropdown** already lists distinct `source` values → named instances
  appear automatically (e.g. *All sources · CSW Jira · Personal GitHub · …*).
- Today's sync chips and the Integrations screen group by instance.

## 5. Per-type setup descriptors

Each type exposes a descriptor the UI renders as a form. `secret: true` fields are written to
`source_credential` (encrypted); others to `config_json`.

| Type | Deployment | Fields |
|------|-----------|--------|
| **Jira** | Cloud | `baseUrl` (`https://acme.atlassian.net`), `email`, `apiToken`*(secret)*, `jql?` |
| | on-prem (DC/Server) | `baseUrl` (`https://jira.critical.pt`), `pat`*(secret, Bearer)*, `jql?` |
| **GitHub** | Cloud | `token`*(secret, fine-grained PAT)*, `scope?` (involves\:me \| org \| repos) |
| | Enterprise | `baseUrl` (`https://ghe.acme.com/api/v3`), `token`*(secret)* |
| **Bitbucket** | Cloud | `workspace`, `email`, `apiToken`*(secret, app password/API token)* |
| | Server/DC | `baseUrl`, `pat`*(secret, Bearer)*, `project?` |
| **Calendar** | ICS (v1) | `icsUrl`*(secret-ish — treat as secret)*, `horizonDays?` |
| | Google/MS (later) | OAuth — separate flow, out of v1 |
| **Notion** | Cloud | `token`*(secret, internal integration)*, `maxItems?` |

Auth specifics (already proven in the current connectors):
- Jira **Cloud** = Basic `email:apiToken`, REST v3; **on-prem** = `Authorization: Bearer <PAT>`,
  REST v2. The descriptor's `deployment` selects which.
- GitHub Enterprise differs only by `baseUrl`.
- Bitbucket **Cloud** = Basic `email:apiToken` (or app password), REST 2.0; **Server/DC** =
  Bearer PAT, REST 1.0 — PR = "pull request", MR terminology is GitLab's.

## 6. Connector architecture refactor

Replace singleton `@Component` connectors with **instance-driven** ones:

```java
interface SourceConnector {
    String type();                                  // "jira"
    SetupDescriptor describe();                      // fields + deployments for the UI
    List<WorkItemEntity> fetch(SourceInstance inst, Secrets secrets); // throws on failure
    default boolean test(SourceInstance inst, Secrets secrets) { … }  // credential check
}
```

- One connector **per type** (not per instance); it's a stateless factory that takes the
  instance config + decrypted secrets and returns items. (Current `JiraConnector` etc. become
  these, parameterised instead of reading `@Value`.)
- A `SourceRegistry` maps `type → SourceConnector`. `SyncService.sync(instanceId)` loads the
  instance + secrets, calls `connector.fetch(...)`, and does **replace-on-sync scoped to the
  instance** (`deleteBySourceInstanceId(id)` then insert) — reusing the throw-on-failure /
  clear-on-empty semantics already in place.
- `extId` uniqueness becomes **`(source_instance_id, ext_id)`** instead of `(source, ext_id)`.

## 7. Sync semantics

- Startup + periodic scheduler iterate **enabled instances** (not types).
- Per-instance replace-on-sync; a failed fetch keeps that instance's rows, a successful-but-empty
  fetch clears them (already implemented for the singletons).
- Audit `sync` events carry the instance name.

## 8. Settings UI

`Settings → Sources` (rework of the Integrations screen):
- Sources grouped by type; each instance is a card: **name · deployment · status · N items ·
  last sync**, with **Re-sync / Sync all**, **Edit**, **Test**, **Disconnect**.
- **+ Add source** → pick type → pick deployment → dynamic form from the descriptor →
  **Test** (validates the credential) → **Save** (encrypts secret, creates instance, first sync).
- Edit lets you change the **name** (→ items re-tagged), `baseUrl`, non-secret config, and
  **replace** the secret (never shows it — masked hint only).
- The existing per-source hint pattern (e.g. Notion's "how to connect more") stays per type.

## 9. Security

- Secrets encrypted with `SecretCipher` (AES-GCM, `DEVLOOM_SECRET`); **required** to add a source
  from the UI (same gate as model keys). Without it, the UI explains and stays read-only.
- Secrets never serialized to the client — only `hasCredential` + `maskedHint`.
- Egress/PrivacyGate is unchanged; local connectors don't egress. Per-instance local-only flags
  can be added later.

## 10. API surface

```
GET    /api/v1/source-types                 → descriptors (types, deployments, fields) for the Add form
GET    /api/v1/sources                       → all instances (status, counts, last sync; no secrets)
POST   /api/v1/sources                       → { type, deployment, name, baseUrl?, config, secret } → create (encrypt)
PUT    /api/v1/sources/{id}                  → update name/baseUrl/config (+ optional new secret)
DELETE /api/v1/sources/{id}                  → remove instance + credential + its work items
POST   /api/v1/sources/{id}/test             → validate credential (no persistence)
POST   /api/v1/sources/{id}/sync             → sync now
```

The current `/integrations`, `/integrations/{source}/sync`, `/integrations/{source}` become thin
shims over these (or are replaced).

## 11. Migration & back-compat

- On first boot after upgrade, if `source_instance` is empty, **seed instances from the existing
  `.env`** (Jira/GitHub/Notion/Calendar) so nothing breaks — named e.g. *"Jira"*, *"GitHub"*.
- Existing `work_item.source` values already equal the type names, so they keep working until the
  next sync re-tags them with the instance name + `source_instance_id`.
- `.env` values become the fallback credential for the seeded instances (as model keys do today).

## 12. Extensibility

Adding a source type is a **~1-file change**: implement `SourceConnector` (type + descriptor +
fetch) and register it. GitLab, Outlook/Graph, Linear, etc. slot in without touching the schema,
sync loop, or Settings UI. The descriptor drives the Add form; the registry drives sync.

## 13. Phased rollout

1. **Schema + registry + refactor** the current 4 connectors to instance-driven (seed from `.env`).
   No visible change; proves the architecture.
2. **Sources Settings UI** (list/add/edit/test/sync/disconnect) + `/sources` API.
3. **New types**: Bitbucket (Cloud + Server/DC), GitHub Enterprise, Jira Cloud.
4. **Multiple calendars**, then OAuth calendars (Google/MS) as a separate auth flow.

## 14. Relationship to the host agent (repos & PR/MR)

Opening a **PR/MR with the dedicated tool** (`gh` for GitHub, `glab`/Bitbucket API for
Bitbucket) and the **local repositories manager** (pick a folder of repos, edit git identity,
pull/push, detect remote type) require host access the Dockerized backend lacks. Those live in a
separate **DevLoom host agent** (cross-platform: Windows/WSL/Debian) — see its own spec. This
multi-source config informs it: a Bitbucket/GitHub *source instance* can be linked to a *local
repo* so "open PR" knows which credential/host to use.

---

### Open questions
- Per-instance **local-only** (never egress) flag? (defer)
- Secret **rotation** UX when `DEVLOOM_SECRET` changes (invalidate + prompt re-enter).
- Rate-limit / backoff per instance (Jira Cloud vs DC differ).
