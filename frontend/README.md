# DevLoom — frontend

Vue 3 + TypeScript + Vite + Pinia. Phase 2C scaffold: the app shell + the **Today** screen, built against a stubbed API. Derives all visual values from [../DESIGN.md](../DESIGN.md).

## Run

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173
```

## What's here

- `src/styles/tokens.css` — the design tokens (single source of truth; matches DESIGN.md, incl. the `--faint-text` AA fix and the dark/paper themes).
- `src/api/stub.ts` — stubbed `fetchToday()` standing in for `GET /api/v1/dashboard` + `/next` (SPEC.md §34). Swap for a real fetch client when the backend exists.
- `src/stores/dashboard.ts` — Pinia store (loading / error / ready).
- `src/components/` — `AppRail`, `WarpList`, `RecommendationCard`, `Mono` (the evidence primitive), `BoundaryToken`.
- `src/views/TodayView.vue` — the Today screen with loading / error / ready states.

## Notes

- Fonts are dev stand-ins (Hanken Grotesk + JetBrains Mono via Google Fonts). Production self-hosts **Commit Mono** for the evidence role (DESIGN.md §1).
- Icons are unicode stand-ins; production uses **Phosphor (light)** (DESIGN.md §4).
- Only the Today screen is implemented in this scaffold; other screens exist as mockups in [../mockups/](../mockups/).
