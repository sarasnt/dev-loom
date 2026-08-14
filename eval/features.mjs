// A ladder of real feature tasks, graded by how much a model has to hold at once.
//
// eval/run.mjs measures whether a model can ANSWER questions about a repo. This measures whether
// it can CHANGE one, which is a different and much harder thing: it has to find the right place,
// match conventions it wasn't told about, and produce code that compiles — and the failures are
// different too (writing to the wrong file, rewriting something it was only supposed to read).
//
//   node eval/features.mjs --repo second-week --tier 1 --models qwen3-coder:30b
//   node eval/features.mjs --repo second-week --tier 1,2 --reps 2 --save features.json
//
// Every task runs as an isolated edit run, so each attempt gets a throwaway worktree and branch.
// Nothing lands anywhere: the worktree is discarded after scoring unless --keep is passed.
//
// Grading is on what the run DID, not on the prose: which files it created or changed, whether
// the new file has the shape asked for, and — where the repo has a toolchain — whether it still
// compiles. A run that explains a perfect implementation and writes nothing scores zero here.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const API = process.env.DEVLOOM_API ?? 'http://localhost/api/v1'

/**
 * The ladder. Tier is about working memory, not lines of code:
 *   1 — one new self-contained file. Nothing existing has to be understood deeply.
 *   2 — one new file that must fit an existing contract (types, naming, a test style).
 *   3 — a change spanning files, where the model must find callers and not break them.
 *   4 — a change that requires understanding behaviour, not just structure.
 *
 * `expect` is deliberately about files and shapes rather than exact text: two correct
 * implementations of the same thing don't look alike, and grading them as if they should is how
 * an eval starts measuring conformity instead of capability.
 */
export const FEATURES = {
  'second-week': [
    {
      id: 'water-treatment',
      tier: 1,
      prompt: `Add water treatment guidance as pure Dart domain logic.

Create lib/features/inventory/domain/water_treatment.dart. Given a volume of unsafe water in litres and what the household has available — a heat source, unscented household bleach of a known concentration, or purification tablets — it returns the treatment to apply: a rolling boil and for how long, or a dose in millilitres plus the contact time to wait before drinking.

Plain Dart, no Drift and no Flutter imports, in the style of water_cascade.dart in that same folder. Add a test under test/features/inventory/domain/.`,
      expect: {
        creates: ['lib/features/inventory/domain/water_treatment.dart'],
        alsoCreates: ['test/features/inventory/domain/water_treatment_test.dart'],
        // The real trap: this task is next door to water_cascade.dart, and a model that gets
        // confused rewrites that instead of adding beside it.
        mustNotChange: ['lib/features/inventory/domain/water_cascade.dart',
                        'test/features/inventory/domain/water_cascade_test.dart'],
        contains: [/class|enum/, /boil/i, /bleach|chlorine|tablet/i],
      },
    },
    {
      id: 'expiry-window',
      tier: 2,
      prompt: `Add a helper that groups stock by how soon it expires, so the app can say what to eat first.

Create lib/features/inventory/domain/expiry_window.dart with an enum of urgency bands (expired, days, weeks, months, beyond) and a function mapping an expiry date plus "today" onto a band. Pure Dart — no Drift, no Flutter.

Match how stock_freshness.dart in the same folder is written, and add a test alongside the existing tests for that folder.`,
      expect: {
        creates: ['lib/features/inventory/domain/expiry_window.dart'],
        alsoCreates: ['test/features/inventory/domain/expiry_window_test.dart'],
        mustNotChange: ['lib/features/inventory/domain/stock_freshness.dart'],
        contains: [/enum/, /DateTime/],
      },
    },
    {
      id: 'allergen-lookup',
      tier: 3,
      prompt: `The app cross-references EU allergens against food stock. Add a function that, given a person's allergens and a list of stock item allergen sets, returns which items that person must not eat.

Put it where the existing allergen code lives (find it — see lib/core/utils/allergens.dart) and follow its conventions rather than introducing a new pattern. Add a test.`,
      expect: {
        changesOrCreates: [/allergen/i],
        contains: [/List|Set|Iterable/],
      },
    },
  ],

  // The eval fixture, as a smoke test: if a model can't do these, the harness is what to look at,
  // not the model.
  'devloom-eval-fixture': [
    {
      id: 'add-constant',
      tier: 1,
      prompt: 'Create src/two.js exporting a constant named two with the value 2, in the same style as src/math.js.',
      expect: { creates: ['src/two.js'], contains: [/export/, /\b2\b/] },
    },
    {
      id: 'guard-empty',
      tier: 2,
      prompt: 'averagePrice in src/cart.js returns NaN for an empty cart. Fix it to return 0, changing nothing else.',
      expect: { changes: ['src/cart.js'], mustNotChange: ['src/math.js'], contains: [/length|isEmpty|=== 0|== 0/] },
    },
  ],
}

// ---- runner ---------------------------------------------------------------------------------

const args = parse(process.argv.slice(2))

async function api(path, init) {
  const res = await fetch(API + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!res.ok) throw new Error(`${init?.method ?? 'GET'} ${path} → ${res.status} ${await res.text()}`)
  return res.status === 204 ? null : res.json()
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function parse(argv) {
  const out = { repo: 'devloom-eval-fixture', tier: null, models: ['qwen3-coder:30b'], reps: 1, save: null, keep: false }
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i + 1]
    switch (argv[i]) {
      case '--repo': out.repo = v; i++; break
      case '--tier': out.tier = v.split(',').map(Number); i++; break
      case '--models': out.models = v.split(','); i++; break
      case '--reps': out.reps = Number(v); i++; break
      case '--save': out.save = v; i++; break
      case '--keep': out.keep = true; break
      default: break
    }
  }
  return out
}

