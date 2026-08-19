# Bitbucket DC build-status probe — for the Builds screen

Reconnaissance only; nothing was implemented. Every response below is a **real** capture from the
company's on-prem Bitbucket, redacted: the host is `$BB`, the CI host is `$CI`, emails and display
names are faked. Key names and structure are untouched. No token appears anywhere.

Probed 2026-08-15 against `LCCCHES/implementation` with the `bitbucket-csw` skill's HTTP access
token. Sample: 25 recent PRs / 31 builds, plus 10 builds re-fetched through the newer API.

## Version

`GET $BB/rest/api/1.0/application-properties` → **200**

```json
{"version":"9.4.22","buildNumber":"9004022","buildDate":"1783935727556","displayName":"Bitbucket"}
```

**9.4.22 — a 9.x, not the 7.x/8.x the brief anticipated.** Both build-status APIs exist here, so
the shape question is settled by capture rather than by version-guessing.

## Dashboard PR sample

`GET $BB/rest/api/1.0/dashboard/pull-requests?state=OPEN&limit=5` → **200**, `size: 5`,
`isLastPage: false`.

Worth knowing: **the dashboard spans repositories.** The five open PRs came back from
`LCCCHES/tests`, `LCCCHES/infrastructure` and `LCCCHES/implementation` in one call, so the Builds
screen gets cross-repo coverage for free — but must read `toRef.repository.project.key` and
`toRef.repository.slug` per PR rather than assuming one repo.

Top-level keys on a PR object:

```
author, closed, createdDate, draft, fromRef, id, links, locked, open,
participants, properties, reviewers, state, title, toRef, updatedDate, version
```

`fromRef.latestCommit` is the SHA the build endpoints key off:

```json
"fromRef": {
  "id": "refs/heads/feature/LCCCHES-2798-partial-completion-cancelled-statuses",
  "displayId": "feature/LCCCHES-2798-partial-completion-cancelled-statuses",
  "latestCommit": "344aa5db4757255d0840b6ba8b2d03aaec350254",
  "type": "BRANCH",
  "repository": { "slug": "implementation", "project": { "key": "LCCCHES" }, ... }
}
```

The `links` block is thinner than expected — **one `self` href, nothing else**. No build link, no
commit link:

```json
"links": { "self": [ { "href": "$BB/projects/LCCCHES/repos/tests/pull-requests/519" } ] }
```

### Does `properties` carry build info? — No.

This was the hoped-for shortcut (one call per PR instead of two). It is not there on 9.4.22.
Checked all five PRs; every one had exactly these keys:

```json
"properties": {
  "mergeResult": { "outcome": "CLEAN", "current": true },
  "resolvedTaskCount": 10, "commentCount": 9, "openTaskCount": 0
}
```

**Consequence: the Builds screen needs a second call per PR to get build state.** Budget for
N+1 requests, or use the stats endpoint below as the cheap first pass.

## Build-status samples

Anchored on PR #749 (`LCCCHES-2822`), whose head commit
`6b38a43a6af8ea5ca195dbbc7042d4b87e5f3965` carries a genuinely **FAILED** build.

### (a) Old API — `GET $BB/rest/build-status/1.0/commits/{commit}` → **200**

```json
{
  "size": 1, "limit": 100, "isLastPage": true, "start": 0,
  "values": [
    {
      "state": "FAILED",
      "key": "d216516c45ab284ba7a729fa55d106b0",
      "name": "LCCCHES » implementation » LCCCHES-2822 Refactor calculation services to scheme specific smart contracts dtos (#749) LCCCHES-13 [PR-749]",
      "url": "$CI/job/LCCCHES/job/implementation/job/PR-749/13/display/redirect",
      "description": "There was a failure building this commit.",
      "dateAdded": 1787071430930
    }
  ]
}
```

Field union across **all 31 builds** seen: `state`, `key`, `name`, `url`, `description`,
`dateAdded`. Nothing else — no `duration`, no `buildNumber`, no `ref`, no `testResults`.

States observed: `SUCCESSFUL` (25), `FAILED` (4), `INPROGRESS` (2). The stats endpoint also
buckets `cancelled` and `unknown`, which never occurred in this sample — treat them as reachable,
not dead.

### (b) Stats — `GET $BB/rest/build-status/1.0/commits/stats/{commit}` → **200**

```json
{"cancelled":0,"successful":0,"inProgress":0,"failed":1,"unknown":0}
```

Cheapest possible "is this PR red?" check: fixed five-integer body, no list to page through. Good
default for a list view; drill into (a)/(c) only when a row is expanded.

### (c) New API — `GET $BB/rest/api/latest/projects/{proj}/repos/{slug}/commits/{commit}/builds`

**This does not 404 — it 400s**, which is the single most useful thing found:

```json
{"errors":[{"message":"Please specify a non-blank key smaller than 255 characters",
            "exceptionName":"com.atlassian.bitbucket.validation.ArgumentValidationException"}]}
```

It is **not a list endpoint**. It requires `?key=<build key>` and returns **one** build object.
Adding the key from (a) → **200**:

