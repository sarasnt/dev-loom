# Harness: Structured Output, Seed, Context Budget, Eval Trend — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four remaining harness gaps: schema-constrained judge verdicts, an optional sampling seed for grounded work, a context window that is actually set and budgeted, and an eval history with regression flagging.

**Architecture:** All changes live in the existing harness seams — `LlmRequest` gains an optional schema the Ollama adapter honors, `Sampling` gains a seed band, `ToolLoop` budgets accumulated messages against the same window `OllamaLlm` now sends as `num_ctx`, and `eval/run.mjs` appends each battery to a history file a new `trend.mjs` reads.

**Tech Stack:** Java 25 / Spring Boot 4.1 (backend, containerized builds), LangChain4j 1.12.1 (`seed`, `numCtx`, `responseFormat` — all verified present on the Ollama builders via `javap`), Vue 3 + TypeScript (settings UI), plain Node ≥ 20.11 (`eval/`).

**Spec:** `docs/superpowers/specs/2026-08-15-harness-gaps.md`

## Recommended executor per task

| Task | Executor | Why |
| --- | --- | --- |
| 1 — eval history + trend | **claude-haiku** | Two JS files, exact code below, no cross-file judgment |
| 2 — sampling seed | **claude-sonnet** | Multi-file wiring (config → Sampling → adapter → API → UI), live verification |
| 3 — num_ctx + message budget | **claude-sonnet** | The riskiest logic in the plan; verification reads live logs |
| 4 — schema on the port | **claude-sonnet** | Small but architectural (port record + adapter fork around the tool loop) |
| 5 — judge on the schema | **claude-sonnet** | Exact code below; the subtle parts (fallback, reason-first) are already decided |
| 6 — re-baseline + docs | **orchestrator (opus)** | Interpreting eval numbers is a judgment call, not an edit |

The orchestrator reviews after every task. If a verification step does not produce the stated
expected output, **stop and report — do not improvise a fix.**

## Global Constraints

- **No local JDK/Maven.** Compile exactly with:
  `MSYS_NO_PATHCONV=1 docker run --rm -v "/c/Users/saras/Documents/projects/dev-loom/backend:/w" -v devloom_m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-25 mvn -q -o compile`
  Grep its output for `ERROR] /w` — an empty grep is a pass. **Do not trust `&&`-chained echoes.**
- **Code changes are invisible until rebuilt:** `docker compose up -d --build backend` (+ `frontend` when `frontend/` changed), then poll `http://localhost/api/v1/settings` until 200.
- **No unit-test harness.** Verification = containerized compile, `cd frontend && npx vue-tsc --noEmit`, live `curl` against `http://localhost/api/v1`, and the scripts in `eval/`.
- **`eval/` needs the stack up**: backend rebuilt AND the host agent running (`node agent/devloom-agent.mjs`). Eval scripts default to `http://localhost/api/v1`.
- **Jackson is `com.fasterxml.jackson`** (Jackson 2). Never `tools.jackson`.
- **Flyway: next migration is V24** — this plan needs **no migration** (config keys only).
- **Frontend API facade triple:** any new endpoint call goes in `api/http.ts` AND `api/stub.ts`, re-exported from `api/index.ts`. Views import from `../api` only.
- **Files are CRLF.** Use the Edit/Write tools for all changes; sed/python string patching has failed repeatedly on this repo.
- **Never POST test credentials or dummy keys** to the running instance.
- **Style:** comments explain *why*; match the surrounding code.
- Eval model for verification is `qwen2.5-coder:7b` (fastest installed). Reference baseline: `eval/hard-baseline.json` (24/30 correct, judge agreement 13/14).
- Execute tasks **in order**. Tasks 2, 3 and 4 all edit `OllamaLlm.generate` — they must not run in parallel.

---

### Task 1: Eval history + trend (executor: claude-haiku)

Every battery run appends one summary line to `eval/history.jsonl`; a new `eval/trend.mjs` renders
the history per model/task and flags regressions. Baselines stop being hand-saved files someone
has to remember to compare.

**Files:**
- Modify: `eval/run.mjs` (imports line + one block after the report, before the `--save` block)
- Create: `eval/trend.mjs`

**Interfaces:**
- Produces: `eval/history.jsonl` — one JSON object per line:
  `{ at: string(ISO), reps: number, tasks: string[], models: { [model]: { pass, n, retried, rescued, tasks: { [taskId]: "p/n" } } } }`
- Consumes: the in-memory `results`/`tasks`/`args` structures that already exist in `run.mjs`.

- [ ] **Step 1: Add the history append to `run.mjs`**

Change the fs import at the top of `eval/run.mjs`:

```js
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs'
```

