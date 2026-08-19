// Display labels for repositories, disambiguated only when they collide.
//
// Computed from the current set of repos and never stored: whether `implementation` is ambiguous
// depends on what else is tracked right now, so a prefix written into git_repo.name would be
// wrong the moment the other repo is removed.
const norm = (p: string) => (p || '').replace(/\\/g, '/').replace(/\/+$/, '')

// Last `depth` segments of a path, joined with '/'. The label is a label, not a path — it uses
// '/' on every platform, and keeps each segment's original casing.
function tail(path: string, depth: number): string {
  const segs = norm(path).split('/').filter(Boolean)
  return segs.slice(Math.max(0, segs.length - depth)).join('/')
}

function segmentCount(path: string): number {
  return norm(path).split('/').filter(Boolean).length
}

export function qualifyNames(paths: string[]): Map<string, string> {
  // Depth per path, grown independently: only the paths still tied get a longer label, so a repo
  // with a unique name is never prefixed.
  const depth = new Map<string, number>(paths.map((p) => [p, 1]))

  for (;;) {
    const byLabel = new Map<string, string[]>()
    for (const p of paths) {
      // Windows paths can differ only by case and must still collide.
      const key = tail(p, depth.get(p)!).toLowerCase()
      const bucket = byLabel.get(key)
      if (bucket) bucket.push(p)
      else byLabel.set(key, [p])
    }

    let grew = false
    for (const bucket of byLabel.values()) {
      if (bucket.length < 2) continue
      for (const p of bucket) {
        // Stop when a path has no more parents to give, or two repos with identical full paths
        // would loop forever.
        if (depth.get(p)! < segmentCount(p)) { depth.set(p, depth.get(p)! + 1); grew = true }
      }
    }
    if (!grew) break
  }

  return new Map(paths.map((p) => [p, tail(p, depth.get(p)!)]))
}