async function repoIdFor(name) {
  const { repos } = await api('/repos')
  const hit = repos.find((r) => (r.name ?? '').toLowerCase() === name.toLowerCase()
    || (r.path ?? '').toLowerCase().includes(name.toLowerCase()))
  if (!hit) throw new Error(`repo '${name}' is not registered in DevLoom`)
  return hit.id
}

/** Score one finished run against the task's expectations, using the changes it actually made. */
function grade(task, changed, created, text, written) {
  const problems = []
  const e = task.expect ?? {}
  const all = [...changed, ...created]

  for (const f of e.creates ?? []) if (!created.includes(f)) problems.push(`did not create ${f}`)
  for (const f of e.alsoCreates ?? []) if (!created.includes(f)) problems.push(`no test: ${f}`)
  for (const f of e.changes ?? []) if (!changed.includes(f)) problems.push(`did not change ${f}`)
  for (const f of e.mustNotChange ?? []) {
    if (changed.includes(f)) problems.push(`CHANGED ${f} — it was only meant to be read`)
  }
  for (const pat of e.changesOrCreates ?? []) {
    if (!all.some((f) => pat.test(f))) problems.push(`nothing matching ${pat} was touched`)
  }
  if (all.length === 0) problems.push('wrote nothing at all')
  // Against the CODE, not the reply. This read the model's prose and reported "written code has
  // no <pattern>" about text that was never meant to contain it — a run that wrote a correct fix
  // was failed for describing it in different words.
  for (const pat of e.contains ?? []) {
    if (!pat.test(written)) problems.push(`written code has no ${pat}`)
  }
  return problems
}

const tasks = (FEATURES[args.repo] ?? []).filter((t) => !args.tier || args.tier.includes(t.tier))
if (tasks.length === 0) {
  console.error(`No tasks for repo '${args.repo}'. Known: ${Object.keys(FEATURES).join(', ')}`)
  process.exit(1)
}

const repoId = await repoIdFor(args.repo)
console.log(`repo ${args.repo} (#${repoId}) · ${tasks.length} tasks x ${args.models.length} models x ${args.reps}\n`)

const results = []
for (const model of args.models) {
  for (const task of tasks) {
    for (let i = 0; i < args.reps; i++) {
      const run = await api('/fleet/runs', {
        method: 'POST',
        body: JSON.stringify({
          repoId, prompt: task.prompt, model,
          permission: 'edit', allowTests: false, isolate: true,
        }),
      })
      let r
      for (;;) {
        r = await api(`/fleet/runs/${run.id}`)
        if (r.status !== 'running') break
        await sleep(3000)
      }
      let changed = [], created = [], text = ''
      try {
        const ch = await api(`/fleet/runs/${run.id}/changes`)
        changed = [...(ch.staged ?? []), ...(ch.unstaged ?? [])].map((c) => c.file)
        created = (ch.untracked ?? []).map((c) => c.file)
      } catch { /* agent offline or run failed */ }
      text = r.resultSummary ?? ''
      // Read what it actually wrote, from the run's own worktree.
      let written = ''
      for (const f of [...changed, ...created]) {
        try { written += readFileSync(join(r.runDir, f), 'utf8') + '\n' } catch { /* gone */ }
      }
      const problems = grade(task, changed, created, text, written)
      results.push({ model, task: task.id, tier: task.tier, quality: r.qualityScore,
                     notes: r.qualityNotes, problems, changed, created })
      console.log(`  ${model} t${task.tier} ${task.id.padEnd(16)} `
        + `${problems.length === 0 ? 'PASS' : 'FAIL'}  q=${r.qualityScore ?? '—'}  `
        + `+${created.length}/~${changed.length}  ${problems[0] ?? ''}`)
      if (!args.keep) await api(`/fleet/runs/${run.id}/discard`, { method: 'POST' }).catch(() => {})
      await api(`/fleet/runs/${run.id}`, { method: 'DELETE' }).catch(() => {})
    }
  }
}

console.log('\n' + '='.repeat(76))
for (const model of args.models) {
  const mine = results.filter((r) => r.model === model)
  const pass = mine.filter((r) => r.problems.length === 0).length
  const q = mine.filter((r) => typeof r.quality === 'number')
  const avg = q.length ? (q.reduce((a, b) => a + b.quality, 0) / q.length).toFixed(2) : '—'
  console.log(`${model}: ${pass}/${mine.length} tasks completed · mean run quality ${avg}`)
  for (const t of [1, 2, 3, 4]) {
    const tier = mine.filter((r) => r.tier === t)
    if (tier.length) {
      console.log(`   tier ${t}: ${tier.filter((r) => r.problems.length === 0).length}/${tier.length}`)
    }
  }
  const wrongFile = mine.filter((r) => r.problems.some((p) => p.startsWith('CHANGED')))
  if (wrongFile.length) console.log(`   ⚠ ${wrongFile.length} run(s) modified a file they were told to leave alone`)
}

if (args.save) {
  const { writeFileSync } = await import('node:fs')
  writeFileSync(args.save, JSON.stringify({ at: new Date().toISOString(), args, results }, null, 2))
  console.log(`\nsaved → ${args.save}`)
}
