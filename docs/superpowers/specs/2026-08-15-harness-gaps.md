# Harness gaps: structured output, seed, context budget, eval trend

**Date:** 2026-08-15
**Status:** agreed (user: "let's fix this")

## Where this list comes from

The verify loop (write → run → read failure → fix) closed the big harness gap. These are the
remaining ones, named in session and confirmed against the code:

1. **No structured output.** `AnswerJudge` parses prose with a regex (`WHY:/DID_TASK:/GROUNDED:`
   lines). Ollama supports schema-constrained decoding; LangChain4j 1.12.1 exposes it
   (`responseFormat(ResponseFormat)` on the Ollama builders — verified via `javap` against the
   jar in the m2 volume). A malformed reply currently means a silently dropped verdict.
2. **No seed.** Even at temperature 0.1, reproducibility is a property of the sampler that we
   never pin. The Ollama builder has `seed(Integer)` (verified).
3. **No token budgeting.** All clipping is by character count; nothing knows the context window.
   Worse: Ollama runs models at its *default* `num_ctx` (4096) unless told otherwise, and when a
   multi-step tool conversation exceeds it, Ollama silently truncates **from the front — the
   system prompt is the first thing lost**. The builder has `numCtx(Integer)` (verified).
4. **Eval baselines have no memory.** `--save x.json` files are hand-made and hand-compared;
   nothing records pass rates over time or flags a regression.
5. **`repo_search` is literal grep.** No index, no embeddings.

## Scope decisions

- Items 1–4 are one plan: they are small, live in the same subsystem (`ai/` + `eval/`), and every
  one of them is verifiable with the existing eval battery.
- **Item 5 is excluded** and gets its own brainstorm + plan later. An embeddings index is a
  subsystem (model choice, storage, invalidation on sync, worktree awareness), not a task — and at
  current repo scale, grep isn't what's failing evals.
- **Seed defaults OFF.** Determinism is an instrument, not a default: users re-run a failed
  analysis partly to harvest variance, and a pinned seed would make every re-run fail identically.
  Grounded features only — a seeded brainstorm would produce the same three ideas every time,
  which is the exact failure the creative band exists to avoid.
- **`num_ctx` defaults to 8192, capped by the model's true context length** (from `/api/show`).
  Doubling Ollama's 4096 fixes the observed truncation class; going higher by default risks
  VRAM on shared cards (KV cache grows linearly with the window). Configurable in
  Settings › Models › Advanced.
- **Structured output is Ollama-only for now.** The judge runs on local models; remote adapters
  ignore the schema and the prose-regex parse stays as the fallback for them (and for any local
  failure). The judge must never break a run — a failed judgement is no judgement.
- **The judge's reason-first design survives:** schema property order puts `why` before
  `didTask`, preserving "decide what happened, then label it" under constrained decoding.

## Success criteria

- `eval/judge.mjs`: agreement ≥ 13/14, **zero** null verdicts, **zero** "called bad work good".
- Two identical grounded runs with a seed set produce byte-identical answers.
- Backend log shows the `num_ctx` actually sent per call; an analysis run over a deliberately
  huge file completes with visible clip telemetry instead of silent front-truncation.
- Every `eval/run.mjs` battery appends one line to `eval/history.jsonl`; `eval/trend.mjs` renders
  the history and flags a crafted regression in a fixture file.
- Full battery re-run compared against `eval/hard-baseline.json`: no task regresses.
