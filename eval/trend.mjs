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