Insert this block immediately **before** the `if (args.save) {` block at the bottom:

```js
// Every battery leaves a row behind, so pass rates have a history instead of a memory. This is
// what lets trend.mjs say "this task regressed" — a hand-saved baseline only answers questions
// someone remembered to ask.
const historyRow = {
  at: new Date().toISOString(),
  reps: args.reps,
  tasks: tasks.map((t) => t.id),
  models: Object.fromEntries(args.models.map((m) => {
    const rows = Object.entries(results[m])
    return [m, {
      pass: rows.reduce((n, [, r]) => n + r.pass, 0),
      n: rows.reduce((n, [, r]) => n + r.n, 0),
      retried: rows.reduce((n, [, r]) => n + r.retried, 0),
      rescued: rows.reduce((n, [, r]) => n + r.rescued, 0),
      tasks: Object.fromEntries(rows.map(([id, r]) => [id, `${r.pass}/${r.n}`])),
    }]
  })),
}
appendFileSync(join(import.meta.dirname, 'history.jsonl'), JSON.stringify(historyRow) + '\n')
console.log('history  → eval/history.jsonl')
```

- [ ] **Step 2: Create `eval/trend.mjs`** with exactly:

```js
// Pass rates over time, from the history run.mjs appends. A saved baseline answers the question
// you remembered to ask; this answers the one you forgot: "did anything get worse since last week?"
//
//   node eval/trend.mjs                 # eval/history.jsonl
//   node eval/trend.mjs --file x.jsonl  # any history file (used by the self-test)
//
// A task is flagged ▼ when its latest pass ratio dropped by a third or more against the best of
// its previous three appearances — at reps=3 that means "lost at least one rep", which separates
// a real regression from run-to-run noise.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const fileArg = process.argv.indexOf('--file')
const path = fileArg > -1 ? process.argv[fileArg + 1] : join(import.meta.dirname, 'history.jsonl')

let lines
try {
  lines = readFileSync(path, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
} catch {
  console.error(`No history at ${path} — run eval/run.mjs at least once.`)
  process.exit(1)
}

const ratio = (pn) => {
  const [p, n] = pn.split('/').map(Number)
  return n ? p / n : 0
}

// model → task → [{at, pn, r}] in time order
const byModel = new Map()
for (const row of lines) {
  for (const [model, m] of Object.entries(row.models)) {
    if (!byModel.has(model)) byModel.set(model, new Map())
    const tasks = byModel.get(model)
    for (const [id, pn] of Object.entries(m.tasks)) {
      if (!tasks.has(id)) tasks.set(id, [])
      tasks.get(id).push({ at: row.at, pn, r: ratio(pn) })
    }
  }
}

let regressions = 0
for (const [model, tasks] of byModel) {
  console.log(`\n${model}`)
  for (const [id, hist] of tasks) {
    const latest = hist[hist.length - 1]
    const prev = hist.slice(-4, -1)                       // up to 3 before the latest
    const prevBest = prev.length ? Math.max(...prev.map((h) => h.r)) : null
    const regressed = prevBest !== null && prevBest - latest.r >= 1 / 3
    if (regressed) regressions++
    const cells = hist.slice(-6).map((h) => h.pn.padStart(5)).join(' ')
    console.log(`  ${regressed ? '▼' : ' '} ${id.padEnd(11)} ${cells}`)
  }
}
console.log(regressions
  ? `\n${regressions} regression candidate(s) — latest run lost ≥1/3 vs its recent best.`
  : '\nno regressions against recent history.')
process.exit(regressions ? 2 : 0)
```

- [ ] **Step 3: Self-test the regression flag with a crafted history**

Write this scratch file (path: `C:\Users\saras\AppData\Local\Temp\claude\c--Users-saras-Documents-projects-dev-loom\c920ceab-5464-452f-961f-27b283f6f3e1\scratchpad\hist-test.jsonl` — three lines, `chain` drops from 3/3 to 0/3 in the last row):

```jsonl
{"at":"2026-08-13T10:00:00Z","reps":3,"tasks":["chain","seed"],"models":{"qwen2.5-coder:7b":{"pass":6,"n":6,"retried":0,"rescued":0,"tasks":{"chain":"3/3","seed":"3/3"}}}}
{"at":"2026-08-14T10:00:00Z","reps":3,"tasks":["chain","seed"],"models":{"qwen2.5-coder:7b":{"pass":5,"n":6,"retried":1,"rescued":1,"tasks":{"chain":"2/3","seed":"3/3"}}}}
{"at":"2026-08-15T10:00:00Z","reps":3,"tasks":["chain","seed"],"models":{"qwen2.5-coder:7b":{"pass":3,"n":6,"retried":2,"rescued":0,"tasks":{"chain":"0/3","seed":"3/3"}}}}
```

