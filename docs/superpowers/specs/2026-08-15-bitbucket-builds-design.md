# Bitbucket DC builds in the Builds screen — design

**Date:** 2026-08-15 · **Status:** approved in chat ("sure go ahead")
**Grounding:** every API fact below is a real capture from the company instance — see
`2026-08-15-bitbucket-dc-probe.md` (Bitbucket DC **9.4.22**, Jenkins multibranch behind it).

## Why

DevLoom syncs Bitbucket PRs but the Builds screen is GitHub-Actions-only, so a red Bitbucket PR
never surfaces as a failed build ("if we support bitbucket then we must"). The probe settled what
is honestly buildable: Bitbucket exposes build **status + link + duration**, never logs, and this
instance publishes **no `testResults`** (0 of 41 builds sampled).

## Decisions

1. **Builds enter at sync-time, in `BitbucketConnector`.** For each dashboard PR (already
   fetched), one extra call: `GET /rest/build-status/1.0/commits/stats/{fromRef.latestCommit}` —
   a fixed five-integer body. `failed > 0` → emit a work item of type `build`, tone `fail`
   (`inProgress > 0` alone → no item; a running build is not a failure). PRs with no builds at
   all (`size: 0` — 2 of 5 in the sample) emit nothing. N+1 is accepted: the probe proved
   `properties` carries no build summary on 9.4.22, and ~25 light calls per sync is the price.
2. **Build-item conventions mirror GitHub's** so the existing pipeline picks them up unchanged
   where possible: `extId` = head commit SHA; `meta = branch + "," + PROJ/slug` (the Builds
   service recovers the repo from `meta[1]`); `withUrl` = the Jenkins build URL from the status.
   The Jira key (`commit.properties.jira-key`, resolved by Bitbucket for free) rides in the
   item metadata.
3. **`BuildFailureService` dispatches by source instead of hardwiring GitHub.** The failed work
   item's `source` name → `SourceInstanceRepository.findByNameIgnoreCase` → instance type:
   `bitbucket` routes to a new `BitbucketBuildAnalyzer`, anything else keeps the GitHub path
   exactly as-is. `resolveRunId` additionally accepts a 40-hex commit SHA as an explicit id
   (GitHub run ids are numeric; the two cannot collide).
4. **`BitbucketBuildAnalyzer` is metadata-only, and says so.** Sequence per the probe: old API
   (`build-status/1.0/commits/{sha}`) to enumerate build keys → new API
   (`/rest/api/latest/projects/{p}/repos/{r}/commits/{sha}/builds?key=`) per key to enrich
   (`buildNumber`, `duration`, `ref`, commit message). The local model summarizes from: state,
   Jenkins job name, description, duration, branch, commit message, Jira key. The log panel is
   one honest line — logs live in Jenkins, with the deep link — never a fake tail. `analyzedBy`
   labels the result metadata-only. Hypothesis confidence is capped at `med`: without logs,
   `high` would be a lie.
5. **Credentials** come from the existing source instance via `SourceCredentialStore.secrets()`
   (HTTP access token, `Bearer`). No new credential, no new config.
6. **Degradation:** unreachable Bitbucket (VPN down) at sync → existing source-error chip; at
   analyze → the screen's existing honest empty/error state. Neither Today nor Builds breaks.

## Out of scope (named, not implied)

- **Jenkins log fetching** — second credential + its own API; a follow-up design. The deep
  console link (`…/PR-749/13/console`) is inference from URL shape and stays a *link*, not data.
- **Test counts** — this CI never publishes `testResults` to Bitbucket; designing UI around a
  number that isn't there was the trap the probe caught.
- **Bitbucket Cloud Pipelines** — different API family; add when a Cloud source exists.
- The Handoff flow keeps working via the shared `Dto.BuildFailure`; a Bitbucket-tailored handoff
  template is follow-up polish.

## Success criteria

- Compile clean; no Flyway migration (no schema change — work items already fit).
- With a red Bitbucket PR: after sync, a `build` work item exists (Today/Work show it, ranked by
  the existing `build` weight), and the Builds screen renders repo/branch/PR/run, a metadata
  analysis, the Jenkins link, and no fabricated log lines. **Verified live from the VPN-side
  session** via a checklist shipped with the plan.
- With Bitbucket unreachable: sync marks the source `error`; Builds shows its honest state.
- GitHub builds behave byte-identically to before (dispatch is additive).
