# PR authorship and role, everywhere — design

**Date:** 2026-08-19 · **Status:** approved in chat ("please go ahead")

## Why

PRs currently carry no authorship: Work's `mine` filter string-matches a baked status label,
Today can't tell "I wrote this" from "this needs my review", and no card says who created a PR.
The user asked for: Work filters **Mine / To Review / Others**, Today flagging authored vs
assigned separately, and **"Created by: …"** on every PR card under the source.

## Decisions

1. **Two nullable columns on `work_item` (migration V24):** `author VARCHAR(120)` (the creator's
   login/display name) and `pr_role VARCHAR(10)` — `mine` | `review` | `other`, null for
   non-PR items. Role is computed at sync time by the connector, which is the only place that
   knows who the authenticated user is; the LLM and the UI never guess it.
2. **GitHub:** the connector already computes `mine` (author login == token login) and `myReview`
   (review-requested set) per item — persist them: `author = user.login`,
   `prRole = mine → "mine"`, `myReview → "review"`, else `"other"` (PRs and review items only).
3. **Bitbucket DC:** `author = author.user.displayName` (fallback `name`) from the dashboard PR
   object. Role via two cheap id-set calls to the same endpoint with its documented `role`
   parameter (`?role=AUTHOR`, `?role=REVIEWER`, ids only) — membership decides
   `mine`/`review`/`other`. The `role` param is Atlassian-documented but **not probe-verified**:
   if either call fails, every PR falls back to `other` with a warn log, and the sync never
   breaks. The VPN-side checklist verifies the param against the real instance.
4. **DTOs and mappers:** `WorkRow` and `Recommendation` gain `author` + `prRole`; the three
   mappers (WorkModelService.toRow, TodayService's rec builders, BriefingService's rebuild)
   carry them through. Frontend types mirror.
5. **UI:** Work — when the type filter is `PRs` (or `Reviews`), a role sub-filter row appears:
   All / Mine / To Review / Others, matching `prRole`; the legacy top-level `mine` filter now
   matches `prRole === 'mine'` instead of string-matching status. Today — PR cards get a small
   role chip (`yours` / `review`) and a `Created by: <author>` line under the source chip
   (per the user's sketch). Cards without an author show nothing — no empty labels.
6. **Out of scope:** Jira/task authorship (the ask was PRs), assignee display for non-PRs,
   avatars.

## Success criteria

- V24 applies; compile + vue-tsc clean.
- **Locally verifiable on live GitHub data:** after sync, `/api/v1/work` PR rows carry
  `author`/`prRole`; the sarasnt-authored PRs read `mine`; Work's Mine/To Review/Others
  sub-filter partitions correctly; Today PR cards show "Created by".
- Bitbucket authorship shows after the VPN-side re-sync; role columns verified there via the
  checklist addendum.
- Non-PR items are untouched (null author/role, no UI residue).
