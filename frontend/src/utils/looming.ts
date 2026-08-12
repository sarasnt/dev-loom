// What DevLoom says while you wait.
//
// A local model can take half a minute to answer, and a spinner with no words reads as "stuck".
// These are the loom's own vocabulary — warping, wefting, shuttling, the shed, the selvedge — so
// the waiting has a voice rather than a progress bar. They are deliberately not status: when the
// app knows what it is actually doing (reading a file, searching), it says that instead. These
// only fill the silence while the model thinks.

const WORDS = [
  'warping',
  'threading the heddles',
  'winding the bobbin',
  'shuttling',
  'picking the shed',
  'beating the weft',
  'tensioning the warp',
  'carding',
  'spinning up',
  'plying',
  'teasing out the thread',
  'unsnarling',
  'setting the selvedge',
  'counting picks',
  'drafting the pattern',
  'dressing the loom',
  'chasing a dropped thread',
  'tying on',
  'sleying the reed',
  'looming',
]

/**
 * A word to show now. Rotates by index so a caller can advance on a timer and get a stable
 * sequence, rather than a new random word on every re-render.
 */
export function loomingWord(i: number): string {
  return WORDS[Math.abs(i) % WORDS.length] + '…'
}

/** A random starting point, so two waits in a row don't open with the same word. */
export function loomingStart(): number {
  return Math.floor(Math.random() * WORDS.length)
}
