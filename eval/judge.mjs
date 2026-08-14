// Does the judge agree with the truth?
//
// AnswerJudge rules on "did this run do what it was asked" at runtime, where no correct answer
// exists to compare against. That makes it useful and unfalsifiable at the same time — so it is
// checked here, against the one place the right answers ARE known: the eval battery, whose tasks
// each have a deterministic pass/fail.
//
//   node eval/judge.mjs --models qwen3-coder:30b --reps 2
//
// Reports agreement, and separately the two ways it can be wrong. They are not equally bad: a
// judge that calls good work bad (false alarm) makes the score untrustworthy and trains you to
// ignore it; one that calls bad work good (missed) leaves you where you were before it existed.
//
// Only tasks marked `attemptIsCorrect` are usable here, and this is the subtlest thing in the
// harness. The judge rules on whether a run ATTEMPTED the task; the battery scores whether it got
// the answer RIGHT. Those two only coincide when the only way to fail is not to do the work —
// true for `refusal` (a confabulated region means it didn't check) and false for `reexport`
// (answering "2" instead of 4 is doing the task and getting it wrong). Scoring the judge on the
// second kind marks it wrong for being right, which is exactly what it did when the hard tasks
// landed. Excluded tasks are named in the output rather than silently dropped.

import { materialize } from './fixture.mjs'
import { TASKS } from './tasks.mjs'
import { join } from 'node:path'
import { homedir } from 'node:os'

const API = process.env.DEVLOOM_API ?? 'http://localhost/api/v1'
const FIXTURE_DIR = process.env.DEVLOOM_EVAL_DIR
  ?? join(homedir(), 'Documents', 'projects', 'devloom-eval-fixture')

const args = { models: ['qwen3-coder:30b'], reps: 1 }
for (let i = 0; i < process.argv.length; i++) {
  if (process.argv[i] === '--models') args.models = process.argv[i + 1].split(',')
  if (process.argv[i] === '--reps') args.reps = Number(process.argv[i + 1])
}

async function api(path, init) {
  const res = await fetch(API + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!res.ok) throw new Error(`${path} → ${res.status}`)
  return res.status === 204 ? null : res.json()
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function repoId() {
  const norm = (p) => (p ?? '').replace(/\\/g, '/').toLowerCase()
  const { repos } = await api('/repos')
  const hit = repos.find((r) => norm(r.path) === norm(FIXTURE_DIR))
  if (hit) return hit.id
  const after = await api('/repos', { method: 'POST', body: JSON.stringify({ path: FIXTURE_DIR }) })
  const list = Array.isArray(after) ? after : after.repos ?? []
  return list.find((r) => norm(r.path) === norm(FIXTURE_DIR)).id
}

materialize(FIXTURE_DIR)
const id = await repoId()

const usable = TASKS.filter((t) => t.attemptIsCorrect)
const skipped = TASKS.filter((t) => !t.attemptIsCorrect).map((t) => t.id)
if (skipped.length) console.log(`skipping (a wrong answer there is still an attempt): ${skipped.join(', ')}
`)

const rows = []
for (const model of args.models) {
  for (const task of usable) {
    for (let i = 0; i < args.reps; i++) {
      const run = await api('/fleet/runs', {
        method: 'POST',
        body: JSON.stringify({
          repoId: id, prompt: task.prompt, model,
          permission: 'readonly', allowTests: false, isolate: false,
        }),
      })
      let r
      for (;;) {
        r = await api(`/fleet/runs/${run.id}`)
        if (r.status !== 'running') break
        await sleep(2500)
      }
      const text = r.resultSummary ?? ''
      const correct = task.check(text)                 // ground truth
      const verdict = r.adherenceScore                 // the judge's opinion
      const saidDidIt = verdict === null || verdict === undefined ? null : verdict > 0
      rows.push({ model, task: task.id, correct, saidDidIt, note: r.adherenceNote, grounded: r.grounded })
      console.log(`  ${model} ${task.id.padEnd(11)} truth=${correct ? 'pass' : 'fail'}  `
        + `judge=${saidDidIt === null ? '—' : saidDidIt ? 'did it' : 'did not'}  ${r.adherenceNote ?? ''}`)
      await api(`/fleet/runs/${run.id}`, { method: 'DELETE' }).catch(() => {})
    }
  }
}

console.log('\n' + '='.repeat(74))
for (const model of args.models) {
  const mine = rows.filter((r) => r.model === model && r.saidDidIt !== null)
  const agree = mine.filter((r) => r.correct === r.saidDidIt).length
  const falseAlarm = mine.filter((r) => r.correct && !r.saidDidIt)   // good work called bad
  const missed = mine.filter((r) => !r.correct && r.saidDidIt)       // bad work called good
  const pct = mine.length ? Math.round((agree / mine.length) * 100) : 0
  console.log(`${model}: agrees with the truth on ${agree}/${mine.length} (${pct}%)`)
  console.log(`   called good work bad: ${falseAlarm.length}  ${falseAlarm.map((r) => r.task).join(', ')}`)
  console.log(`   called bad work good: ${missed.length}  ${missed.map((r) => r.task).join(', ')}`)
  if (pct < 80) {
    console.log('   → not trustworthy enough to show as a verdict on its own.')
  }
}
