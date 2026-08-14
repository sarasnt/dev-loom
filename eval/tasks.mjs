// The battery: questions with one right answer each, shared by run.mjs (which scores the model)
// and judge.mjs (which scores the judge against these same known answers).
import { TRACKED_FILE_COUNT, TRACKED_FILE_WORD } from './fixture.mjs'

// `attemptIsCorrect: false` means a wrong answer here is still an attempt, so eval/judge.mjs must
// not score the judge on it — see the header there.
export const TASKS = [
  {
    id: 'version',
    attemptIsCorrect: true,
    prompt: 'What version is declared in package.json? Answer with just the version number.',
    check: (a) => /\b2\.4\.1\b/.test(a),
    why: 'reads a named file and extracts one field',
  },
  {
    id: 'export',
    attemptIsCorrect: true,
    prompt: 'What is the numeric value of PI exported by src/math.js?',
    check: (a) => /3\.14159/.test(a),
    why: 'reads a second named file — catches models that answer from general knowledge (3.14159265…)',
  },
  {
    id: 'count',
    attemptIsCorrect: true,
    prompt: 'How many files are tracked in this git repository? Answer with the number.',
    // Spelled or digit, but it must not be a count of .git internals.
    check: (a) => new RegExp(`\\b(${TRACKED_FILE_COUNT}|${TRACKED_FILE_WORD})\\b`, 'i').test(a),
    why: 'the file list is in context — a model reaching for a directory tool gets .git noise instead',
  },
  {
    id: 'crossfile',
    attemptIsCorrect: true,
    prompt: 'Which file imports `add` from math.js? Answer with the file path.',
    check: (a) => /cart\.js/.test(a) && !/index\.js/.test(a.replace(/[\s\S]*cart\.js/, '')),
    why: 'needs two files related to each other, not one file read',
  },
  {
    id: 'bug',
    attemptIsCorrect: true,
    prompt: 'src/cart.js has a bug that produces NaN for one particular input. '
      + 'Name the input and explain why.',
    check: (a) => /empty|no items|zero items|\blength\b|\b0\b/i.test(a)
      && /divid|division|\/ *items\.length|zero/i.test(a),
    why: 'reasoning over code that was read, not recall',
  },
  {
    id: 'survey',
    attemptIsCorrect: true,
    // Deliberately the expensive one: it can't be done in a single read, so it exercises the
    // multi-step path where weak models start repeating themselves or run out of budget. The
    // check is loose on purpose — this task exists to stress the loop, and the penalties in the
    // report say more about it than pass/fail does.
    prompt: 'Read every JavaScript file in src/ and list, for each one, the names it exports.',
    check: (a) => /\bPI\b/.test(a) && /\badd\b/.test(a) && /\btotal\b/.test(a) && /averagePrice/.test(a),
    why: 'multi-step gathering — where loops and step-cap failures actually happen',
  },
  // ---- the hard half -------------------------------------------------------------------------
  // Added once the battery above stopped discriminating: near 100% means a prompt change can move
  // nothing, so the harness had quietly lost the ability to say "that made it worse". These need
  // facts COMBINED, not retrieved, which is where the small models actually come apart.
  {
    id: 'chain',
    attemptIsCorrect: false,
    prompt: 'What number does averagePrice([{ price: 2 }, { price: 4 }]) return? '
      + 'Answer with just the number.',
    // 2 + 4 = 6, over 2 items = 3. Requires cart.js AND math.js AND running the reduce in its head:
    // a model that reads only cart.js sees `reduce(add, 0)` and cannot know what add does.
    //
    // Scored on the tail, not the whole reply: the first version rejected any answer containing
    // "NaN" and so failed a model that explained the empty-cart case on its way to answering 3
    // correctly. Mentioning NaN is not the failure — concluding it is.
    check: (a) => {
      const end = a.trim().slice(-160)
      return /(^|[^\d.])3([^\d.]|$)/.test(end) && !/NaN/i.test(end)
    },
    why: 'composes two files and evaluates the code, rather than quoting it',
  },
  {
    id: 'seed',
    attemptIsCorrect: false,
    prompt: 'total([]) returns 0 rather than throwing. Which part of the code makes that true?',
    // The initial value passed to reduce. Naming `reduce` alone isn't it — the 0 is the answer.
    check: (a) => /reduce/i.test(a)
      && /(initial|seed|starting|second argument|accumulator).{0,40}\b0\b|\b0\b.{0,40}(initial|seed|starting|second argument)|reduce\(add, ?0\)/is.test(a),
    why: 'attributes behaviour to a specific argument, not to the function that takes it',
  },
  {
    id: 'reexport',
    attemptIsCorrect: false,
    prompt: 'How many distinct names does src/index.js re-export? Answer with the number.',
    // total, averagePrice, PI, add — four, across two re-export lines.
    check: (a) => /\b(4|four)\b/i.test(a),
    why: 'counts across two statements — a model that reads one line answers 2',
  },
  {
    id: 'refusal',
    attemptIsCorrect: true,
    // Nothing in the fixture mentions deployment, AWS or a region. The only correct answer is
    // "that isn't here" — inventing one is the failure this catches.
    prompt: 'Which AWS region is this project configured to deploy to?',
    check: (a) => !/\b(us|eu|ap|sa|ca|me|af)-(east|west|north|south|central|northeast|southeast)-\d\b/i.test(a)
      && /\bnot\b|\bno\b|isn't|does not|doesn't|cannot|can't|unable|absent|missing|nothing/i.test(a),
    why: 'confabulation check — a wrong answer here is worse than no answer',
  },
]
