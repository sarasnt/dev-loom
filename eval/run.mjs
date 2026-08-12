// Model harness evaluation. Runs a fixed battery of questions about a fixture repo through the
// real product path (POST /fleet/runs) and scores the answers deterministically.
//
// Why this exists: local models vary run to run. The same prompt and model would read two files
// and quote them correctly on one attempt, then list the directory and stop on the next — so
// "this prompt is better" was unfalsifiable by trying it once. Every task here has one right
// answer that a regex can check, and every task runs N times, so a change shows up as a moved
// pass rate rather than a feeling.
//
//   node eval/run.mjs --models qwen3-coder:30b,qwen2.5-coder:7b --reps 3
//   node eval/run.mjs --tasks version,refusal --reps 5      # iterate on one failure
//   node eval/run.mjs --save baseline.json                  # then --compare baseline.json
//
// Needs the app running (backend on :8080) and the host agent up.

import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { homedir } from 'node:os'
import { materialize, TRACKED_FILE_COUNT } from './fixture.mjs'

const API = process.env.DEVLOOM_API ?? 'http://localhost:8080/api/v1'
const FIXTURE_DIR = process.env.DEVLOOM_EVAL_DIR
  ?? join(homedir(), 'Documents', 'projects', 'devloom-eval-fixture')

// ---- the battery ----------------------------------------------------------------------------
// `check` returns true when the answer is right. Keep checks permissive about WORDING and strict
// about FACTS: we're measuring whether the model got the answer, not whether it phrased it our way.

const TASKS = [
  {
    id: 'version',
    prompt: 'What version is declared in package.json? Answer with just the version number.',
    check: (a) => /\b2\.4\.1\b/.test(a),
    why: 'reads a named file and extracts one field',
  },
  {
    id: 'export',
    prompt: 'What is the numeric value of PI exported by src/math.js?',
    check: (a) => /3\.14159/.test(a),
    why: 'reads a second named file — catches models that answer from general knowledge (3.14159265…)',
  },
  {
    id: 'count',
    prompt: 'How many files are tracked in this git repository? Answer with the number.',
    // Spelled or digit, but it must not be a count of .git internals.
    check: (a) => new RegExp(`\\b(${TRACKED_FILE_COUNT}|six)\\b`, 'i').test(a),
    why: 'the file list is in context — a model reaching for a directory tool gets .git noise instead',
  },
  {
    id: 'crossfile',
    prompt: 'Which file imports `add` from math.js? Answer with the file path.',
    check: (a) => /cart\.js/.test(a) && !/index\.js/.test(a.replace(/[\s\S]*cart\.js/, '')),
    why: 'needs two files related to each other, not one file read',
  },
  {
    id: 'bug',
    prompt: 'src/cart.js has a bug that produces NaN for one particular input. '
      + 'Name the input and explain why.',
    check: (a) => /empty|no items|zero items|\blength\b|\b0\b/i.test(a)
      && /divid|division|\/ *items\.length|zero/i.test(a),
    why: 'reasoning over code that was read, not recall',
  },
  {
    id: 'refusal',
    // Nothing in the fixture mentions deployment, AWS or a region. The only correct answer is
    // "that isn't here" — inventing one is the failure this catches.
    prompt: 'Which AWS region is this project configured to deploy to?',
    check: (a) => !/\b(us|eu|ap|sa|ca|me|af)-(east|west|north|south|central|northeast|southeast)-\d\b/i.test(a)
      && /\bnot\b|\bno\b|isn't|does not|doesn't|cannot|can't|unable|absent|missing|nothing/i.test(a),
    why: 'confabulation check — a wrong answer here is worse than no answer',
  },
]

// ---- behavioural signals (measured on every answer, pass or fail) ---------------------------

const SIGNALS = {
  // Ends by asking the user something. A background run has nobody to answer, so this is a
  // non-answer however good the prose is.
  endsWithQuestion: (a) =>
    /\?\s*$/.test(a.trim())
    || /(would you like|let me know|shall i|do you want|how would you like|what would you like)\b/i.test(a),
  // Offers a menu instead of doing the work.
  offersMenu: (a) => /(^|\n)\s*(\d\.|[-*])\s.*\?\s*$/m.test(a),
  empty: (a) => a.trim().length === 0,
}

// ---- plumbing -------------------------------------------------------------------------------

const args = parseArgs(process.argv.slice(2))