Run: `node eval/trend.mjs --file "<scratchpad>/hist-test.jsonl"`
Expected: `chain` row carries `▼`, `seed` row does not, final line says `1 regression candidate(s)`, exit code 2.

- [ ] **Step 4: Verify the append against the live stack** (backend + host agent must be up)

Run: `node eval/run.mjs --models qwen2.5-coder:7b --tasks version --reps 1`
Expected: battery output ends with `history  → eval/history.jsonl`; then
`node eval/trend.mjs` prints a `version` row with `  1/1` and `no regressions against recent history.`

- [ ] **Step 5: Commit**

```bash
git add eval/run.mjs eval/trend.mjs eval/history.jsonl
git commit -m "eval: a history every battery appends to, and a trend that flags regressions"
```

---

### Task 2: Sampling seed for grounded work (executor: claude-sonnet)

An optional fixed seed, grounded features only, default off. Off by default because reruns exist
partly to harvest variance; never for brainstorm because a seeded brainstorm defeats the creative
band's purpose.

**Files:**
- Modify: `backend/src/main/java/com/devloom/common/AppConfigService.java` (one key)
- Modify: `backend/src/main/java/com/devloom/ai/Sampling.java` (one method)
- Modify: `backend/src/main/java/com/devloom/ai/OllamaLlm.java` (both builders)
- Modify: `backend/src/main/java/com/devloom/api/SettingsController.java` (advanced map + record + PUT)
- Modify: `frontend/src/types.ts`, `frontend/src/api/stub.ts`, `frontend/src/views/ProvidersView.vue`

**Interfaces:**
- Produces: `Sampling.seed(String feature)` → `Integer` (null = leave the provider random).
- Produces: config key `AppConfigService.SAMPLING_SEED = "sampling.grounded.seed"`.
- The advanced settings payload gains a `seed` string field ("" = off).

- [ ] **Step 1: Add the config key** in `AppConfigService`, directly under `SAMPLING_CREATIVE_TEMP`:

```java
    /** Fixed sampling seed for grounded work ("" = random). An instrument, not a default. */
    public static final String SAMPLING_SEED = "sampling.grounded.seed";
```

- [ ] **Step 2: Add `seed()` to `Sampling`**, after the `topP` method:

```java
    /**
     * A fixed seed for grounded features, or null to leave the sampler random. Off by default:
     * re-running a failed analysis is partly a way to harvest variance, and a pinned seed would
     * make every re-run fail identically. Never applies to brainstorm — a seeded brainstorm
     * produces the same three ideas every time, which is the failure the creative band exists
     * to avoid.
     */
    public Integer seed(String feature) {
        if (feature == null || !grounded(feature)) return null;
        return config.get(AppConfigService.SAMPLING_SEED).map(v -> {
            try {
                int n = Integer.parseInt(v.trim());
                return n >= 0 ? n : null;   // a negative seed is a typo, not a request
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }
```

- [ ] **Step 3: Send it from `OllamaLlm`** — in the blocking builder (currently
`.temperature(sampling.temperature(request.feature(), model))` / `.topP(...)` around line 98) and
in the streaming builder (around line 129), add one line to each, directly after `.topP(...)`:

```java
                .seed(sampling.seed(request.feature()))
```

- [ ] **Step 4: Compile**

Run the Global Constraints compile command. Expected: no `ERROR] /w` lines.

- [ ] **Step 5: Expose it in the settings API.** In `SettingsController`:

In `advanced()`, after the `creativeTemperature` put:

```java
        a.put("seed", config.get(AppConfigService.SAMPLING_SEED).orElse(""));
```

Change the record to:

```java
    public record AdvancedSettings(String groundedTemperature, String groundedTopP,
                                   String creativeTemperature, String maxSteps, Boolean judgeEnabled,
                                   String seed) {}
```

In `setAdvanced(...)`, after the `creativeTemperature` line:

```java
            if (body.seed() != null) config.set(AppConfigService.SAMPLING_SEED, blankToNull(body.seed()));
```

- [ ] **Step 6: Frontend.** In `frontend/src/types.ts`, inside `AdvancedSettings`, after `maxSteps: string`:

```ts
  seed: string // fixed sampling seed for grounded work; "" = random (the default)
```

In `frontend/src/api/stub.ts`, in `ADVANCED_STUB`, add `seed: '',` after `maxSteps: ''`.

In `frontend/src/views/ProvidersView.vue`: in `blankAdvanced()`-adjacent code **no change** (that
is the per-model shape, which does not get a seed). In `saveAdvanced()`, add `seed: a.seed,` to the
object passed to `saveAdvancedSettings`. In the template, after the "Tool steps per run" row:

