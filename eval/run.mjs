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
import { materialize } from './fixture.mjs'
import { TASKS } from './tasks.mjs'

const API = process.env.DEVLOOM_API ?? 'http://localhost:8080/api/v1'
const FIXTURE_DIR = process.env.DEVLOOM_EVAL_DIR
  ?? join(homedir(), 'Documents', 'projects', 'devloom-eval-fixture')

// ---- the battery ----------------------------------------------------------------------------
// `check` returns true when the answer is right. Keep checks permissive about WORDING and strict
// about FACTS: we're measuring whether the model got the answer, not whether it phrased it our way.


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
      return {
        text: r.resultSummary ?? '',
        error: r.error ?? null,
        ms: Date.now() - started,
        // How it went about the work, scored by the backend (RunQuality) — correctness below is
        // about the answer, this is about the process that produced it.
        quality: r.qualityScore ?? null,
        notes: r.qualityNotes ?? '',
        toolCalls: r.toolCalls ?? 0,
        toolRepeats: r.toolRepeats ?? 0,
        // Did the judge send it back? A rescued run and a first-time-right run both read as
        // "pass" without this, which would make the retry loop unfalsifiable.
        retried: !!r.retried,
        adherence: typeof r.adherenceScore === 'number' ? r.adherenceScore : null,
      }
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
function avg(xs) { return xs.length ? Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 10) / 10 : 0 }

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
    const row = { pass: 0, n: 0, ms: [], quality: [], calls: [], repeats: [], penalties: {},
                  retried: 0, rescued: 0,
                  signals: { endsWithQuestion: 0, offersMenu: 0, empty: 0 }, samples: [] }
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
      if (typeof out.quality === 'number') row.quality.push(out.quality)
      row.calls.push(out.toolCalls ?? 0)
      row.repeats.push(out.toolRepeats ?? 0)
      // "ungrounded -0.40, repeat -0.10" → count each penalty by name.
      for (const part of String(out.notes ?? '').split(',')) {
        const m = part.trim().match(/^([a-z-]+)\s+-/)
        if (m) row.penalties[m[1]] = (row.penalties[m[1]] ?? 0) + 1
      }
      if (out.retried) {
        row.retried++
        // Kept only when the second attempt scored above zero, so anything positive here is a
        // run the loop pulled back from a miss.
        if (typeof out.adherence === 'number' && out.adherence > 0) row.rescued++
      }
      for (const [name, fn] of Object.entries(SIGNALS)) if (fn(text)) row.signals[name]++
      if (!ok && row.samples.length < 2) row.samples.push((out.error ?? text).slice(0, 700))
      process.stdout.write(`  ${model} ${task.id} ${i + 1}/${args.reps} ${ok ? 'pass' : 'FAIL'}\n`)
    }
    results[model][task.id] = row
  }
}

// ---- report ---------------------------------------------------------------------------------

console.log('\n' + '='.repeat(78))
for (const model of args.models) {
  console.log(`\n${model}`)
  console.log('  task        correct   quality  calls  rpt  retry   penalties')
  let totalPass = 0, totalN = 0
  const allQuality = []
  const allPenalties = {}
  for (const task of tasks) {
    const r = results[model][task.id]
    totalPass += r.pass; totalN += r.n
    allQuality.push(...r.quality)
    for (const [k, v] of Object.entries(r.penalties)) allPenalties[k] = (allPenalties[k] ?? 0) + v
    const q = r.quality.length ? (r.quality.reduce((a, b) => a + b, 0) / r.quality.length) : null
    const pen = Object.entries(r.penalties).map(([k, v]) => `${k}x${v}`).join(' ')
    const retry = r.retried ? `${r.rescued}/${r.retried}` : '—'
    console.log(`  ${task.id.padEnd(11)} ${`${r.pass}/${r.n}`.padEnd(9)} ${(q === null ? '—' : q.toFixed(2)).padStart(6)}`
      + `  ${String(avg(r.calls)).padStart(5)}  ${String(avg(r.repeats)).padStart(3)}  ${retry.padStart(5)}   ${pen}`)
  }
  const q = allQuality.length ? (allQuality.reduce((a, b) => a + b, 0) / allQuality.length) : null
  const retried = tasks.reduce((n, t) => n + results[model][t.id].retried, 0)
  const rescued = tasks.reduce((n, t) => n + results[model][t.id].rescued, 0)
  console.log(`  ${'OVERALL'.padEnd(11)} ${`${totalPass}/${totalN}`.padEnd(9)} ${(q === null ? '—' : q.toFixed(2)).padStart(6)}`
    + `   (correct ${pct(totalPass, totalN)})`)
  // "retry" is the judge sending a run back; "rescued" is that second attempt being kept because
  // it scored better. Retries that changed nothing are the price of the ones that did.
  if (retried) console.log(`  retries:    ${retried} fired, ${rescued} rescued (${pct(rescued, retried)})`)
  // What a failure actually said. Without this a 0/4 is indistinguishable from a broken check —
  // which has happened here more than once, and cost more than printing two lines ever will.
  for (const task of tasks) {
    for (const s of results[model][task.id].samples) {
      console.log(`  ↳ ${task.id}: ${JSON.stringify(s).slice(0, 700)}`)
    }
  }
  if (Object.keys(allPenalties).length) {
    console.log('  penalties:  ' + Object.entries(allPenalties).sort((a, b) => b[1] - a[1])
      .map(([k, v]) => `${k} x${v}`).join('  ·  '))
  }
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