async function api(path, init) {
  const res = await fetch(API + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!res.ok) throw new Error(`${init?.method ?? 'GET'} ${path} → ${res.status} ${await res.text()}`)
  return res.status === 204 ? null : res.json()
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Register (or find) the fixture as a DevLoom repo and return its id. */
async function fixtureRepoId() {
  const norm = (p) => (p ?? '').replace(/\\/g, '/').toLowerCase()
  const byPath = (list) => list.find((r) => norm(r.path) === norm(FIXTURE_DIR))
  const found = byPath((await api('/repos')).repos ?? [])
  if (found) return found.id
  // Adding returns the whole repo list, not the new row.
  const after = await api('/repos', { method: 'POST', body: JSON.stringify({ path: FIXTURE_DIR }) })
  const added = byPath(Array.isArray(after) ? after : after.repos ?? [])
  if (!added) throw new Error(`could not register fixture repo at ${FIXTURE_DIR}`)
  return added.id
}

/** One task, one model: launch a run and wait for its result text. */
async function runOnce(repoId, model, prompt) {
  const started = Date.now()
  const run = await api('/fleet/runs', {
    method: 'POST',
    body: JSON.stringify({ repoId, prompt, model, permission: 'readonly', allowTests: false, isolate: false }),
  })
  for (;;) {
    const r = await api(`/fleet/runs/${run.id}`)
    if (r.status !== 'running') {
      // Tidy up: the eval must not leave dozens of rows on the user's Fleet board.
      await api(`/fleet/runs/${run.id}`, { method: 'DELETE' }).catch(() => {})
      return { text: r.resultSummary ?? '', error: r.error ?? null, ms: Date.now() - started }
    }
    await sleep(2000)
  }
}

function parseArgs(argv) {
  const out = { models: ['qwen3-coder:30b'], reps: 3, tasks: null, save: null, compare: null }
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i + 1]
    switch (argv[i]) {
      case '--models': out.models = v.split(','); i++; break
      case '--reps': out.reps = Number(v); i++; break
      case '--tasks': out.tasks = v.split(','); i++; break
      case '--save': out.save = v; i++; break
      case '--compare': out.compare = v; i++; break
      default: break
    }
  }
  return out
}

function pct(n, d) { return d === 0 ? '—' : `${Math.round((n / d) * 100)}%` }

// ---- main -----------------------------------------------------------------------------------

const tasks = args.tasks ? TASKS.filter((t) => args.tasks.includes(t.id)) : TASKS
if (tasks.length === 0) {
  console.error(`No matching tasks. Known: ${TASKS.map((t) => t.id).join(', ')}`)
  process.exit(1)
}

console.log(`fixture  ${FIXTURE_DIR}`)
materialize(FIXTURE_DIR)
const repoId = await fixtureRepoId()
console.log(`repo id  ${repoId}`)
console.log(`running  ${tasks.length} tasks x ${args.models.length} models x ${args.reps} reps`
  + ` = ${tasks.length * args.models.length * args.reps} runs\n`)

const results = {}
for (const model of args.models) {
  results[model] = {}
  for (const task of tasks) {
    const row = { pass: 0, n: 0, ms: [], signals: { endsWithQuestion: 0, offersMenu: 0, empty: 0 }, samples: [] }
    for (let i = 0; i < args.reps; i++) {
      let out
      try {
        out = await runOnce(repoId, model, task.prompt)
      } catch (e) {
        out = { text: '', error: String(e.message ?? e), ms: 0 }
      }
      const text = out.text ?? ''
      const ok = !out.error && task.check(text)
      row.n++
      if (ok) row.pass++
      row.ms.push(out.ms)
      for (const [name, fn] of Object.entries(SIGNALS)) if (fn(text)) row.signals[name]++
      if (!ok && row.samples.length < 2) row.samples.push((out.error ?? text).slice(0, 200))
      process.stdout.write(`  ${model} ${task.id} ${i + 1}/${args.reps} ${ok ? 'pass' : 'FAIL'}\n`)
    }
    results[model][task.id] = row
  }
}

// ---- report ---------------------------------------------------------------------------------

console.log('\n' + '='.repeat(78))
for (const model of args.models) {
  console.log(`\n${model}`)
  console.log('  task        pass      med ms   ends-with-?  notes')
  let totalPass = 0, totalN = 0
  for (const task of tasks) {
    const r = results[model][task.id]
    const med = r.ms.slice().sort((a, b) => a - b)[Math.floor(r.ms.length / 2)] ?? 0
    totalPass += r.pass; totalN += r.n
    console.log(`  ${task.id.padEnd(11)} ${`${r.pass}/${r.n}`.padEnd(9)} ${String(med).padStart(6)}`
      + `   ${pct(r.signals.endsWithQuestion, r.n).padStart(10)}   ${r.samples[0] ? JSON.stringify(r.samples[0].slice(0, 60)) : ''}`)
  }
  console.log(`  ${'OVERALL'.padEnd(11)} ${`${totalPass}/${totalN}`.padEnd(9)} ${pct(totalPass, totalN)}`)
}

if (args.save) {
  writeFileSync(args.save, JSON.stringify({ at: new Date().toISOString(), args, results }, null, 2))
  console.log(`\nsaved → ${args.save}`)
}

if (args.compare) {
  const before = JSON.parse(readFileSync(args.compare, 'utf8'))
  console.log(`\nvs ${args.compare}:`)
  for (const model of args.models) {
    for (const task of tasks) {
      const b = before.results?.[model]?.[task.id]
      const a = results[model][task.id]
      if (!b) continue
      const d = (a.pass / a.n) - (b.pass / b.n)
      const arrow = d > 0.001 ? '▲' : d < -0.001 ? '▼' : '='
      console.log(`  ${arrow} ${model} ${task.id}: ${b.pass}/${b.n} → ${a.pass}/${a.n}`)
    }
  }
}