```html
        <div class="row">
          <span class="mono lbl">Grounded seed</span>
          <input v-model="adv.seed" class="keyin tiny mono" placeholder="random" />
          <span class="mono hint">Pins grounded sampling for reproducibility. Empty = random; never applies to Brainstorm.</span>
        </div>
```

And in `resetAdvanced()`, include `seed: ''` in the reset object.

- [ ] **Step 7: Typecheck + rebuild**

Run: `cd frontend && npx vue-tsc --noEmit` — expected exit 0.
Run: `docker compose up -d --build backend frontend`, poll `http://localhost/api/v1/settings` for 200.

- [ ] **Step 8: Verify determinism live.** The fixture repo id is discoverable via
`GET /api/v1/repos` (name `devloom-eval-fixture`). Temporarily raise grounded temperature so the
seed has something to pin, set the seed, run the same analysis twice:

```bash
curl -s -X PUT -H "Content-Type: application/json" \
  -d '{"groundedTemperature":"0.9","seed":"7"}' http://localhost/api/v1/settings/advanced
# launch the same readonly fleet run twice (prompt: "Name three risks of this codebase, one line each."),
# poll each to completion, capture resultSummary
```

Expected: the two `resultSummary` values are **byte-identical**.
Then clear: `curl -s -X PUT -H "Content-Type: application/json" -d '{"groundedTemperature":"","seed":""}' http://localhost/api/v1/settings/advanced`
and run twice more — expected: the summaries differ (if they coincide once, re-run; twice-identical without a seed at 0.9 is effectively impossible for this prompt).
Delete the four test runs afterwards (`DELETE /api/v1/fleet/runs/{id}`).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/devloom/common/AppConfigService.java backend/src/main/java/com/devloom/ai/Sampling.java backend/src/main/java/com/devloom/ai/OllamaLlm.java backend/src/main/java/com/devloom/api/SettingsController.java frontend/src/types.ts frontend/src/api/stub.ts frontend/src/views/ProvidersView.vue
git commit -m "ai: an optional grounded seed — reproducibility as an instrument, not a default"
```

---

### Task 3: Send a real context window, and budget messages against it (executor: claude-sonnet)

Ollama runs models at its default `num_ctx` (4096) unless told otherwise, and silently truncates
**from the front** when a conversation exceeds it — the system prompt is the first casualty. Send
an explicit window (min of the model's true limit and a configured cap, default 8192), and make
`ToolLoop` clip tool results so the conversation stays inside it.

**Files:**
- Modify: `backend/src/main/java/com/devloom/ai/OllamaAdminService.java` (add `contextLength`)
- Modify: `backend/src/main/java/com/devloom/common/AppConfigService.java` (one key)
- Modify: `backend/src/main/java/com/devloom/ai/ToolLoop.java` (constant, budget, clip)
- Modify: `backend/src/main/java/com/devloom/ai/OllamaLlm.java` (field + ctor param + both builders)
- Modify: `backend/src/main/java/com/devloom/api/SettingsController.java`, `frontend/src/types.ts`, `frontend/src/api/stub.ts`, `frontend/src/views/ProvidersView.vue` (one advanced row)

**Interfaces:**
- Produces: `OllamaAdminService.contextLength(String model)` → `int` (0 = unknown), cached.
- Produces: `ToolLoop.DEFAULT_NUM_CTX = 8_192` and config key `MODEL_NUM_CTX = "model.numCtx"`.
- Produces: `ToolLoop.estTokens(CharSequence)` → `int` (chars/3, deliberately high).
- `OllamaLlm` constructor gains an `OllamaAdminService admin` parameter (Spring wires it).

- [ ] **Step 1: `contextLength` in `OllamaAdminService`** — add a cache field near the other fields
and this method (match the class's existing `MAP` type reference and log style):

```java
    /** model → context length, cached — /api/show is slow and the answer never changes. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> ctxCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The model's true context length from /api/show, or 0 when unknown. Needed because Ollama
     * ignores a model's capability and serves its own default window (4096) unless each request
     * says otherwise — and when a conversation exceeds it, Ollama truncates from the FRONT, so
     * the system prompt is the first thing silently lost.
     */
    public int contextLength(String model) {
        if (model == null || model.isBlank()) return 0;
        return ctxCache.computeIfAbsent(model, m -> {
            try {
                Map<String, Object> info = http.post().uri("/api/show")
                        .header("Content-Type", "application/json")
                        .body(Map.of("model", m))
                        .retrieve().body(MAP);
                Object mi = info == null ? null : info.get("model_info");
                if (mi instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        // The key is arch-prefixed ("qwen3moe.context_length"), so match the suffix.
                        if (String.valueOf(e.getKey()).endsWith(".context_length")
                                && e.getValue() instanceof Number n) {
                            return n.intValue();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("/api/show {} failed: {}", m, e.getMessage());
            }
            return 0;
        });
    }
```

- [ ] **Step 2: Config key** in `AppConfigService`, under `SAMPLING_SEED`:

```java
    /** Context window cap sent to Ollama as num_ctx ("" = the shipped default; see ToolLoop). */
    public static final String MODEL_NUM_CTX = "model.numCtx";
```

- [ ] **Step 3: The constant, the estimator and the budget in `ToolLoop`.** Next to
`DEFAULT_MAX_STEPS`:

```java
    /**
     * The context window requested from Ollama when the model's own limit doesn't cap it lower.
     * 8192 (double Ollama's default) because the KV cache grows linearly with the window — a
     * larger default risks VRAM on shared cards, and anyone with headroom can raise it in
     * Settings › Models › Advanced.
     */
    public static final int DEFAULT_NUM_CTX = 8_192;

    /** Rough token estimate. chars/3 errs high on purpose: overflow truncates silently from the
     *  front of the conversation, over-clipping is visible in the result. */
    static int estTokens(CharSequence s) {
        return s == null ? 0 : s.length() / 3;
    }

    /** The configured window cap, or the shipped default; nonsense falls through, same as maxSteps. */
    public int numCtx() {
        return config.get(com.devloom.common.AppConfigService.MODEL_NUM_CTX).map(v -> {
            try {
                int n = Integer.parseInt(v.trim());
                return n >= 2_048 && n <= 262_144 ? n : DEFAULT_NUM_CTX;
            } catch (NumberFormatException e) {
                return DEFAULT_NUM_CTX;
            }
        }).orElse(DEFAULT_NUM_CTX);
    }
```

- [ ] **Step 4: Clip tool results against the budget.** In `ToolLoop`, add:

```java
    /**
     * Fit a tool result into what's left of the window. Without this, six steps of file reads
     * walk straight past num_ctx and Ollama silently drops the front of the conversation — the
     * system prompt first, then the task. Clipping the newest result instead keeps the model's
     * instructions intact and tells it what happened, which beats it silently forgetting who it
     * is. The floor keeps a clipped result useful; the estimate errs high (see estTokens).
     */
    private String fitBudget(List<ChatMessage> messages, String result, ToolTelemetry tel) {
        if (result == null) return null;
        int used = 0;
        for (ChatMessage m : messages) used += estTokens(m.toString());
        int left = numCtx() - 2_048 - used;               // reserve room for the answer itself
        int allowedChars = Math.max(1_500, left * 3);
        if (result.length() <= allowedChars) return result;
        tel.did("clipping a tool result to fit the context window");
        return result.substring(0, allowedChars)
                + "\n[clipped " + (result.length() - allowedChars)
                + " characters to fit the context window — ask for a narrower range if you need more]";
    }
```

Then wrap **both** `ToolExecutionResultMessage.from(...)` call sites (lines ~210 and ~270):

Line ~210, currently
`messages.add(ToolExecutionResultMessage.from(req, withBudget(o.text(), stepsLeft)));` becomes:

```java
                    messages.add(ToolExecutionResultMessage.from(req,
                            fitBudget(messages, withBudget(o.text(), stepsLeft), tel)));
```

Line ~270, currently `messages.add(ToolExecutionResultMessage.from(req, o.text()));` becomes:

```java
                messages.add(ToolExecutionResultMessage.from(req, fitBudget(messages, o.text(), tel)));
```

- [ ] **Step 5: Send `num_ctx` from `OllamaLlm`.** Add the field/ctor param (constructor currently
ends `..., ToolLoop toolLoop, Sampling sampling) {`):

```java
    private final OllamaAdminService admin;
```

constructor: `..., ToolLoop toolLoop, Sampling sampling, OllamaAdminService admin) {` plus
`this.admin = admin;`. Add a helper:

```java
    /** The window to request: the configured cap, but never above what the model actually has. */
    private int numCtx(String model) {
        int cap = toolLoop.numCtx();
        int hard = admin.contextLength(model);
        int n = hard > 0 ? Math.min(cap, hard) : cap;
        log.debug("num_ctx for {}: {} (cap {}, model limit {})", model, n, cap, hard);
        return n;
    }
```

Add `.numCtx(numCtx(model))` to **both** builders (blocking ~line 98 and streaming ~line 129),
after the `.seed(...)` line added in Task 2.

- [ ] **Step 6: Compile** (Global Constraints command). Expected: clean.

- [ ] **Step 7: The advanced settings row.** Same four files and pattern as Task 2 Steps 5–6:
- `SettingsController.advanced()`: `a.put("numCtx", config.get(AppConfigService.MODEL_NUM_CTX).orElse(""));`
  and add `"numCtx", com.devloom.ai.ToolLoop.DEFAULT_NUM_CTX` to the `defaults` map.
- `AdvancedSettings` record gains `String numCtx` (after `seed`); `setAdvanced` gains
  `if (body.numCtx() != null) config.set(AppConfigService.MODEL_NUM_CTX, blankToNull(body.numCtx()));`
- `types.ts`: `numCtx: string` in `AdvancedSettings`, `numCtx: number` in its `defaults`.
- `stub.ts`: `numCtx: ''` in `ADVANCED_STUB` and `numCtx: 8192` in its defaults.
- `ProvidersView.vue`: include `numCtx: a.numCtx` in `saveAdvanced()`, `numCtx: ''` in
  `resetAdvanced()`, and after the seed row:

```html
        <div class="row">
          <span class="mono lbl">Context window</span>
          <input v-model="adv.numCtx" class="keyin tiny mono" :placeholder="String(adv.defaults.numCtx)" />
          <span class="mono hint">Tokens sent as num_ctx (capped by the model's own limit). Larger = more VRAM.</span>
        </div>
```

- [ ] **Step 8: Typecheck, rebuild, verify against a huge file.**

`cd frontend && npx vue-tsc --noEmit` → exit 0. Rebuild backend+frontend, poll for 200.

Create a ~400KB file in the fixture working copy
(`C:\Users\saras\Documents\projects\devloom-eval-fixture\big.txt` — any generated text, e.g. a
line repeated 8000 times), `git -C <fixture> add -A && git -C <fixture> commit -m big`. Launch a
readonly fleet run on the fixture: prompt `Read big.txt and state its first line.` Poll to
completion. Expected: status `review`, a sane answer, and the run detail's activity (or backend
log) contains `clipping a tool result to fit the context window`. Backend log at debug shows
`num_ctx for qwen2.5-coder:7b: 8192`. Delete the run; re-materialize the fixture
(`node -e "import('./eval/fixture.mjs').then(m=>m.materialize('C:/Users/saras/Documents/projects/devloom-eval-fixture'))"`)
so `big.txt` doesn't pollute later evals.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/devloom/ai/OllamaAdminService.java backend/src/main/java/com/devloom/common/AppConfigService.java backend/src/main/java/com/devloom/ai/ToolLoop.java backend/src/main/java/com/devloom/ai/OllamaLlm.java backend/src/main/java/com/devloom/api/SettingsController.java frontend/src/types.ts frontend/src/api/stub.ts frontend/src/views/ProvidersView.vue
git commit -m "ai: send a real num_ctx and budget the conversation against it"
```

---

### Task 4: A schema on the port, honored by the Ollama adapter (executor: claude-sonnet)

`LlmRequest` gains an optional JSON schema. When set, the Ollama adapter constrains decoding to it
and **bypasses the tool loop** — forced-JSON output and tool-call turns are mutually exclusive, and
every schema consumer (today: the judge) is a single-shot classification, not an agentic turn.
Remote adapters and the stub ignore the field; callers keep a prose fallback.

**Files:**
- Modify: `backend/src/main/java/com/devloom/ai/LlmPort.java` (record + convenience ctors)
- Modify: `backend/src/main/java/com/devloom/ai/OllamaLlm.java` (blocking path fork)

**Interfaces:**
- Produces: `LlmRequest.schema()` → `dev.langchain4j.model.chat.request.json.JsonSchema` (nullable)
  and `LlmRequest.withSchema(JsonSchema)`.
- All existing 4/5/6-arg constructions keep compiling unchanged.

- [ ] **Step 1: Extend the record.** Replace the `LlmRequest` record in `LlmPort.java` with:

```java
    /**
     * @param repoPath the repository this request is about, or null. Present, it turns on the
     *                 built-in repo tools ({@link RepoTools}) for the turn — which is what lets a
     *                 model actually read the code it is being asked about.
     * @param schema   constrain the reply to this JSON shape, or null for prose. Adapters that
     *                 can't constrain simply ignore it, so every caller keeps a prose fallback —
     *                 the schema removes a failure mode, it must never add one.
     */
    record LlmRequest(String feature, String system, String prompt, String model, String repoPath,
                      boolean repoWritable, dev.langchain4j.model.chat.request.json.JsonSchema schema) {

        /** For features with no repository in play (brainstorming a topic, a build log). */
        public LlmRequest(String feature, String system, String prompt, String model) {
            this(feature, system, prompt, model, null, false, null);
        }

        /** Repo-scoped and read-only — the default for anything that only needs to understand code. */
        public LlmRequest(String feature, String system, String prompt, String model, String repoPath) {
            this(feature, system, prompt, model, repoPath, false, null);
        }

        /** The pre-schema shape, kept so existing call sites don't churn. */
        public LlmRequest(String feature, String system, String prompt, String model, String repoPath,
                          boolean repoWritable) {
            this(feature, system, prompt, model, repoPath, repoWritable, null);
        }

        /** The same request, with its reply constrained to {@code s}. */
        public LlmRequest withSchema(dev.langchain4j.model.chat.request.json.JsonSchema s) {
            return new LlmRequest(feature, system, prompt, model, repoPath, repoWritable, s);
        }
    }
```

- [ ] **Step 2: Honor it in `OllamaLlm.generate` (blocking path only).** Add imports:

```java
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
```

In the blocking `generate(LlmRequest, StreamSink)` path, after the existing
`OllamaChatModel chat = OllamaChatModel.builder()....build();` block, restructure so the builder
is conditional — replace the block with:

```java
        var builder = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of(monitor))
                // Sampling by feature: grounded work near-greedy, brainstorming warm. Left unset
                // this ran at the provider default, which made repeat runs disagree with themselves.
                .temperature(sampling.temperature(request.feature(), model))
                .topP(sampling.topP(request.feature(), model))
                .seed(sampling.seed(request.feature()))
                .numCtx(numCtx(model));
        if (request.schema() != null) {
            builder.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(request.schema())
                    .build());
        }
        OllamaChatModel chat = builder.build();
```

Then, immediately before the `ToolLoop.Reply reply = toolLoop.run(...)` line, add the fork:

```java
        // A schema'd request skips the tool loop entirely: constrained JSON output and tool-call
        // turns are mutually exclusive (the grammar would strangle a tool call mid-JSON), and
        // every schema consumer is a single-shot classification, not an agentic turn.
        if (request.schema() != null) {
            List<ChatMessage> plain = new ArrayList<>(messages);
            String json = chat.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(plain).build()).aiMessage().text();
            log.info("Ollama generate (schema): model={} chars={}", model, json == null ? 0 : json.length());
            return new LlmResult(json == null ? "" : json, model, provider(), true, null);
        }
```

- [ ] **Step 3: Compile** (Global Constraints command). Expected: clean. This proves every
existing `LlmRequest` construction site still compiles against the widened record.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/devloom/ai/LlmPort.java backend/src/main/java/com/devloom/ai/OllamaLlm.java
git commit -m "ai: requests can carry a JSON schema; Ollama constrains decoding to it"
```

---

### Task 5: The judge rules in JSON (executor: claude-sonnet)

`AnswerJudge` sends its verdict schema with the request and parses JSON first; the regex parse
stays as the fallback for remote models and for anything malformed. Property order keeps `why`
first — the reason-first design ("decide what happened, then label it") survives constrained
decoding because generation follows schema order.

**Files:**
- Modify: `backend/src/main/java/com/devloom/ai/AnswerJudge.java`

**Interfaces:**
- Consumes: `LlmRequest.withSchema(JsonSchema)` from Task 4.
- The `Verdict` record and `judge(...)` signature are **unchanged** — callers don't move.

- [ ] **Step 1: Build the schema.** Add imports:

```java
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
```

Add fields under `PROMPT`:

```java
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The verdict, as a shape instead of a hoped-for format. Property order is the point: "why"
     * comes first so the model still decides what happened before labelling it — under
     * constrained decoding, generation follows schema order, so this preserves the reason-first
     * rubric that fixed the self-contradicting verdicts.
     */
    private static final JsonSchema VERDICT_SCHEMA = JsonSchema.builder()
            .name("verdict")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("why", dev.langchain4j.model.chat.request.json.JsonStringSchema.builder()
                            .description("one sentence, at most 20 words").build())
                    .addProperty("didTask", JsonEnumSchema.builder()
                            .enumValues("yes", "partly", "no").build())
                    .addProperty("grounded", JsonBooleanSchema.builder().build())
                    .required("why", "didTask", "grounded")
                    .build())
            .build();
```

- [ ] **Step 2: Update the shipped rubric.** In `SYSTEM`, replace only this block:

```
            Reply with exactly three lines and nothing else, in this order:

            WHY: one sentence, at most 20 words
            DID_TASK: yes | partly | no
            GROUNDED: yes | no
```

with:

```
            Reply as JSON with exactly these fields, in this order:

            "why": one sentence, at most 20 words
            "didTask": "yes" | "partly" | "no"
            "grounded": true | false
```

(The following "The reason comes first on purpose..." paragraph stays — it is still true.)
Note: if a `devloom/answer-judge` prompt already exists in Langfuse, `seed()` will not overwrite
it; constrained decoding produces JSON regardless of the prose there, but the orchestrator should
update or delete the Langfuse copy so the wording matches. Say so in the task report.

- [ ] **Step 3: Send the schema and parse JSON first.** In `judge(...)`, change the generate call to:

```java
            LlmPort.LlmResult r = llm.generate(
                    new LlmPort.LlmRequest("judge", prompts.get(PROMPT, SYSTEM), prompt, model)
                            .withSchema(VERDICT_SCHEMA));
```

Replace the block from `Double adherence = switch (field(text, "DID_TASK")) {` through
`return new Verdict(...);` with:

```java
            Verdict v = parseJson(text);
            if (v == null) v = parseProse(text);   // remote adapters ignore the schema; keep them working
            if (v == null) {
                log.debug("Judge produced no usable verdict: {}", clip(text, 160));
                return null;
            }
            return v;
```

Add the two parsers after `judge(...)`:

```java
    /** The schema-constrained path: shape guaranteed by decoding, so failure here means no JSON at all. */
    private static Verdict parseJson(String text) {
        try {
            JsonNode n = JSON.readTree(text.trim());
            Double adherence = switch (n.path("didTask").asText("")) {
                case "yes" -> 1.0;
                case "partly" -> 0.5;
                case "no" -> 0.0;
                default -> null;
            };
            if (adherence == null) return null;
            return new Verdict(adherence, n.path("grounded").asBoolean(true),
                    clip(n.path("why").asText(""), 160));
        } catch (Exception e) {
            return null;
        }
    }

    /** The prose fallback — the pre-schema format, still what remote models produce. */
    private static Verdict parseProse(String text) {
        Double adherence = switch (field(text, "DID_TASK")) {
            case "yes" -> 1.0;
            case "partly" -> 0.5;
            case "no" -> 0.0;
            default -> null;
        };
        if (adherence == null) return null;
        boolean grounded = !"no".equals(field(text, "GROUNDED"));
        return new Verdict(adherence, grounded, clip(field(text, "WHY"), 160));
    }
```

(`field()` and `clip()` already exist — do not duplicate them.)

- [ ] **Step 4: Compile, rebuild.** Global Constraints compile → clean; rebuild backend, poll for 200.

- [ ] **Step 5: Verify against ground truth.** With the host agent running:

Run: `node eval/judge.mjs --models qwen2.5-coder:7b --reps 2`
Expected — all three must hold, or stop and report:
- agreement **≥ 13/14**
- `called bad work good: 0`
- zero `judge=—` rows (that symbol marks a null verdict, the parse-failure class this task deletes)

Then one live fleet run on the fixture (readonly, prompt `What version is declared in package.json?`):
expected `adherenceNote` reads as a sentence (the `why` field), not raw JSON.
Delete the test run.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/devloom/ai/AnswerJudge.java
git commit -m "ai: the judge rules in schema-constrained JSON, prose regex demoted to fallback"
```

---

### Task 6: Re-baseline, trend, and honest docs (executor: orchestrator/opus)

**Files:**
- Modify: `README.md` (harness section + known limits)
- Create: `eval/post-structured.json` (via `--save`)

- [ ] **Step 1: Full battery against the standing baseline**

Run: `node eval/run.mjs --models qwen2.5-coder:7b --reps 3 --save eval/post-structured.json --compare eval/hard-baseline.json`
Expected: no `▼` on any task in the compare block; retries report as before; the run appends to
`eval/history.jsonl`.

- [ ] **Step 2: Judge validation at full width**

Run: `node eval/judge.mjs --models qwen2.5-coder:7b --reps 2`
Expected: same gates as Task 5 Step 5. Record the agreement number.

- [ ] **Step 3: Trend renders the accumulated history**

Run: `node eval/trend.mjs`
Expected: rows for every task that ran in Tasks 1 and 6, no regression flags, exit 0.

- [ ] **Step 4: Update the README.** In the harness bullet list: the Scoring bullet gains
"verdicts are schema-constrained on local models (prose fallback elsewhere)"; the Sampling bullet
gains the seed and window knobs. In **Known limits**: delete the line implying the judge parses
prose only if present; keep honest limits (remote streaming, index) intact. Add `eval/trend.mjs`
to the "Evaluating it" command list:

```bash
node eval/trend.mjs                                       # pass rates over time; flags regressions
```

- [ ] **Step 5: Commit, and update the session memory/backlog** (orchestrator): mark gaps 1–4
closed in the backlog memory, leaving the retrieval index as the named survivor.

```bash
git add README.md eval/post-structured.json eval/history.jsonl
git commit -m "docs+eval: re-baseline after structured verdicts, seed, num_ctx and trend"
```
