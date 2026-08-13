// The battery: questions with one right answer each, shared by run.mjs (which scores the model)
// and judge.mjs (which scores the judge against these same known answers).
import { TRACKED_FILE_COUNT } from './fixture.mjs'

export const TASKS = [
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
    id: 'survey',
    // Deliberately the expensive one: it can't be done in a single read, so it exercises the
    // multi-step path where weak models start repeating themselves or run out of budget. The
    // check is loose on purpose — this task exists to stress the loop, and the penalties in the
    // report say more about it than pass/fail does.
    prompt: 'Read every JavaScript file in src/ and list, for each one, the names it exports.',
    check: (a) => /\bPI\b/.test(a) && /\badd\b/.test(a) && /\btotal\b/.test(a) && /averagePrice/.test(a),
    why: 'multi-step gathering — where loops and step-cap failures actually happen',
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
