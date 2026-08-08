# Handover 01 — Frontend MVP screens

**Date:** 2026-08-08. **Step:** 1 of 5. **Status:** ✅ complete & browser-verified.

## What was built

The Vue app in `frontend/` now has **routing and every MVP screen**, all against the stub API. Vite 8 build + `vue-tsc` type-check both pass clean.

### New/changed files
- `src/router/index.ts` — routes: `/today`, `/work`, `/builds`, `/builds/:id`, `/handoffs/:id?`, `/brainstorm`, `/onboarding`, `/settings/{integrations,providers,privacy}`. Lazy-loaded (code-split — see build output, one JS/CSS chunk per view).
- `src/main.ts` — registers pinia + router.
- `src/App.vue` — rail + `<RouterView>`; onboarding is chromeless (no rail).
- `src/components/AppRail.vue` — nav is now `<RouterLink>`s with `active-class="on"`; Settings link added.
- `src/components/SourceChip.vue` — reusable evidence chip (mono id + optional boundary badge).
- `src/views/` — `TodayView` (from before) + **new**: `WorkView`, `BuildFailureView`, `HandoffView`, `BrainstormView`, `OnboardingView`, `IntegrationsView`, `ProvidersView`, `PrivacyView`.
- `src/types.ts` — added types for all screens (WorkRow, BuildFailure/Hypothesis/LogLine, Handoff, Integration, ProvidersData, PrivacyData, BrainstormData, OnboardStep).
- `src/api/stub.ts` — added `fetchWork / fetchBuildFailure / fetchHandoff / fetchIntegrations / fetchProviders / fetchPrivacy / fetchBrainstorm / fetchOnboarding`, all with simulated latency via `delay()`.
- `package.json` — added `vue-router ^4.6.4`.

### Design-contract adherence
- All component styles use `var(--…)` tokens only (no hardcoded hex) — the paper theme still works.
- Epistemic split honored: mono for evidence/data/logs/IDs (`Mono`, `SourceChip`, log viewer, handoff artifact), grotesque for prose; LLM output carries the `reasoning°` marker.
- Build-failure screen shows truncation (`[… N lines omitted …]`) + redaction (`‹SECRET REDACTED›`) markers; the low-confidence cause is labeled `uncited · low`.
- Handoff screen: mono artifact + boxed safety block + Copy (uses clipboard) + disabled "Run" (no auto-execution in MVP).

## Verification
- `npm run build` → exit 0 (Vite 8.2.1), per-route code splitting confirmed.
- `npm run dev` + browser: verified **Today** (earlier), **Build-failure** (`/builds/1893`), **Brainstorm** (`/brainstorm`) render correctly and rail navigation switches the active item. Screenshots: `frontend/screenshots/work-build.png`, `work-brainstorm.png` (+ earlier `today-dark.png`, `today-paper.png`).

## How to run
```bash
cd frontend && npm install && npm run dev   # http://localhost:5173 (or next free port)
```

## Known gaps / notes for the next session
- Screens are wired to **stub data**; actions (buttons, filters beyond the Work tab toggle, Save-as, override form) are presentational — they don't mutate yet. Wire them to real endpoints in Step 5.
- Fonts/icons are still dev stand-ins (JetBrains Mono / unicode glyphs). Swap to Commit Mono + Phosphor per DESIGN.md when polishing.
- The `Recommendation.signals/evidence` (expanded inspector from the mockups) isn't shown as a drawer yet — TodayView renders the compact cards; the expanded evidence view is speced (UI-SPEC §8) and stubbed in data, to be added when wiring the inspector.
- No command palette (`⌘K`) yet — speced, deferred.

## Next: Step 2 — backend skeleton
Spring Boot 4 modular monolith built/run in Docker (no local Java). See `02-backend.md` when done. The frontend's `src/api/stub.ts` is the contract the backend must satisfy (shapes = SPEC §34 DTOs).
