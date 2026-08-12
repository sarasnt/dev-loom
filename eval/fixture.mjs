// The evaluation fixture: a tiny repo whose every fact is unambiguous, so a model's answer can be
// scored by exact match rather than judgement. It is materialised (and re-materialised) by the
// harness rather than committed, because it has to be a git repo of its own — repo analysis reads
// branch, status and tracked files, none of which exist for a plain directory.
//
// Keep it small and keep the facts SHARP. A fixture with fuzzy answers measures the scorer, not
// the model.

import { execFileSync } from 'node:child_process'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/** path → contents. Also the source of truth for the "how many files" task. */
export const FILES = {
  '.gitignore': 'node_modules/\n*.log\n',

  'README.md': `# Loom Eval Fixture

A tiny shopping-cart module used to evaluate DevLoom's model harness.
Nothing here is deployed; it exists to be read and reasoned about.
`,

  'package.json': JSON.stringify({
    name: 'loom-eval-fixture',
    version: '2.4.1',
    type: 'module',
    main: 'src/index.js',
  }, null, 2) + '\n',

  'src/math.js': `export const PI = 3.14159

export function add(a, b) {
  return a + b
}
`,

  'src/cart.js': `import { add } from './math.js'

export function total(items) {
  return items.map((i) => i.price).reduce(add, 0)
}

// Bug: no guard for an empty cart — items.length is 0, so this divides by zero
// and returns NaN instead of 0.
export function averagePrice(items) {
  return total(items) / items.length
}
`,

  'src/index.js': `export { total, averagePrice } from './cart.js'
export { PI, add } from './math.js'
`,
}

/** Facts the harness scores against, derived from FILES so they cannot drift apart. */
export const TRACKED_FILE_COUNT = Object.keys(FILES).length // 6

/** Write the fixture to `dir` as a clean git repo, replacing whatever was there. */
export function materialize(dir) {
  rmSync(dir, { recursive: true, force: true })
  mkdirSync(dir, { recursive: true })
  for (const [rel, body] of Object.entries(FILES)) {
    const full = join(dir, rel)
    mkdirSync(dirname(full), { recursive: true })
    writeFileSync(full, body)
  }
  // shell:false throughout — the same reason the host agent does it, arguments with spaces.
  const git = (...args) => execFileSync('git', args, { cwd: dir, stdio: 'pipe', shell: false })
  git('init', '-q', '-b', 'main')
  git('config', 'user.name', 'DevLoom Eval')
  git('config', 'user.email', 'eval@devloom.local')
  git('add', '-A')
  git('commit', '-q', '-m', 'Loom eval fixture')
  return dir
}
