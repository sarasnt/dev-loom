# DevLoom — UI & UX Specification (Phase 2)

> **Status:** Phase 2 deliverable — low-fidelity structure & interaction design for review.
> **Date:** 2026-08-08. Depends on the approved [SPEC.md](SPEC.md).
> **Fidelity:** This is **low-fidelity** — information architecture, navigation, layout structure, interaction design, and a design-token/component system. Wireframes are ASCII/box sketches, not pixels. **No production frontend code is written until this spec is approved** (per the brief).

**What the UI must make effortless** — the seven questions from the brief:

1. What needs my attention? 2. Why does it matter? 3. What changed? 4. What is blocked? 5. What should I do next? 6. What evidence supports this suggestion? 7. Can an agent help me prepare or fix it?

The whole design is organized so these are answerable in one glance and one click — calm, not noisy.

---

## Table of contents

1. [Design language & rationale](#1-design-language--rationale)
2. [Design tokens](#2-design-tokens)
3. [Information architecture](#3-information-architecture)
4. [Navigation model](#4-navigation-model)
5. [Screen inventory](#5-screen-inventory)
6. [Primary workflows](#6-primary-workflows)
7. [Dashboard layout ("Today")](#7-dashboard-layout-today)
8. [Priority recommendation cards](#8-priority-recommendation-cards)
9. [Source & evidence presentation](#9-source--evidence-presentation)
10. [Build-failure analysis screen](#10-build-failure-analysis-screen)
11. [Coding-agent handoff screen](#11-coding-agent-handoff-screen)
12. [Brainstorming workspace](#12-brainstorming-workspace)
13. [Integration setup](#13-integration-setup)
14. [Model-provider settings](#14-model-provider-settings)
15. [Privacy & data-boundary controls](#15-privacy--data-boundary-controls)
16. [State design: loading, empty, error, stale, partial-sync](#16-state-design)
17. [Accessibility requirements](#17-accessibility-requirements)
18. [Responsive behavior](#18-responsive-behavior)
19. [Component inventory](#19-component-inventory)
20. [Open UI questions & Phase 2 closeout](#20-open-ui-questions--phase-2-closeout)

---

## 1. Design language & rationale

**Subject & job.** DevLoom is a *calm engineering command center* for one developer, used all day, that answers "what deserves my attention and why." Its audience is technical and keyboard-driven. Its single job on open: **rank + justify**.

**Two design decisions carry the whole identity:**

### 1.1 The epistemic type split (signature #1)

The product's first principle is **evidence over assertion**. The type system makes that visible:

- **Monospace = verified fact / machine data.** Commit SHAs, PR/issue IDs, signal values (`review_wait=51h`), timestamps, counts, log excerpts, code, file paths, the handoff artifact.
- **Humanist grotesque = prose / reasoning.** LLM explanations, summaries, hypotheses, UI chrome, labels.

So *before reading*, you can see which parts of a card are ground truth and which are the model's interpretation. LLM reasoning is **always** grotesque and always carries a small `reasoning` marker; the citations embedded in it are mono links (the threads, below). This isn't decoration — it's the principle rendered as typography.

### 1.2 The provenance thread & the warp (signature #2)

DevLoom means "weaving threads of work together." The signature interaction:

- The ranked **"Next" list is a warp** — items hang off a single vertical warp line under tension; priority = position on the warp.
- Every claim (a recommendation, a hypothesis, a "why this matters") shows its **citations as threads** connecting the prose to **source chips**. Hover/focus a claim → its threads and source chips highlight in warp-gold; hover a source chip → the claims it supports highlight. This is how "what evidence supports this?" becomes a physical property of the screen, not a footnote.

Threads are drawn as hairlines — but unlike the "broadsheet hairline" default, **every hairline here means provenance or dependency**; there are no decorative rules.

### 1.3 Calm by construction

- **Restraint in color.** One accent (warp brass). Status hues are deliberately **desaturated** — a blocked item is muted clay, not alarm-red; a passing build is sage, not neon-green. Loud color is reserved for genuine failures. Priority/attention is shown by **position on the warp + a warp-threaded edge**, not by shouting color. This is the deliberate opposite of a noisy Jira board.
- **Negative space is the default surface.** The dashboard shows *few* things prominently and hides the rest behind "Everything" — the product's job is to *not* show you 200 cards.
- **One motion moment:** threads draw in (~180ms) when a recommendation resolves; everything else is still. Fully disabled under `prefers-reduced-motion`.

### 1.4 Self-critique (did I avoid the defaults?)

- Not **cream+serif+terracotta**: the base is cool indigo-ink, no serif, no terracotta.
- Not **black+acid-green**: base is `#14161C` (indigo-ink, not `#000`), accent is a muted brass, no acid accent.
- Not **broadsheet hairlines**: hairlines are load-bearing (provenance/dependency), never texture.
- Risk taken (justified): the **mono-vs-grotesque epistemic split** is unusual and slightly demanding, but it directly serves the product's defining principle — worth it. If it proves heavy in testing, the fallback is to keep mono for data only in evidence contexts (cards/analysis) and normal UI type elsewhere.

---

## 2. Design tokens

Low-fidelity token system (names + values are the contract; exact hues tunable in hi-fi). **Dark-first** (developer tool, all-day use) with a cool **"paper" light mode**.

### 2.1 Color

**Dark (default)**
```
--bg            #14161C   deep indigo-ink (app background)   [not pure black]
--surface       #1B1E26   cards, panels
--surface-raised#232732   popovers, drawers, active card
--line          #2E3340   hairlines / provenance threads (rest state)
--ink           #E6E8EC   primary text
--ink-dim       #9AA1AE   secondary text / prose captions
--ink-faint     #6B7280   tertiary / labels / disabled

--warp          #C6902F   THE accent — warp thread, focus ring, primary action
--warp-hi       #E0A94A   warp hover / active thread highlight
--warp-weft     rgba(198,144,47,.14)  warp wash (selected row, threaded edge)

# status — deliberately desaturated (calm)
--blocked       #A65D57   muted clay  (blocked / blocking others)
--failed        #B04A45   brick       (build/deploy failure — the one earned saturation)
--healthy       #6E8B6A   sage        (green build / merged)
--stale         #6C7689   slate       (stale PR / waiting / going cold)
--info          #5F7E93   dust-blue   (neutral notices)
```

**Light "paper" (cool, NOT cream)**
```
--bg            #E7E9E5   cool oat-grey
--surface       #F1F2EE
--surface-raised#FBFBF8
--line          #CFD3CC
--ink           #1B1E26
--ink-dim       #565C68
--ink-faint     #7C8493
# accent + status hold; darken warp to #A9761E for AA on paper
```

Both themes are validated to **WCAG 2.2 AA** for text (§17). Status color is **never** the sole signal — always paired with an icon + text label.

### 2.2 Typography

```
Grotesque (prose / UI / reasoning) : "Hanken Grotesk"   400 / 500 / 600
Mono (evidence / data / logs / code): "Commit Mono"      400 / 500        [alt: Geist Mono]
```
- No Inter/Roboto/system-sans (avoids templated defaults).
- **Type scale (rem):** 0.75 (mono chip) · 0.8125 (label) · 0.875 (UI) · 1 (body prose) · 1.25 (card title) · 1.5 (section) · 2 (screen title) · 3.25 (mono "instrument readout" for big empty-state numerals).
- **The rule:** machine-derived/verifiable → **mono**; human/AI narrative → **grotesque**. Enforced in components, not left to authors.

### 2.3 Space, radius, motion

```
--space  4 / 8 / 12 / 16 / 24 / 40 / 64        (4px base grid)
--radius 6 (controls) · 10 (cards) · 14 (drawers/sheets)   [soft, not pill, not zero]
--thread 1px hairline; active thread 1.5px @ --warp-hi
--motion-fast 120ms  --motion 180ms  ease-out;  ALL motion off under prefers-reduced-motion
--focus  2px --warp ring + 2px offset (always visible, keyboard-first)
--elevation quiet: shadows are low-opacity, single-layer; this is a flat, calm surface
```

### 2.4 The data-boundary token (privacy is a first-class visual)

A dedicated **egress state** rendered wherever data might leave:
```
boundary=local   →  icon ⌂ + ink text  "On your machine"      (calm, no accent)
boundary=remote  →  icon ◉ + --warp     "Leaves for {provider}" (warp = attention)
boundary=mixed   →  icon ◐ + --warp     "Partly leaves"         (must enumerate what)
```
This token drives the rail indicator (§4), the pre-action egress preview (§15), and per-source badges.

---

## 3. Information architecture

```
DevLoom
├── Today                     ← home: ranked "Next", What changed, attention
│   └── Recommendation detail (evidence drawer / full view)
├── Work                      ← browse everything, filterable
│   ├── Pull requests (mine · reviews requested · stale)
│   ├── Tasks (Jira issues/epics/sprints)
│   ├── Builds & deployments
│   └── Calendar (Google + Microsoft, unified)
│   └── Work-item detail (any type) → relationships, signals, sources
├── Builds                    ← build-failure center (failures first)
│   └── Build-failure analysis → Generate handoff
├── Brainstorm                ← sessions list + workspace
│   └── Session (chat + source tray + save-as)
├── Handoffs                  ← library of generated agent handoffs (secondary)
└── Settings
    ├── Integrations          (GitHub, Jira, Google Cal, Microsoft Cal)
    ├── Model providers       (Ollama local · optional Anthropic/OpenAI keys)
    ├── Privacy & data boundary (local-only repos, egress log, defaults)
    ├── Account               (login, profile)
    └── Data & retention      (retention windows, export, delete)
```

**Hierarchy principle:** *Today* is where you live; *Work* is where you dig; *Builds/Brainstorm/Handoffs* are focused surfaces; *Settings* is rarely visited but always trustworthy. Depth over breadth is mirrored in IA — a shallow, memorable tree.

---

## 4. Navigation model

**Persistent left rail** (the command surface), then a **focus column**, then a **contextual inspector** on the right. Rail is always visible on desktop; it carries navigation *and* live system truth (sync health, provider, data boundary) so trust signals are never more than a glance away.

```
┌───────────┐
│ DevLoom   │  workspace name
│           │
│ ◉ Today   │  ← primary nav (icon + label)
│ ▤ Work    │
│ ⚡ Builds  │     (badge: # new failures)
│ ✎ Brainstorm
│ ⇥ Handoffs│
│───────────│
│ SYNC      │  ← integration health (dots): GH ● Jira ● GCal ● MSCal ●
│  updated  │     "· 2m ago"   (mono timestamp)
│───────────│
│ MODEL     │  Qwen3-Coder (local)     ← current default model
│ BOUNDARY  │  ⌂ On your machine       ← the egress token (§2.4)
│───────────│
│ ⚙ Settings│
│ ⌘K search │
└───────────┘
```

- **Command palette (`⌘K` / `Ctrl-K`)** is the fast path — jump to any work item, run "analyze latest failure", "new brainstorm", "connect integration", switch model. Keyboard-first because the audience is.
- **Rail health dots** use color + shape + tooltip (never color alone): ● healthy, ◐ syncing, ◑ partial, ○ error.
- The **BOUNDARY** line in the rail is always truthful about the *current default*; any individual action that differs shows its own boundary inline before it runs.

---

## 5. Screen inventory

| # | Screen | Purpose | Key states |
|---|---|---|---|
| S0 | First-run / onboarding | Connect GitHub App, Jira, calendars; choose local model or add BYO key; mark local-only repos | empty, connecting, partial |
| S1 | **Today** (dashboard) | Ranked "Next" + What changed + attention groups | loading, empty, stale, partial-sync |
| S2 | Recommendation detail / evidence drawer | Full "why", signal breakdown, threaded sources, actions | streaming, hypothesis, error |
| S3 | Work browser | Browse/filter all work items | empty-per-filter, stale |
| S4 | Work-item detail | Any item: relationships, signals, sources | stale, partial |
| S5 | **Build-failure analysis** | Summary → failing step → evidence → ranked causes → fixes → handoff | collecting, streaming, low-confidence |
| S6 | **Coding-agent handoff** | Review/edit/copy the handoff artifact | draft, edited, copied |
| S7 | **Brainstorm workspace** | Chat + source tray + model switch + save-as | empty, streaming, boundary-remote |
| S8 | Settings · Integrations | Connect/disconnect, scopes, sync status | connected, error, reauth |
| S9 | Settings · Model providers | Local models + optional keys, test, defaults, per-task, cost caps | untested, testing, over-cap |
| S10 | Settings · Privacy & data boundary | Local-only repos, egress log, defaults | — |
| S11 | Settings · Data & retention | Retention windows, export, delete integration/account | confirm-destructive |

---

## 6. Primary workflows

Mapped to the SPEC journeys J1–J6.

- **W1 Morning triage (J1).** Open → *Today* → warp shows ranked Next → top card already explains *why* (grotesque) with threaded evidence (mono chips) → act / snooze / **override with reason**. Target: understand the top item in < 5 seconds, no click.
- **W2 Build-failure triage (J2).** *Builds* badge → failure → S5 streams summary → failing step + evidence → ranked causes (evidence vs hypothesis clearly split) → **Generate handoff**.
- **W3 Handoff (J3).** S6 opens pre-filled → review boxed safety constraints → edit → **Copy** (and/or export) → paste into agent. DevLoom never runs it.
- **W4 Catch-up (J4).** *Today* → "What changed since you last looked" strip → each change threaded to its source.
- **W5 Brainstorm (J5).** *Brainstorm* → new session → attach sources (tray shows exactly what's in context + each source's boundary) → switch model → chat → **Save as** note/task/decision/spec, linked to sources.
- **W6 Provider & privacy setup (J6).** S0 / Settings → add local model or BYO key → **test** → set default + per-task → mark local-only repos → see the boundary reflected in the rail.

---

## 7. Dashboard layout ("Today")

The home screen. Three zones: rail · focus (the warp) · inspector (collapsed until you open an item).

```
┌─rail─┬────────────────── FOCUS: Today ──────────────────┬──── inspector ────┐
│      │  Today                        Tue 8 Aug · 09:14   │  (collapsed until  │
│ ◉Tdy │  ────────────────────────────────────────────    │   a card is opened)│
│ ▤Wrk │  WHAT CHANGED  (since Mon 17:30)      [dismiss]    │                    │
│ ⚡Bld²│  ┌──────────────────────────────────────────┐    │                    │
│ ✎Brn │  │ 3 PRs merged · 1 build broke & recovered  │    │                    │
│ ⇥Hnd │  │ 2 reviews now waiting on you  ›            │    │                    │
│      │  └──────────────────────────────────────────┘    │                    │
│ SYNC │                                                    │                    │
│ ●●●● │  NEXT                          sorted: priority ▾  │                    │
│ 2m   │  ╻                                                 │                    │
│      │  ┃①┌──────────────────────────────────────────┐   │                    │
│MODEL │  ┃ │ Review PR #482  ·  acme/billing           │   │                    │
│Qwen  │  ┃ │ ‹grotesque› Teammate blocked 2d; CI green;│   │                    │
│local │  ┃ │  merges the release.                      │   │                    │
│      │  ┃ │ ‹mono chips›  blocks:1  wait:51h  rel ⚑    │   │                    │
│BNDRY │  ┃ │ [Open ▸]  [Snooze]  [Why]  [⇧ Override]    │   │                    │
│ ⌂ on │  ┃ └──────────────────────────────────────────┘   │                    │
│ your │  ┃②┌──────────────────────────────────────────┐   │                    │
│ mach │  ┃ │ Fix CI on feature/pricing · acme/billing  │   │                    │
│      │  ┃ │ ‹grotesque› 1st failure; likely your last │   │                    │
│ ⚙ Set│  ┃ │  commit touched the failing test.         │   │                    │
│ ⌘K   │  ┃ │ ‹mono› build:#1893  fail ⚑  age:22m        │   │                    │
│      │  ┃ │ [Analyze ▸] [Snooze] [Why]                 │   │                    │
│      │  ┃ └──────────────────────────────────────────┘   │                    │
│      │  ┃③ Stale PR — #455 idle 4d (going cold) ‹slate›   │                    │
│      │  ╹                                                 │                    │
│      │  ▸ Everything (37)   ▸ Snoozed (5)                 │                    │
└──────┴────────────────────────────────────────────────────┴───────────────────┘
```

- The **warp spine** (`┃`) runs down the left of the Next list; each item hangs off it, numbered by rank (the numbering *is* information — it's the priority order, which is exactly when frontend-design says numbering is legitimate).
- Cards show **prose why (grotesque)** + **evidence chips (mono)**. Nothing shouts; the *release* flag ⚑ and *fail* flag ⚑ are the only warm marks.
- **"What changed"** is a dismissible strip at top (W4). **"Everything"** is collapsed — the calm default is the short list.
- Empty/first-run and stale variants in §16.

---

## 8. Priority recommendation cards

The core object. Two states: **compact** (in the Next warp) and **expanded** (opens the inspector / S2).

**Compact anatomy**
```
① ┌────────────────────────────────────────────────┐
  │ {Title, grotesque 1.25}      {source glyph: ⎇PR} │
  │ {Why — grotesque prose, 1–2 lines}   reasoning°  │  ← ° = LLM marker
  │ {signal chips — MONO}: blocks:1 wait:51h rel⚑    │
  │ [Open ▸] [Snooze] [Why ⌄] [⇧ Override]           │
  └────────────────────────────────────────────────┘
```

**Expanded (inspector) — the evidence & reasoning view**
```
┌─ Review PR #482 ─────────────────────────────── ✕ ┐
│ acme/billing · #482 · opened 3d      ⎇ github  ⌂  │  ← boundary token per item
│                                                   │
│ WHY THIS IS #1                          reasoning°│
│ ‹grotesque› A teammate has been blocked on this   │
│ for 2 days, CI is green, and it's tagged for the  │
│ release cut Thursday. Clearing it unblocks them   │
│ and de-risks the release.                         │
│    └─thread→ ⧉ #482  └─thread→ ⧉ TICKET-91        │  ← provenance threads
│                                                   │
│ SIGNALS (deterministic)                  weight   │
│ blocks teammate    ██████████  1 dev      ×0.9    │  ← MONO values + bars
│ review wait        ███████     51h        ×0.7    │
│ release impact     ████████    tagged     ×0.8    │
│ freshness          ██████████  2m ago     ×1.0    │
│ ── score 0.86 · algo v3 ──────────────────────    │
│                                                   │
│ EVIDENCE (sources in this reasoning)              │
│ ⧉ PR #482  acme/billing         ⌂ local   [open]  │
│ ⧉ TICKET-91 "Checkout 500s"     ⌂ local   [open]  │
│                                                   │
│ [Open PR ▸] [Snooze ⌄] [⇧ Override priority]      │
└───────────────────────────────────────────────────┘
```

- **Signals are mono + bar + explicit weight** → the ranking is transparent and inspectable (SPEC §22). Hovering a signal explains it in a grotesque tooltip.
- **Override** opens a small form: adjust up/down + **required reason** (grotesque textarea). Copy: *"Why should this rank differently?"* Saved overrides show a small `overridden` tag and (later) feed learning.
- **Hypotheses vs facts:** any LLM sentence sits in grotesque with the `reasoning°` marker; cited items are mono thread-links. A claim with no citation is visually flagged `uncited` and de-emphasized (the design refuses to let assertion masquerade as evidence).

---

## 9. Source & evidence presentation

The through-line of the whole product. Rules, applied everywhere (cards, analysis, chat):

- **Source chip** (mono): `⧉ {type}{id}` + short title + **boundary badge** (`⌂` local / `◉` remote) + `[open ↗]`. Chip color is neutral; hover highlights its threads in warp.
- **Provenance thread:** a hairline from a prose claim to the chip(s) it rests on. Hover/focus either end lights the pair. Keyboard: focusing a claim announces "supported by 2 sources" and Tab moves through them (§17).
- **Evidence vs hypothesis** is a hard visual contract:
  - `fact` — mono, from a real source, threaded.
  - `hypothesis` — grotesque, `reasoning°` marker, confidence shown as `conf: high/med/low` (mono).
  - `uncited` — flagged; never styled like a fact.
- **"Sources included" tray** (chat/analysis): an always-visible list of exactly what's in the model's context, each with a boundary badge and a remove control. Nothing enters context invisibly.

---

## 10. Build-failure analysis screen

The signature workflow (SPEC §23). Reads top-to-bottom as the 10-part contract, streaming in as analysis completes.

```
┌─ Build failure · acme/billing · run #1893 ───────────────── ⌂ local ─┐
│ feature/pricing · PR #482 · failed 22m ago            [Re-analyze ↻]  │
│                                                                       │
│ ① SUMMARY                                                  reasoning° │
│   ‹grotesque› The `test` job failed on one assertion in               │
│   PricingServiceTest; your commit d4e5f6 is the only change touching  │
│   that path. Most likely a tier-boundary off-by-one.        conf: high│
│                                                                       │
│ ② FAILING STEP                                              ‹MONO›     │
│   job: test → step: ./gradlew test                                    │
│   test: PricingServiceTest.appliesTierDiscount                        │
│                                                                       │
│ ③ LOG EXCERPT                          [redacted ✓] [full log ↗]      │
│   ┌───────────────────────────────────────────────────────────┐ MONO │
│   │ …                                                          │      │
│   │ expected: 90.00 but was: 100.00           ← first failure  │      │
│   │   at PricingServiceTest.appliesTierDiscount(:211)          │      │
│   │ [… 340 lines omitted …]                                    │      │
│   │ ‹SECRET REDACTED: env token›                               │      │
│   └───────────────────────────────────────────────────────────┘      │
│                                                                       │
│ ④ RANKED CAUSES                                                       │
│   1 �•high  Tier-boundary off-by-one in discount()        reasoning°   │
│         evidence→ ⧉ assert L211   ⧉ commit d4e5f6                      │
│   2 ▪low   Stale test fixture                            reasoning°   │
│         evidence→ (none in diff)  ← flagged low, uncited               │
│                                                                       │
│ ⑤ RELATED   ⧉ PR #482  ⧉ commit d4e5f6  ⧉ prior fail #1120  ⧉ TICKET-91│
│                                                                       │
│ ⑥ DIAGNOSTICS         ‹grotesque, numbered — a real sequence›         │
│   1. Run only the failing test locally.  [copy cmd ⧉]                 │
│   2. Inspect the tier boundary at qty=10.                             │
│                                                                       │
│ ⑦ POTENTIAL FIXES                                          reasoning° │
│   • Adjust the boundary check in discount() …                         │
│                                                                       │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │  ⇥  Generate agent handoff                        [Generate ▸]     ││
│ └──────────────────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────────────────┘
```

- **Evidence-first everywhere:** each cause carries its evidence threads; a cause with no evidence is explicitly `uncited · low` — the UI never lets a guess look like a finding.
- **Log viewer** is mono, shows **truncation markers** (`[… N lines omitted …]`) and **redaction badges** (`‹SECRET REDACTED›`) as first-class, reassuring the user that secrets were stripped before the model saw them (SPEC §23/§26). `[full log ↗]` opens the raw (still redacted) log.
- **Streaming:** sections ①→⑦ fill in as they resolve (SSE), each with a quiet mono placeholder; `aria-live=polite` announces section completion, not every token.
- **Local model reality (SPEC §20/§21):** if the failure context is too large for the local model, a calm inline note offers *"This log is large — summarize locally, or run this analysis once on {BYO model}? ◉ leaves for {provider}"* with the boundary shown before you choose.

---

## 11. Coding-agent handoff screen

```
┌─ Agent handoff · Fix CI on feature/pricing ───────── draft · v1 ─ ⌂ ─┐
│ target agent:  [ Claude Code ▾ ]   (affects header note only)        │
│                                                                      │
│ ┌── rendered (MONO, editable) ─────────────────────────────────────┐│
│ │ # Agent Handoff — Fix failing CI on feature/pricing              ││
│ │ Repo: acme/billing @ feature/pricing | PR #482 | a1b2c3..d4e5f6  ││
│ │ Failing: `test` → PricingServiceTest.appliesTierDiscount         ││
│ │ ## Reproduce … ## Evidence … ## Ranked hypotheses …              ││
│ │                                                                  ││
│ │ ╔═ SAFETY (locked defaults, editable) ══════════════════════════╗││
│ │ ║ ✓ Verify the fix with tests before claiming done              ║││
│ │ ║ ✗ Do NOT push / merge / deploy / delete without approval      ║││
│ │ ║ ✓ Allowed: read repo, ./gradlew test, edit src/tests          ║││
│ │ ║ ✗ Forbidden: network, deploy, destructive git                 ║││
│ │ ╚════════════════════════════════════════════════════════════════╝│
│ └──────────────────────────────────────────────────────────────────┘│
│ included sources: ⧉#482 ⧉d4e5f6 ⧉log(redacted) ⧉TICKET-91           │
│ [Copy]  [Export .md]  [Regenerate ⌄]        edited · not yet copied  │
└──────────────────────────────────────────────────────────────────────┘
```

- The **safety block is visually boxed and distinct** — the constraints from SPEC §24 render as a locked-but-editable panel so the user always sees "won't push/merge/deploy/delete" and "verify with tests" before copying.
- Fully **mono** (it's an artifact, not prose) and editable in place; edits mark the version `draft · edited`.
- Primary actions are **Copy** and **Export .md**. DevLoom does **not** execute the agent (SPEC §24) — no "Run" button exists in MVP; a disabled, tooltip'd placeholder communicates that direct invocation is a future, approval-gated capability.
- Included-sources row reuses the source-chip + boundary pattern (§9); the redacted log chip reassures that secrets are stripped.

---

## 12. Brainstorming workspace

```
┌─rail─┬──── sessions ────┬────────── conversation ──────────┬── source tray ──┐
│      │ + New session     │  Session: "Pricing refactor"      │ IN CONTEXT (3)   │
│ ✎Brn │  ─ Pricing refac. │                                   │ ⧉ PR #482    ⌂ ✕ │
│      │  ─ Release plan    │  you › should discount() be tier- │ ⧉ TICKET-91  ⌂ ✕ │
│      │  ─ (personal)      │        aware or flat?             │ ⧉ build#1893 ⌂ ✕ │
│      │                    │                                   │ [+ Add source]   │
│      │  visibility:       │  DevLoom › ‹grotesque› Given the   │                  │
│      │  ● personal        │  ticket and the failing test,     │ MODEL            │
│      │  ○ workspace       │  tier-aware is safer because…      │ Qwen3-Coder ▾    │
│      │                    │     └thread→ ⧉TICKET-91 ⧉build     │ ⌂ On your machine│
│      │                    │                        reasoning°  │                  │
│      │                    │  [ type a message …          ⏎ ]  │ BOUNDARY         │
│      │                    │                                   │ ⌂ all local      │
│      │                    │  Save as ⌄: Note·Task·Decision·Spec│                  │
└──────┴────────────────────┴────────────────────────────────────┴──────────────────┘
```

- **Source tray** shows *exactly* what's in context, each with a boundary badge and a remove ✕ — the SPEC's "show exactly which sources are included" made literal. Blank start is the default; nothing is auto-attached.
- **Model switch** is inline; changing to a BYO model updates the **BOUNDARY** panel and, if any attached source is a local-only repo, the switch is **blocked** with a calm explanation (SPEC §20 PrivacyGate) — you can't accidentally send local-only content remote.
- **Save as** turns output into a note / proposed task / decision / draft spec, **linked back to the sources** (threads persist into the saved artifact). `doc-coauthoring` skill territory when we build the "draft spec" path.
- **Visibility** toggle (personal ↔ workspace) is per session; personal is default (SPEC §26). In the single-user MVP this still exists but is quiet.
- Streaming reasoning uses `aria-live=polite`; the "thinking" indicator is a quiet mono ellipsis, not a spinner.

---

## 13. Integration setup

First-run (S0) and Settings (S8) share the same connect cards.

```
┌─ Integrations ───────────────────────────────────────────────┐
│ GitHub        ● connected · GitHub App · 6 repos    2m ago    │
│   scopes: Contents·PRs·Issues·Checks·Actions·Deploys (read)   │
│   [Manage repos]  [Re-sync]  [Disconnect]                     │
│                                                               │
│ Jira          ◑ partial — last sync hit a rate limit          │
│   [Retry]  [Details]                          waiting…        │
│                                                               │
│ Google Calendar   ● connected (read)               5m ago     │
│ Microsoft Calendar ○ not connected   [Connect ▸]              │
│                                                               │
│ ‹grotesque› DevLoom reads these to build your work view. It   │
│ writes nothing back. You choose which repos it can see.       │
└───────────────────────────────────────────────────────────────┘
```

- Each provider states its **scopes in plain language** and that DevLoom is **read-only** (SPEC §13). The GitHub **repo picker** is prominent — per-repo consent is the privacy story.
- Connection states: `not connected → connecting → connected / partial / error (reauth)`, each with a clear next action and a mono "last sync" timestamp.
- Onboarding (S0) walks these as steps with a progress warp, ending at "choose a model" (S9) and "mark local-only repos" (S10).

---

## 14. Model-provider settings

```
┌─ Model providers ─────────────────────────────────────────────┐
│ LOCAL · Ollama                       ⌂ nothing leaves          │
│   default:  [ Qwen3-Coder-30B-A3B ▾ ]        ● loaded          │
│   available: gpt-oss-20b · Gemma 3 12B · Mistral 3.2 · +pull   │
│   per-task:  analysis→Qwen3-Coder  summaries→Gemma  chat→…     │
│                                                                │
│ ANTHROPIC (optional · your key)      ◉ leaves for Anthropic    │
│   key: ••••••••  [Test]  ✓ valid           model: claude-…▾    │
│   monthly cap: [ $20 ]   used: ▓▓▁▁▁ $6.40                     │
│                                                                │
│ OPENAI (optional · your key)         ◉ leaves for OpenAI       │
│   [+ Add key]                                                  │
│                                                                │
│ FALLBACK  ○ off  ‹grotesque› Off by default. If on, choose     │
│   exactly when to fall back — never silent.        [Configure] │
└────────────────────────────────────────────────────────────────┘
```

- **Local is presented first and framed as free + private**; BYO providers are clearly optional and each carries the `◉ leaves for {provider}` boundary token.
- **Test connection** gives immediate mono feedback (`✓ valid` / error). **Per-task model map** is a simple table (analysis / summaries / chat / handoff → model).
- **Cost meter** only appears for paid providers; approaching the cap warns, hitting it hard-stops with a clear message (SPEC §20). Local has no meter.
- **Fallback is off by default** and, if enabled, forces the user to specify the trigger — the UI encodes "explicit-only, never silent."

---

## 15. Privacy & data-boundary controls

The privacy posture rendered as UI. Two surfaces: the **always-on rail indicator** (§4) and this settings screen + the **pre-action egress preview**.

```
┌─ Privacy & data boundary ─────────────────────────────────────┐
│ DEFAULT BOUNDARY   ⌂ Local-first — nothing leaves unless you   │
│                    add a key and approve it.                   │
│                                                                │
│ LOCAL-ONLY REPOS  (never sent to any remote model)             │
│   ● acme/secret-svc     ● acme/payments                        │
│   [+ Mark a repo local-only]                                   │
│                                                                │
│ EGRESS LOG  (what left, when, to whom)                  MONO   │
│   09:02  build#1893 summary → Anthropic   ~2.1k tok   [view]   │
│   Mon    (nothing left)                                        │
└────────────────────────────────────────────────────────────────┘
```

**Pre-action egress preview** — shown before *any* action that would leave the machine (SPEC FR-29). A calm modal, warp-accented:

```
┌─ This will leave your machine ──────────── ◉ ┐
│ Action: Analyze build #1893                   │
│ To:     Anthropic (your key)                  │
│ Sends:  ‹mono› summary + redacted log excerpt │
│         (~2.1k tokens)  · secrets stripped ✓  │
│ Not sent: source code, other repos            │
│ [Keep local instead]        [Send ▸]          │
└───────────────────────────────────────────────┘
```

- The preview **enumerates exactly what leaves**, confirms redaction, and offers **"Keep local instead"** as an equal-weight choice. A local-only repo can never reach this modal — its actions route local or are refused with an explanation.
- The **egress log** is a plain, mono, auditable record — trust through visibility.

---

## 16. State design

Every screen specifies loading / empty / error / stale / partial-sync. Principles: **states are direction, not mood** — say what happened and the next action, in the interface's voice.

- **Loading.** Skeletons that match final layout (warp + card outlines), not spinners. Streamed AI content shows a quiet mono ellipsis in the target region, never a full-screen block. Text: none needed; structure implies it.
- **Empty.**
  - First-run Today: a large **mono instrument-readout zero** and one action — *"Nothing tracked yet. Connect GitHub to begin."* → S0.
  - Empty filter: *"No open PRs waiting on you. "* (a good-news empty, stated plainly).
  - Empty brainstorm: *"Start from a blank thread. Attach a PR, ticket, or build to give it context."*
- **Error.** Named cause + fix, no apology. *"Jira sync failed: rate limit. Retrying in 3m."* `[Retry now]`. AI error: *"The model didn't return a usable answer. Try again, or switch model."* Never a raw stack trace to the user.
- **Stale.** Any data past its freshness window wears a mono `stale · 14m` badge and dims one step; the priority engine already down-weights stale signals (SPEC §22), and the card says *"based on data from 14m ago."* Trust = never pretending freshness.
- **Partial-sync.** A calm rail state (◑) + a focus-top strip: *"Showing GitHub + Google. Jira is still catching up — some items may be missing."* The product degrades one panel, never the whole screen (SPEC NFR-3).
- **Over cost-cap.** *"Monthly cap for Anthropic reached ($20). Switch to a local model or raise the cap."* with both actions.

---

## 17. Accessibility requirements

Target **WCAG 2.2 AA**. This is a keyboard-driven developer tool — accessibility and the audience's ergonomics coincide.

- **Keyboard-first.** Everything reachable and operable by keyboard; `⌘K` command palette; logical tab order; the warp Next list is an arrow-key list; the evidence drawer traps focus and returns it on close. Documented shortcuts.
- **Visible focus.** 2px warp focus ring + offset on every interactive element (token in §2.3); never removed.
- **Color is never the only signal.** Status = color **+ icon + text** (the desaturated palette makes this mandatory, not optional). The mono/grotesque epistemic split is reinforced with the `reasoning°` marker and an SR-only label ("model reasoning" / "verified data") so it isn't font-only. Provenance threads are backed by focusable "supported by N sources" semantics, not hover-only.
- **Streaming & live regions.** AI output regions are `aria-live="polite"`; section completion is announced (not every token). Hypothesis vs fact and confidence are exposed to assistive tech as text, not just style.
- **Contrast.** All text meets AA (4.5:1 body, 3:1 large); warp on dark and the darkened warp `#A9761E` on paper are validated for controls/large text; status hues chosen to pass with their labels.
- **Motion.** `prefers-reduced-motion` disables thread-draw and all transitions; content still appears, just without animation.
- **Log & code viewers.** Selectable, screen-reader-navigable; truncation and redaction markers are real text, not images.
- **Targets & zoom.** ≥24px (ideally ≥44px on touch) hit targets; layout reflows to 400% zoom without loss.
- **Forms.** Override reason, keys, filters — all labeled, with errors tied to fields and announced.

---

## 18. Responsive behavior

Three-zone → graceful collapse. Command palette is the constant fast path at every size.

- **Desktop ≥1200px.** Rail + focus + inspector as drawn. Inspector opens beside the focus column.
- **Laptop 1024–1199px.** Rail full; inspector becomes an **overlay drawer** from the right (doesn't squeeze the focus column).
- **Tablet 768–1023px.** Rail **collapses to an icon strip** (labels on hover/focus + in the palette); the BOUNDARY + SYNC truth moves into a top status bar so it's never lost. Inspector = overlay sheet.
- **Mobile <768px.** Bottom tab bar (Today · Work · Builds · Brainstorm · More); focus column full-width; the warp Next list stacks as full-width cards; inspector/analysis/handoff open as **full-screen sheets**; source tray becomes a bottom sheet. The pre-action **egress preview and safety block stay fully visible** — privacy and safety never get truncated on small screens.
- Content-out breakpoints: if a card's mono chips wrap past two lines, they collapse to `+N` with a tap to expand.

---

## 19. Component inventory

Grouped; each is a low-fi contract for hi-fi + build.

**Shell & navigation**
- `AppRail` (nav + sync health + model + boundary + settings + palette entry)
- `CommandPalette` (`⌘K` — jump / run actions)
- `SyncHealthDot` (● ◐ ◑ ○ + tooltip)
- `BoundaryIndicator` (⌂ / ◉ / ◐ — the data-boundary token, §2.4)
- `Inspector` / `Sheet` (right drawer desktop → overlay → full-screen sheet)

**Evidence system (the core)**
- `RecommendationCard` (compact + expanded)
- `WarpList` (ranked list with the warp spine + rank numbering)
- `SignalBreakdown` (mono value + bar + weight + tooltip)
- `SourceChip` (mono id + title + boundary badge + open)
- `ProvenanceThread` (hairline link; hover/focus highlight; keyboard semantics)
- `ReasoningBlock` (grotesque + `reasoning°` marker + confidence)
- `HypothesisCard` (evidence-vs-hypothesis, ranked, with confidence)
- `OverrideForm` (adjust + required reason)

**Build-failure & handoff**
- `LogViewer` (mono, truncation markers, redaction badges, full-log link)
- `FailureAnalysis` (the 10-section streamed layout)
- `HandoffEditor` (mono editable artifact)
- `SafetyBlock` (boxed locked-but-editable constraints)
- `CopyExportBar`

**Brainstorm**
- `SourceTray` (in-context list + add/remove + per-source boundary)
- `SourcePicker` (attach tickets/PRs/builds/repos/events)
- `ModelSwitcher` (inline; boundary-aware; blocks local-only egress)
- `SaveAsMenu` (note / task / decision / draft spec)
- `MessageStream` (SSE, aria-live, quiet thinking indicator)

**Settings**
- `IntegrationCard` (state + plain-language scopes + actions)
- `RepoPicker` (per-repo consent)
- `ProviderCard` (local / BYO; test; boundary)
- `PerTaskModelMap`
- `CostMeter` (paid providers only)
- `FallbackConfig` (off by default; explicit trigger)
- `LocalOnlyRepoList`
- `EgressLog` (auditable, mono)
- `EgressPreviewModal` (pre-action, enumerates what leaves)
- `DangerZone` (disconnect / delete with typed confirm)

**Universal states**
- `Skeleton`, `EmptyState`, `ErrorState`, `StaleBadge`, `PartialSyncStrip`, `Toast`, `ConfirmDestructive`

---

## 20. Open UI questions & Phase 2 closeout

### The design in one paragraph
A calm, dark-first (with cool-paper light) command center on a cool indigo-ink base with a single muted-brass **warp** accent. Typography splits by epistemic status — **mono for verified evidence, grotesque for reasoning** — so fact and interpretation are distinguishable at a glance. The signature is the **loom**: a warp spine ranks the "Next" list, and **provenance threads** tie every claim to its sources. Three zones (rail · focus · inspector) collapse responsively; the **data boundary** is a first-class, always-visible token, and the **pre-action egress preview** makes "what leaves my machine" unmissable. Status color is deliberately desaturated — the product's job is to be quiet and point at the one thing that matters.

### Open UI questions (none block a hi-fi pass)
1. **Default theme** — dark-first is my recommendation for an all-day dev tool; confirm, or want light "paper" as default?
2. **Font licensing** — Hanken Grotesk + Commit Mono are open/free; if you have a house/brand face, name it and I'll swap the roles.
3. **The epistemic type split** — it's the boldest choice. Confirm you want to commit to it (I recommend yes); the fallback is mono-for-data-in-evidence-contexts-only.
4. **Handoffs in top nav vs. tucked under Work** — I placed it in nav as a secondary item; happy to demote if you rarely revisit past handoffs.

### What's intentionally deferred to the (future) hi-fi stage
Exact pixel spacing, final hex tuning + full contrast audit, real font files, iconography set, empty-state illustration style, and motion timing curves — all belong to high-fidelity, which per the brief comes **after** this structural spec is approved.

### Request for approval
This is the low-fidelity UI/UX spec. **I'm stopping here and will not write production frontend code until you approve it.** Tell me to proceed (optionally answering the four questions above) and the next step is high-fidelity design → then, only after that's approved, implementation. Or send revisions and I'll fold them in first.
