# DevLoom — Design System (High-Fidelity Reference)

> **Status:** Phase 2B — the definitive, build-ready design system. Implementation (Phase 2C) derives every value from this file.
> **Date:** 2026-08-08. Builds on [UI-SPEC.md](UI-SPEC.md); demonstrated in [mockups/](mockups/).
> **Principle carried into pixels:** *evidence over assertion* → **mono = verified evidence, grotesque = reasoning**; the **warp** ranks; **threads** cite. Calm by construction: one accent, desaturated status, load-bearing hairlines only.

This is the layer the earlier docs deferred: exact tokens, a real contrast audit, finalized type/space scales, an icon set, motion specs, and component state matrices.

---

## 1. Typography (locked)

| Role | Face | License | Source | Used for |
|---|---|---|---|---|
| **Prose / UI** | **Hanken Grotesk** | SIL OFL 1.1 (free, commercial-ok) | Google Fonts | body, headings, labels, reasoning text |
| **Evidence / data** | **Commit Mono** | Free (incl. commercial) | commitmono.com (self-host woff2) | SHAs, IDs, signal values, timestamps, logs, code, the handoff artifact |

- Both self-hosted as `woff2` in the app (no runtime dependency on Google's CDN — consistent with the self-hosted, privacy-first posture). Subset to Latin.
- **The epistemic rule is enforced in components, not left to authors:** a `<Mono>` primitive wraps all machine-derived values; prose components use Hanken. A lint check flags raw numbers/SHAs rendered in the sans face inside evidence contexts.
- Fallback stacks: `"Hanken Grotesk", system-ui, sans-serif` · `"Commit Mono", ui-monospace, "JetBrains Mono", monospace`.

**Type scale** (base 16px = 1rem; line-heights tuned for a dense-but-calm dashboard):

| Token | rem / px | weight | line-height | Use |
|---|---|---|---|---|
| `text-mono-chip` | 0.75 / 12 | 400/500 | 1.35 | signal chips, meta, timestamps |
| `text-label` | 0.8125 / 13 | 500 | 1.4 | uppercase mono eyebrows (letter-spacing .14em) |
| `text-ui` | 0.875 / 14 | 400/500 | 1.5 | controls, list rows |
| `text-body` | 1.0 / 16 | 400 | 1.55 | reasoning prose |
| `text-card` | 1.25 / 20 | 600 | 1.3 | card titles |
| `text-section` | 1.5 / 24 | 600 | 1.25 | section headers |
| `text-screen` | 1.625 / 26 | 600 | 1.2 | screen titles |
| `text-readout` | 3.0 / 48 | 400 (mono) | 1.0 | empty-state instrument numerals |

Tracking: display/titles `-0.01em`; uppercase mono labels `+0.14em`; body `0`.

---

## 2. Color & the contrast audit

### 2.1 Dark theme tokens (default)

```
--bg #14161C  --surface #1B1E26  --raised #232732  --line #2E3340
--ink #E6E8EC  --dim #9AA1AE  --faint #6B7280      (see audit → add --faint-text)
--warp #C6902F  --warp-hi #E0A94A  --warp-weft rgba(198,144,47,.14)
--blocked #A65D57  --failed #B04A45  --healthy #6E8B6A  --stale #6C7689  --info #5F7E93
```

### 2.2 Contrast audit (computed, WCAG 2.2)

Ratios of text/element against its background. AA thresholds: **4.5:1** normal text, **3:1** large text (≥18.66px/24px bold) and UI components/graphics.

| Pair | Ratio | Verdict |
|---|---|---|
| `ink` on `bg` | **14.7:1** | ✅ AAA |
| `ink` on `surface` | ~13:1 | ✅ AAA |
| `dim` on `bg` | **6.96:1** | ✅ AA (normal) |
| `faint` on `bg` | **3.74:1** | ⚠️ AA **large/graphic only** — fails 4.5 for small text |
| `warp` on `bg` | **6.4:1** | ✅ AA (normal) |
| `warp-hi` on `bg` | ~8.5:1 | ✅ AAA |
| button ink `#1A1204` on `warp` | **6.76:1** | ✅ AA (primary button) |
| `blocked` on `bg` | **3.73:1** | ⚠️ large/graphic only |
| `failed` / `healthy` / `stale` on `bg` | ~3.4–3.9:1 | ⚠️ large/graphic only |

### 2.3 Two fixes this audit forces (adopted)

1. **Add `--faint-text: #808896` (5.06:1) for small text.** Keep `--faint #6B7280` for **decorative/graphical** use only (hairline-adjacent, disabled glyphs). Any label ≤14px uses `--faint-text`. *(The mockups use `--faint` on some small uppercase labels; implementation uses `--faint-text` there.)*
2. **Status color is a graphic, not small text.** Status hue lives on the **dot/icon** (a graphical object → 3:1 is sufficient); the accompanying **word** renders in `--dim`/`--ink`. Never set a small status word in its own low-ratio hue. `--failed`/`--blocked` may be used for larger text (≥ large threshold) where earned (e.g. a failure heading).

### 2.4 Paper (light) theme

```
--bg #E7E9E5  --surface #F1F2EE  --raised #FBFBF8  --line #CFD3CC
--ink #1B1E26  --dim #565C68  --faint-text #6E7480
--warp #A9761E (darkened for AA on light)  --warp-hi #8A5E12
status hues darkened ~12% for ≥4.5 where used as text
```
Audit spot-checks: `ink`/`bg` 13.9:1 ✅; `dim`/`bg` 5.9:1 ✅; `warp`(#A9761E)/`bg` 4.6:1 ✅ (normal text ok). Theme switch is a `data-theme` attribute swapping the custom-property block; all components reference tokens only.

---

## 3. Space, grid, radius, elevation

- **Grid:** 4px base. Space scale `4 · 8 · 12 · 16 · 24 · 40 · 64`. Layout gutters 24; card padding 14–16; section gaps 20–24.
- **Layout columns:** rail `212px` · focus `fluid` · inspector `340px` (desktop). Brainstorm adds a `182px` sessions column and a `246px` tray. Content max-width for prose blocks `~68ch`.
- **Radius:** controls `6px` · cards `10px` · drawers/sheets/modals `14px` · phone frame `28px`. No pills except filter chips (`20px`) and status dots.
- **Elevation:** flat and quiet — a single low-opacity shadow for popovers/drawers (`0 20px 50px -30px #000`), none on resting cards. Depth comes from `surface`→`raised`, not heavy shadow.
- **Focus ring (token, non-negotiable):** `0 0 0 2px var(--warp)` + `2px` offset, on every interactive element, both themes.

---

## 4. Iconography

- **Set: Phosphor Icons, "light" weight** (open, MIT). Chosen over Lucide/Feather because its thin, even stroke reads as *instrument/calm* rather than utilitarian, matching the brief; and its duotone option can tint the active/warp state without a second asset. The unicode glyphs in the mockups (◉ ▤ ⚡ ✎ ⇥ ⧉ ⎇) are **stand-ins** → map to Phosphor:

| Concept | Phosphor icon |
|---|---|
| Today | `sun-horizon` / `target` |
| Work | `rows` |
| Builds | `lightning` |
| Brainstorm | `pen-nib` |
| Handoffs | `arrow-square-out` |
| PR / branch | `git-pull-request` / `git-branch` |
| Task | `diamond` |
| Build/CI | `lightning` |
| Calendar event | `calendar-dot` |
| Source / citation (`⧉`) | `link-simple` (the thread endpoint) |
| Boundary local | `house` · remote `broadcast` · mixed `circle-half` |
| Sync health | filled `circle` (state via color+title) |

- Icons are **16px** in UI, **20px** in the rail, stroke aligned to the 4px grid, and always paired with a text label or an `aria-label` (never icon-only without an accessible name).
- Status is **icon + color + word** (per §2.3) so it never relies on color alone.

---

## 5. Motion

Restraint is the rule — one signature moment, everything else near-still. **All of the below collapse to instant under `prefers-reduced-motion: reduce`.**

| Motion | Spec | Notes |
|---|---|---|
| **Thread-draw (signature)** | provenance threads stroke-dashoffset 0→full, `180ms ease-out`, staggered 30ms per thread | fires when a recommendation/analysis resolves; reduced-motion → appear instantly |
| Hover highlight (claim ↔ source) | color/opacity `120ms ease-out` | warp-hi on the claim, its threads, and source chips together |
| Card enter | opacity+translateY(4px) `160ms ease-out` | list items only, not on every re-render |
| Streaming (SSE) | tokens append; a quiet mono `…` pulse `1s ease-in-out` infinite | no spinners anywhere |
| Drawer/sheet | transform `200ms cubic-bezier(.2,.7,.2,1)` | inspector, mobile sheets |
| Skeleton shimmer | background-position `1.2s linear` infinite | disabled under reduced-motion (static gradient) |

Durations tokenized: `--motion-fast 120ms · --motion 180ms · --motion-slow 200ms`. Easing: `--ease-out cubic-bezier(.2,.7,.2,1)`.

---

## 6. Component state matrix

Every interactive component defines the five states against tokens (dark shown; paper mirrors via token swap):

| Component | rest | hover | focus (kbd) | active/selected | disabled |
|---|---|---|---|---|---|
| **Button — primary** | bg `warp`, text `#1A1204` | bg `warp-hi` | +focus ring | inset darken 6% | opacity .45, no ring |
| **Button — default** | bg `#1A1D24`, border `line`, text `ink` | border `warp` | +focus ring | bg `raised` | opacity .45 |
| **Button — ghost** | transparent, text `dim` | text `ink` | +focus ring | text `ink` | opacity .4 |
| **Nav item** | text `dim` | text `ink`, bg `#171B22` | +focus ring | bg `warp-weft`, `inset 2px warp`, text `ink` | — |
| **Filter chip** | border `line`, text `dim` | border `warp` | +focus ring | bg `warp-weft`, border `warp`, text `ink` | — |
| **Recommendation card** | `surface`, border `line` | border lightens to `#39404E` | +focus ring on card | lead: `inset 2px warp` + weft wash | — |
| **Source chip** | `#191C23`, mono, `dim` | its threads+claim highlight `warp-hi` | +focus ring, "supported by N" announced | — | — |
| **Signal bar** | track `#20242D`, fill `warp→warp-hi` | tooltip (grotesque) explains signal | tooltip on focus | — | — |
| **Input / select** | border `line`, `#191C23` | border `#39404E` | +focus ring | — | opacity .5 |
| **Boundary token** | local: `dim`+house; remote: `warp` border+broadcast | — | — | — | — |
| **Toggle (local-only, fallback)** | off: track `line` | — | +focus ring | on: track `warp` | — |

---

## 7. Theming & implementation contract

- **Tokens are CSS custom properties** on `:root` (dark) and `[data-theme="paper"]`. Components reference `var(--…)` exclusively — no hard-coded hex in component styles. This is the single rule that makes theming and the audit fixes propagate.
- **Primitives** the app ships (map 1:1 to the component inventory in UI-SPEC §19): `<Mono>`, `<Reasoning>` (adds the `reasoning°` marker + SR label), `<SourceChip>`, `<ProvenanceThread>`, `<SignalBar>`, `<BoundaryToken>`, `<StatusDot>`, `<WarpList>`, `<RecommendationCard>`, plus the screen components.
- **Accessibility carried from UI-SPEC §17:** focus ring token everywhere; status = icon+color+word; `aria-live="polite"` on streaming regions; the mono/grotesque split reinforced with SR-only labels ("verified data" / "model reasoning"); 400% reflow; ≥24px targets.
- **Default theme:** dark (recommended for the all-day dev tool). Paper is a first-class toggle, not an afterthought.

---

## 8. What B changed vs. the mockups

The mockups (Phase 2A) are faithful to this system with three deliberate deltas, now locked for implementation:

1. **Fonts:** JetBrains Mono (mockup stand-in) → **Commit Mono** (self-hosted).
2. **Small-label contrast:** `--faint` on small labels → **`--faint-text #808896`** (AA fix from §2.3).
3. **Icons:** unicode glyphs → **Phosphor light** set (§4).

Everything else — palette, warp, threads, layout, spacing, the epistemic type split — carries verbatim. Implementation (C) builds from this file; the mockups remain the visual reference.