```json
{
  "createdDate": 1786972343783,
  "dateAdded": 1787071430930,
  "updatedDate": 1787071430930,
  "description": "There was a failure building this commit.",
  "key": "d216516c45ab284ba7a729fa55d106b0",
  "name": "LCCCHES » implementation » ... (#749) LCCCHES-13 [PR-749]",
  "state": "FAILED",
  "url": "$CI/job/LCCCHES/job/implementation/job/PR-749/13/display/redirect",
  "buildNumber": "13",
  "duration": 2430478,
  "projectKey": "LCCCHES",
  "repositorySlug": "implementation",
  "ref": "refs/heads/feature/LCCCHES-2822-refactor-calculation-services-to-scheme-specific-smart-contract-dtos",
  "commit": {
    "id": "6b38a43a6af8ea5ca195dbbc7042d4b87e5f3965",
    "displayId": "6b38a43a6af",
    "author":    { "name": "adev", "emailAddress": "dev@example.com", "displayName": "Dev User", "slug": "adev", ... },
    "committer": { "name": "bdev", "emailAddress": "dev@example.com", "displayName": "Dev User", "slug": "bdev", ... },
    "authorTimestamp": 1786650662000,
    "committerTimestamp": 1786971812000,
    "message": "LCCCHES-2822 Refactor calculation services to scheme specific smart contracts dtos",
    "parents": [ { "id": "acaed3c8ac5f...", "displayId": "acaed3c8ac5" } ],
    "properties": { "jira-key": ["LCCCHES-2822"] }
  }
}
```

**The old API is therefore still required.** You cannot discover build keys through the new
endpoint, so the real sequence is: (a) or (b) to enumerate → (c) per key to enrich. The new API is
an enrichment call, not a replacement.

### Field availability

| Field | Old (a) | New (c) |
| --- | --- | --- |
| `state`, `key`, `name`, `url`, `description`, `dateAdded` | yes | yes |
| `buildNumber`, `duration`, `ref`, `projectKey`, `repositorySlug` | **no** | yes |
| `createdDate`, `updatedDate`, `commit` (full object) | **no** | yes |
| `testResults` | **no** | **no** |

## testResults present? — No. Not once.

Checked **31 builds via the old API and 10 via the new one; zero carried a `testResults` key.**
It is optional in Bitbucket's model — populated only if the CI job posts it when publishing the
status, and this Jenkins setup does not.

**A Builds screen cannot show failed/passed/skipped test counts from Bitbucket alone.** Either
drop test counts from the design, or get them from Jenkins (out of scope today, and a second
credential). Worth deciding before the screen is designed around a number that isn't there.

`duration` is the usable substitute already present in (c) — though one sampled build returned
`duration: 0`, so treat 0 as "not yet known" (in-progress) rather than "instant".

## CI system behind the `url`

```
$CI/job/LCCCHES/job/implementation/job/PR-749/13/display/redirect
```

**Jenkins** — specifically a *multibranch pipeline*. The nested `/job/<folder>/job/<repo>/job/PR-<n>/`
segments are Jenkins folder nesting, `13` is the build number (matching `buildNumber` in (c)), and
`/display/redirect` is a Jenkins-ism. Not Bamboo, which uses flat `/browse/KEY-PLAN-123`.

Per the brief, Jenkins itself was **not** probed.

Two consequences: the PR number is recoverable from the URL, and a deep link to console output is
constructible (`.../PR-749/13/console`) — but that is inference from URL shape, unverified, and
would need its own auth.

## Which endpoints 404'd

| Endpoint | Status |
| --- | --- |
| `/rest/build-status/2.0/commits/{commit}` | **404** — no v2 on 9.4.22 |
| `/rest/api/latest/projects/{p}/repos/{r}/commits/{c}/builds/stats` | **404** — stats only on the `build-status/1.0` path |
| `/rest/api/1.0/projects/{p}/repos/{r}/pull-requests/749/builds` | **404** — no PR-level build endpoint; must go via head commit |
| `/rest/api/latest/.../commits/{c}/builds` *(no `key`)* | **400**, not 404 — endpoint exists, param missing |

Repo-scoped PR listing (`/projects/LCCCHES/repos/implementation/pull-requests?state=OPEN&limit=2`)
→ **200**. Token reaches both repo-scoped and dashboard endpoints.

## Anything surprising

1. **`properties` has no build summary.** The hoped-for one-call-per-PR shortcut doesn't exist on
   9.4.22. Plan for the extra round trip.
2. **The "new" API is a single-build lookup, not a list.** It 400s without `key`. This inverts the
   expected migration story — the old endpoint can't be retired, only supplemented.
3. **`testResults` is absent everywhere.** The most likely thing to have been designed in on the
   assumption it was there.
4. **`commit.properties.jira-key`** — Bitbucket already resolves the Jira key per commit
   (`["LCCCHES-2822"]`). DevLoom can link a build straight to its ticket without parsing branch
   names or titles.
5. **The dashboard is cross-repo**, so `toRef.repository.{project.key,slug}` must be read per PR.
6. **Commits with no build at all are common** — 2 of 5 open dashboard PRs had `size: 0`. "No build"
   is a distinct state from "build pending"; the UI needs an honest empty state for it.
7. **`name` is long and pre-formatted** (`"LCCCHES » implementation » <title> (#749) LCCCHES-13 [PR-749]"`)
   — a display string from Jenkins, not a stable identifier. Don't parse it; use `key`.

## Suggested call pattern (not implemented)

1. `GET /rest/api/1.0/dashboard/pull-requests?state=OPEN` — PRs across repos, one call.
2. Per PR, `GET /rest/build-status/1.0/commits/stats/{fromRef.latestCommit}` — cheap red/green.
3. On expand, `GET /rest/build-status/1.0/commits/{sha}` for the keys, then
   `GET /rest/api/latest/projects/{p}/repos/{r}/commits/{sha}/builds?key={key}` per build for
   `duration` / `buildNumber` / `ref` / commit metadata.

Step 3 is two calls per build; skip it entirely for a list-only view.
