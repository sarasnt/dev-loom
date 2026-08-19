# Verify Bitbucket builds — run from the VPN-side session

Prereqs: images republished by CI after the feature merged (`docker compose pull`), stack up,
the Bitbucket source configured and reachable.

1. `docker compose pull && docker compose up -d` (COMPOSE_FILE is pinned on this machine).
2. Settings → Sources → the Bitbucket source → **Re-sync**. Expect `connected · N items` where
   N now exceeds the PR count when any PR is red.
3. `curl -s http://localhost/api/v1/work | python3 -c "import sys,json;print([r['title'] for r in json.load(sys.stdin) if r['type']=='build'])"`
   → expect `CI failed · #<pr> · <title>` entries for red PRs (none if everything is green —
   check the Bitbucket UI for a red PR first; #749 was red on 2026-08-15).
4. Open **Builds**: the failure renders repo (`PROJ/slug`), branch, PR #, build #; the summary
   is present; `analyzedBy` ends with `metadata only (no log available)`; the log panel shows
   the Jenkins pointer line + URL and **no fabricated log lines**.
5. Click the Jenkins link — it must open the real build.
6. Today/Work: the build card ranks near the top (build weight 0.95) and Open works.
7. Negative path: disconnect VPN, Re-sync → the source chip goes error; Builds still renders
   its honest state. Reconnect, Re-sync → recovers.

Report results (numbers + any deviation) back to Sara / the design session.

## PR authorship (added 2026-08-19) — same VPN-side run

8. After re-sync: `curl -s http://localhost/api/v1/work | python3 -c "import sys,json; print([(r['title'][:30], r['author'], r['prRole']) for r in json.load(sys.stdin) if r['type']=='pr'])"`
   → every Bitbucket PR shows its creator's display name; PRs you opened read `mine`, PRs
   awaiting your review read `review`, the rest `other`. If ALL read `other`, the dashboard
   `role` parameter failed on this instance — check backend logs for "role filter … unavailable"
   and report exactly that. If ALL read `mine`, the role parameter was ignored by this instance —
   the sync now detects identical AUTHOR/REVIEWER sets and falls back, so all-`mine` should not
   occur; report it if it does.
9. Work → PRs: the Mine / To Review / Others chips partition the list; Today: PR cards show
   "created by …" under the source and a `yours`/`for review` chip where it applies.
