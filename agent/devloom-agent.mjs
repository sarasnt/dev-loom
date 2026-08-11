#!/usr/bin/env node
// DevLoom host agent — a tiny local bridge the (containerised) backend calls via
// host.docker.internal to reach things a Linux container can't: your logged-in `claude`
// CLI (subscription), git, and gh/glab. Cross-platform: Windows, WSL, Debian.
//
//   node agent/devloom-agent.mjs           # listens on 127.0.0.1:8765
//   DEVLOOM_AGENT_PORT=9000 node agent/devloom-agent.mjs
//
// HTTP endpoints use Node built-ins only. The embedded terminal (/pty, for brainstorm's
// claude-cli mode) additionally needs `ws` + `node-pty` — run `npm install` in this folder;
// they're lazy-loaded, so everything else works without them. Prereqs for the Claude bridge:
// the `claude` CLI installed and logged in (claude 2.x). Repos features also use `git`/`gh`/`glab`.

import http from 'node:http'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const PORT = Number(process.env.DEVLOOM_AGENT_PORT || 8765)
const HOST = process.env.DEVLOOM_AGENT_HOST || '127.0.0.1'
const IS_WIN = process.platform === 'win32'
const AGENT_DIR = path.dirname(fileURLToPath(import.meta.url))
const LOGO_PATH = path.join(AGENT_DIR, 'assets', 'devloom-logo.png')

// Run a command, capturing stdout/stderr. `input` (if given) is written to stdin.
function run(cmd, args, { input, cwd, timeoutMs = 180000 } = {}) {
  return new Promise((resolve) => {
    let child
    try {
      child = spawn(cmd, args, { cwd, shell: IS_WIN }) // shell:true on Windows resolves .cmd/.ps1
    } catch (e) {
      resolve({ code: -1, out: '', err: String(e) })
      return
    }
    let out = '', err = ''
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs)
    child.stdout.on('data', (d) => (out += d))
    child.stderr.on('data', (d) => (err += d))
    child.on('error', (e) => { clearTimeout(timer); resolve({ code: -1, out, err: String(e) }) })
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, out, err }) })
    if (input != null) { child.stdin.write(input); child.stdin.end() }
  })
}

async function has(cmd, args = ['--version']) {
  const r = await run(cmd, args, { timeoutMs: 8000 })
  return r.code === 0 ? (r.out || r.err).trim().split('\n')[0] : null
}

function json(res, code, body) {
  const s = JSON.stringify(body)
  res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' })
  res.end(s)
}

function readBody(req) {
  return new Promise((resolve) => {
    let b = ''
    req.on('data', (c) => (b += c))
    req.on('end', () => {
      try { resolve(b ? JSON.parse(b) : {}) } catch { resolve({}) }
    })
  })
}

// ---- Claude Code (subscription) ----
// Runs `claude -p --output-format json`, prompt on stdin so large/multiline prompts are safe.
// Optional cwd runs it inside a repo (so it can read/iterate that repo and use its skills);
// optional sessionId resumes a prior session for multi-turn continuity. In a repo we use
// plan mode (read-only) so brainstorming never edits your files.
async function claude(system, prompt, cwd, sessionId) {
  const base = ['-p', '--output-format', 'json']
  if (system) base.push('--append-system-prompt', system)
  if (cwd) base.push('--permission-mode', 'plan') // read-only iteration in the repo
  const build = (withResume) => {
    const a = [...base]
    if (withResume && sessionId) a.push('--resume', sessionId)
    return a
  }
  let r = await run('claude', build(true), { input: prompt, cwd: cwd || undefined, timeoutMs: 600000 })
  // A stale/invalid session id fails resume → retry once as a fresh session.
  if (r.code !== 0 && sessionId) {
    r = await run('claude', build(false), { input: prompt, cwd: cwd || undefined, timeoutMs: 600000 })
  }
  if (r.code !== 0) {
    throw new Error(`claude exited ${r.code}: ${(r.err || r.out).slice(0, 400)}`)
  }
  try {
    const parsed = JSON.parse(r.out)
    return {
      text: String(parsed.result ?? parsed.text ?? ''),
      model: parsed.model || 'claude-code',
      sessionId: parsed.session_id || sessionId || null,
    }
  } catch {
    return { text: r.out.trim(), model: 'claude-code', sessionId: sessionId || null }
  }
}

// ---- git / repositories ----
function git(cwd, args) {
  return run('git', args, { cwd, timeoutMs: 120000 })
}

/**
 * Native desktop toast — no npm deps; shells out to the OS notifier. Windows uses a PowerShell
 * toast (built into Win10/11), macOS uses osascript, Linux uses notify-send. Best-effort: returns
 * { ok:false, error } rather than throwing so the backend can log-and-continue.
 */
async function osNotify(title, body, urgency = 'normal') {
  const t = String(title || 'DevLoom')
  const b = String(body || '')
  const urgent = urgency === 'urgent'
  try {
    if (IS_WIN) {
      // Branded ToastGeneric with the DevLoom app-logo. Native toasts can't animate, so we make
      // it recognizable (logo) and, when urgent, harder to ignore: a 'reminder' toast stays on
      // screen until dismissed and uses a distinct looping alarm sound.
      const xmlEsc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
      const logo = fs.existsSync(LOGO_PATH)
        ? `<image placement="appLogoOverride" hint-crop="none" src="${xmlEsc('file:///' + LOGO_PATH.replace(/\\/g, '/'))}"/>`
        : ''
      const audio = urgent
        ? '<audio src="ms-winsoundevent:Notification.Looping.Alarm2" loop="false"/>'
        : '<audio src="ms-winsoundevent:Notification.Reminder"/>'
      const scenario = urgent ? ' scenario="reminder"' : ''
      const toastXml =
        `<toast${scenario}><visual><binding template="ToastGeneric">` +
        logo +
        `<text>${xmlEsc(t)}</text><text>${xmlEsc(b)}</text>` +
        `</binding></visual>${audio}</toast>`
      // Single-quoted PS literal (escape ' as ''); whole script base64'd via -EncodedCommand so
      // the Windows shell can't mangle quotes.
      const q = (s) => "'" + String(s).replace(/'/g, "''") + "'"
      const script = [
        '[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null;',
        '[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > $null;',
        '$xml = [Windows.Data.Xml.Dom.XmlDocument]::new();',
        `$xml.LoadXml(${q(toastXml)});`,
        '$toast = [Windows.UI.Notifications.ToastNotification]::new($xml);',
        '[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("DevLoom").Show($toast);',
      ].join(' ')
      const encoded = Buffer.from(script, 'utf16le').toString('base64')
      const r = await run('powershell', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], { timeoutMs: 10000 })
      return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'powershell toast failed').trim().slice(0, 300) }
    }
    if (process.platform === 'darwin') {
      const esc = (s) => s.replace(/"/g, '\\"')
      const sound = urgent ? ' sound name "Basso"' : ''
      const r = await run('osascript', ['-e', `display notification "${esc(b)}" with title "${esc(t)}"${sound}`], { timeoutMs: 8000 })
      return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'osascript failed').trim().slice(0, 300) }
    }
    const args = ['-a', 'DevLoom', '-u', urgent ? 'critical' : 'normal']
    if (fs.existsSync(LOGO_PATH)) args.push('-i', LOGO_PATH)
    args.push(t, b)
    const r = await run('notify-send', args, { timeoutMs: 8000 })
    return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'notify-send failed (is libnotify installed?)').trim().slice(0, 300) }
  } catch (e) {
    return { ok: false, error: String(e).slice(0, 300) }
  }
}

function hostOf(remoteUrl) {
  const u = (remoteUrl || '').toLowerCase()
  if (u.includes('github.com')) return 'github'
  if (u.includes('bitbucket.org') || u.includes('bitbucket')) return 'bitbucket'
  if (u.includes('gitlab')) return 'gitlab'
  return remoteUrl ? 'git' : 'none'
}

// "owner/repo" from an https or ssh remote url.
function repoSlug(remoteUrl) {
  if (!remoteUrl) return ''
  let s = remoteUrl.trim().replace(/\.git$/, '')
  s = s.replace(/^git@[^:]+:/, '').replace(/^https?:\/\/[^/]+\//, '')
  return s
}

async function repoInfo(dir) {
  const isRepo = fs.existsSync(path.join(dir, '.git'))
  if (!isRepo) return null
  const [branch, remote, dirty, upstream, upstreamRef, gitDirRes] = await Promise.all([
    git(dir, ['rev-parse', '--abbrev-ref', 'HEAD']),
    git(dir, ['remote', 'get-url', 'origin']),
    git(dir, ['status', '--porcelain']),
    git(dir, ['rev-list', '--left-right', '--count', '@{u}...HEAD']),
    git(dir, ['rev-parse', '--abbrev-ref', '@{u}']),
    git(dir, ['rev-parse', '--git-dir']),
  ])
  const remoteUrl = remote.code === 0 ? remote.out.trim() : ''
  const hasUpstream = upstream.code === 0
  let behind = 0, ahead = 0
  if (hasUpstream) {
    const m = upstream.out.trim().split(/\s+/)
    behind = Number(m[0] || 0); ahead = Number(m[1] || 0)
  }
  // Working-tree counts from porcelain (X = staged, Y = unstaged; "??" = untracked).
  let staged = 0, unstaged = 0, untracked = 0
  if (dirty.code === 0) {
    for (const line of dirty.out.split('\n')) {
      if (!line) continue
      if (line.startsWith('??')) { untracked++; continue }
      if (line[0] !== ' ' && line[0] !== '?') staged++
      if (line[1] !== ' ' && line[1] !== '?') unstaged++
    }
  }
  const cfg = await gitIdentity(dir)
  return {
    path: dir,
    name: path.basename(dir),
    branch: branch.code === 0 ? branch.out.trim() : '(unknown)',
    remote: remoteUrl,
    slug: repoSlug(remoteUrl),
    host: hostOf(remoteUrl),
    dirty: dirty.code === 0 ? dirty.out.trim().length > 0 : false,
    staged, unstaged, untracked,
    ahead, behind, hasUpstream,
    upstream: hasUpstream && upstreamRef.code === 0 ? upstreamRef.out.trim() : null,
    operation: detectGitOperation(dir, gitDirRes.code === 0 ? gitDirRes.out.trim() : '.git'),
    user: cfg,
  }
}

/** The repo's default branch (origin/HEAD), stripped of the "origin/" prefix. */
async function defaultBranch(dir) {
  const s = await git(dir, ['symbolic-ref', '--short', 'refs/remotes/origin/HEAD'])
  return s.code === 0 ? s.out.trim().replace(/^origin\//, '') : null
}

/**
 * Source-branch freshness (repo-spec §7.3): resolve the source (override → default → none) and
 * compute ahead/behind of HEAD vs the latest fetched source ref, using merge-base semantics.
 * Read-only. `behind` = commits on source the branch lacks; `ahead` = commits unique to the branch.
 */
async function sourceStatus(dir, override) {
  const def = await defaultBranch(dir)
  const source = override && override.trim() ? override.trim() : def
  const base = { source: source || null, defaultBranch: def, hasSource: false, sourceAhead: 0, sourceBehind: 0 }
  if (!source) return base
  let ref = null
  if ((await git(dir, ['rev-parse', '--verify', '--quiet', 'refs/remotes/origin/' + source])).code === 0) ref = 'origin/' + source
  else if ((await git(dir, ['rev-parse', '--verify', '--quiet', source])).code === 0) ref = source
  if (!ref) return { ...base, missing: true }
  const [behind, ahead] = await Promise.all([
    git(dir, ['rev-list', '--count', `HEAD..${ref}`]),
    git(dir, ['rev-list', '--count', `${ref}..HEAD`]),
  ])
  return {
    source, defaultBranch: def, hasSource: true, ref,
    sourceBehind: behind.code === 0 ? Number(behind.out.trim() || 0) : 0,
    sourceAhead: ahead.code === 0 ? Number(ahead.out.trim() || 0) : 0,
  }
}

/**
 * Fetch remote-tracking refs (repo-spec §7/§14: "fetch once per remote"). Only updates
 * refs/remotes — never touches HEAD, index, or the working tree, so it is safe read-only.
 */
async function fetchRemote(dir) {
  const has = await git(dir, ['remote'])
  if (has.code !== 0 || !has.out.trim()) return { ok: false, error: 'no remote configured' }
  const r = await git(dir, ['fetch', '--all', '--prune', '--quiet'])
  return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'fetch failed').trim().slice(0, 300) }
}

/**
 * Predict content conflicts from integrating the resolved source branch into HEAD, without
 * mutating anything (repo-spec §7.4). Uses `git merge-tree --write-tree` (git 2.38+); exit 1
 * means conflicts, and with --name-only the body lists the conflicting paths. States:
 * clean | conflict | unknown (source unresolved) | unable (git too old / other failure).
 */
async function conflictCheck(dir, override) {
  const s = await sourceStatus(dir, override)
  if (!s.hasSource || !s.ref) {
    return { state: 'unknown', ref: s.ref || null, files: [],
      reason: s.missing ? 'source ref not found — fetch or reconfigure' : 'source branch cannot be resolved' }
  }
  const r = await git(dir, ['merge-tree', '--write-tree', '--name-only', 'HEAD', s.ref])
  if (r.code === 0) return { state: 'clean', ref: s.ref, files: [] }
  if (r.code === 1) {
    // First line is the (unused) tree OID; conflicting paths follow until a blank line.
    const lines = r.out.split('\n').slice(1)
    const files = []
    for (const l of lines) { if (!l.trim()) break; files.push(l.trim()) }
    return { state: 'conflict', ref: s.ref, files }
  }
  const why = (r.err || '').toLowerCase()
  const tooOld = why.includes('usage') || why.includes('unknown option') || why.includes('--write-tree')
  return { state: 'unable', ref: s.ref, files: [],
    reason: tooOld ? 'git version does not support conflict prediction' : (r.err || 'merge-tree failed').trim().slice(0, 200) }
}

/**
 * Abort an in-progress merge/rebase/cherry-pick/revert (repo-spec: when an operation we don't
 * manage leaves the repo mid-conflict, let the user back out). Restores HEAD/index/worktree to
 * the pre-operation state via git's own `--abort`; no-op with a clear message if none is active.
 */
async function abortOperation(dir) {
  const gd = await git(dir, ['rev-parse', '--git-dir'])
  const op = gd.code === 0 ? detectGitOperation(dir, gd.out.trim()) : null
  if (!op) return { ok: false, operation: null, output: 'no merge, rebase, cherry-pick, or revert in progress' }
  const cmd = op === 'rebase' ? ['rebase', '--abort']
    : op === 'cherry-pick' ? ['cherry-pick', '--abort']
    : op === 'revert' ? ['revert', '--abort']
    : ['merge', '--abort']
  const r = await git(dir, cmd)
  return { ok: r.code === 0, operation: op, output: (r.out + r.err).trim().slice(0, 2000) }
}

/** Detect an in-progress git operation (merge/rebase/cherry-pick/revert) from the git dir. */
function detectGitOperation(dir, gitDir) {
  try {
    const gd = path.isAbsolute(gitDir) ? gitDir : path.join(dir, gitDir)
    if (fs.existsSync(path.join(gd, 'rebase-merge')) || fs.existsSync(path.join(gd, 'rebase-apply'))) return 'rebase'
    if (fs.existsSync(path.join(gd, 'MERGE_HEAD'))) return 'merge'
    if (fs.existsSync(path.join(gd, 'CHERRY_PICK_HEAD'))) return 'cherry-pick'
    if (fs.existsSync(path.join(gd, 'REVERT_HEAD'))) return 'revert'
  } catch { /* ignore */ }
  return null
}

async function gitIdentity(dir) {
  const [name, email] = await Promise.all([
    git(dir, ['config', 'user.name']),
    git(dir, ['config', 'user.email']),
  ])
  return { name: name.out.trim(), email: email.out.trim() }
}

async function scanRepos(root) {
  const found = []
  if (!root || !fs.existsSync(root)) return found
  const self = await repoInfo(root)
  if (self) found.push(self)
  let entries = []
  try { entries = fs.readdirSync(root, { withFileTypes: true }) } catch { entries = [] }
  for (const e of entries) {
    if (!e.isDirectory()) continue
    const info = await repoInfo(path.join(root, e.name))
    if (info) found.push(info)
  }
  return found
}

// Open a PR/MR with the right tool for the host; falls back to a web URL to create it.
async function openPr(dir, info) {
  if (info.host === 'github') {
    const r = await git(dir, []) // noop to keep pattern; use gh below
    void r
    const gh = await run('gh', ['pr', 'create', '--fill'], { cwd: dir, timeoutMs: 60000 })
    if (gh.code === 0) return { ok: true, url: (gh.out.match(/https?:\/\/\S+/) || [''])[0] || gh.out.trim() }
    // Already-exists or no auth → offer the web page.
    const web = `https://github.com/${info.slug}/pull/new/${encodeURIComponent(info.branch)}`
    return { ok: false, url: web, web: true, error: (gh.err || gh.out).trim().slice(0, 300) }
  }
  if (info.host === 'gitlab') {
    const glab = await run('glab', ['mr', 'create', '--fill', '--yes'], { cwd: dir, timeoutMs: 60000 })
    if (glab.code === 0) return { ok: true, url: (glab.out.match(/https?:\/\/\S+/) || [''])[0] || glab.out.trim() }
    return { ok: false, error: (glab.err || glab.out).trim().slice(0, 300) }
  }
  if (info.host === 'bitbucket') {
    const web = `https://bitbucket.org/${info.slug}/pull-requests/new?source=${encodeURIComponent(info.branch)}`
    return { ok: true, url: web, web: true }
  }
  return { ok: false, error: `unsupported host: ${info.host}` }
}

// List directories for the folder browser. Blank path → home (+ drives on Windows).
function listDirs(p) {
  let dir = p && p.trim() ? p : os.homedir()
  const roots = []
  if (IS_WIN && (!p || !p.trim())) {
    // Offer drive roots on Windows so you can jump across volumes.
    for (const l of 'CDEFGH') {
      const d = `${l}:\\`
      if (fs.existsSync(d)) roots.push({ name: d, path: d })
    }
  }
  let entries = []
  try { entries = fs.readdirSync(dir, { withFileTypes: true }) } catch { dir = os.homedir(); entries = fs.readdirSync(dir, { withFileTypes: true }) }
  const dirs = entries
    .filter((e) => e.isDirectory() && !e.name.startsWith('.'))
    .map((e) => ({ name: e.name, path: path.join(dir, e.name), repo: fs.existsSync(path.join(dir, e.name, '.git')) }))
    .sort((a, b) => a.name.localeCompare(b.name))
  const parent = path.dirname(dir)
  return {
    path: dir,
    parent: parent === dir ? null : parent,
    isRepo: fs.existsSync(path.join(dir, '.git')),
    drives: roots,
    dirs,
  }
}

// Parse `git status --porcelain` into staged / unstaged / untracked file lists.
async function changes(dir) {
  const r = await git(dir, ['status', '--porcelain'])
  const staged = [], unstaged = [], untracked = []
  if (r.code !== 0) return { staged, unstaged, untracked }
  for (const line of r.out.split('\n')) {
    if (!line.trim()) continue
    const x = line[0], y = line[1], file = line.slice(3)
    if (x === '?' && y === '?') { untracked.push({ file, status: 'new' }); continue }
    if (x !== ' ' && x !== '?') staged.push({ file, status: codeName(x) })
    if (y !== ' ' && y !== '?') unstaged.push({ file, status: codeName(y) })
  }
  return { staged, unstaged, untracked }
}
function codeName(c) {
  return { M: 'modified', A: 'added', D: 'deleted', R: 'renamed', C: 'copied', U: 'conflict' }[c] || c
}

// Stream a Claude Code turn: spawn with stream-json + partial messages and forward the raw
// ndjson event lines to the HTTP response as they arrive (the backend parses/relays them).
function streamClaude(res, body) {
  const { system, prompt, cwd, sessionId, model } = body
  const args = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages']
  if (system) args.push('--append-system-prompt', system)
  if (cwd) args.push('--permission-mode', 'plan')
  if (model) args.push('--model', model)
  if (sessionId) args.push('--resume', sessionId)
  res.writeHead(200, {
    'Content-Type': 'application/x-ndjson',
    'Cache-Control': 'no-cache',
    'Access-Control-Allow-Origin': '*',
    Connection: 'keep-alive',
  })
  let child
  try {
    child = spawn('claude', args, { cwd: cwd || undefined, shell: IS_WIN })
  } catch (e) {
    res.write(JSON.stringify({ type: 'error', error: String(e) }) + '\n')
    res.end()
    return
  }
  child.stdout.on('data', (d) => res.write(d))
  child.stderr.on('data', (d) => res.write(JSON.stringify({ type: 'stderr', text: String(d) }) + '\n'))
  child.on('error', (e) => { res.write(JSON.stringify({ type: 'error', error: String(e) }) + '\n'); res.end() })
  child.on('close', () => res.end())
  if (prompt != null) { child.stdin.write(prompt); child.stdin.end() }
  res.on('close', () => { try { child.kill('SIGKILL') } catch { /* ignore */ } })
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') return json(res, 204, {})
  const url = new URL(req.url, `http://${req.headers.host}`)
  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      const [claudeV, gitV, ghV, glabV] = await Promise.all([
        has('claude'), has('git'), has('gh'), has('glab'),
      ])
      return json(res, 200, {
        ok: true, platform: process.platform,
        claude: claudeV, git: gitV, gh: ghV, glab: glabV,
      })
    }
    if (req.method === 'POST' && url.pathname === '/notify') {
      const { title, body, urgency } = await readBody(req)
      return json(res, 200, await osNotify(title, body, urgency))
    }
    if (req.method === 'POST' && url.pathname === '/claude') {
      const body = await readBody(req)
      if (!body.prompt) return json(res, 400, { error: 'prompt required' })
      const out = await claude(body.system, body.prompt, body.cwd, body.sessionId)
      return json(res, 200, out)
    }
    if (req.method === 'POST' && url.pathname === '/claude/stream') {
      const body = await readBody(req)
      if (!body.prompt) return json(res, 400, { error: 'prompt required' })
      streamClaude(res, body)
      return
    }

    // ---- repositories ----
    if (req.method === 'POST' && url.pathname === '/repos/scan') {
      const { root } = await readBody(req)
      return json(res, 200, { repos: await scanRepos(root) })
    }
    if (req.method === 'POST' && url.pathname === '/repos/status') {
      const { path: p } = await readBody(req)
      const info = await repoInfo(p)
      return info ? json(res, 200, info) : json(res, 400, { error: 'not a git repo' })
    }
    if (req.method === 'POST' && url.pathname === '/repos/source') {
      const { path: p, source } = await readBody(req)
      return json(res, 200, await sourceStatus(p, source))
    }
    if (req.method === 'POST' && url.pathname === '/repos/fetch') {
      const { path: p } = await readBody(req)
      return json(res, 200, await fetchRemote(p))
    }
    if (req.method === 'POST' && url.pathname === '/repos/conflict') {
      const { path: p, source } = await readBody(req)
      return json(res, 200, await conflictCheck(p, source))
    }
    if (req.method === 'POST' && url.pathname === '/repos/config') {
      const { path: p, name, email } = await readBody(req)
      if (name != null) await git(p, ['config', 'user.name', String(name)])
      if (email != null) await git(p, ['config', 'user.email', String(email)])
      return json(res, 200, await gitIdentity(p))
    }
    if (req.method === 'POST' && url.pathname === '/repos/pull') {
      const { path: p } = await readBody(req)
      const r = await git(p, ['pull', '--ff-only'])
      return json(res, 200, { ok: r.code === 0, output: (r.out + r.err).trim().slice(0, 2000) })
    }
    if (req.method === 'POST' && url.pathname === '/repos/push') {
      const { path: p, force } = await readBody(req)
      // Force uses --force-with-lease (never a bare --force): refuses if the remote moved
      // since our last fetch, so we can't clobber a teammate's push (repo-spec §9.5).
      const args = force ? ['push', '--force-with-lease'] : ['push']
      const r = await git(p, args)
      const output = (r.out + r.err).trim()
      // Signal a rejected non-fast-forward so the UI can offer force-with-lease.
      const rejected = r.code !== 0 && /\b(non-fast-forward|fetch first|rejected|force)\b/i.test(output)
      return json(res, 200, { ok: r.code === 0, output: output.slice(0, 2000), rejected, forced: !!force })
    }
    if (req.method === 'POST' && url.pathname === '/repos/abort') {
      const { path: p } = await readBody(req)
      return json(res, 200, await abortOperation(p))
    }
    if (req.method === 'POST' && url.pathname === '/repos/pr') {
      const { path: p } = await readBody(req)
      const info = await repoInfo(p)
      if (!info) return json(res, 400, { error: 'not a git repo' })
      return json(res, 200, await openPr(p, info))
    }
    if (req.method === 'POST' && url.pathname === '/fs/list') {
      const { path: p } = await readBody(req)
      return json(res, 200, listDirs(p))
    }
    if (req.method === 'POST' && url.pathname === '/repos/changes') {
      const { path: p } = await readBody(req)
      return json(res, 200, await changes(p))
    }
    if (req.method === 'POST' && url.pathname === '/repos/stage') {
      const { path: p, files } = await readBody(req)
      const args = files && files.length ? ['add', '--', ...files] : ['add', '-A']
      const r = await git(p, args)
      return json(res, 200, { ok: r.code === 0, output: (r.out + r.err).trim().slice(0, 1000) })
    }
    if (req.method === 'POST' && url.pathname === '/repos/unstage') {
      const { path: p, files } = await readBody(req)
      const args = files && files.length ? ['restore', '--staged', '--', ...files] : ['reset']
      const r = await git(p, args)
      return json(res, 200, { ok: r.code === 0, output: (r.out + r.err).trim().slice(0, 1000) })
    }
    if (req.method === 'POST' && url.pathname === '/repos/commit') {
      const { path: p, message } = await readBody(req)
      if (!message || !message.trim()) return json(res, 400, { error: 'commit message required' })
      const r = await git(p, ['commit', '-m', message])
      return json(res, 200, { ok: r.code === 0, output: (r.out + r.err).trim().slice(0, 2000) })
    }
    if (req.method === 'POST' && url.pathname === '/repos/branches') {
      const { path: p } = await readBody(req)
      const [cur, list] = await Promise.all([
        git(p, ['rev-parse', '--abbrev-ref', 'HEAD']),
        git(p, ['branch', '--format=%(refname:short)']),
      ])
      const local = list.code === 0
        ? list.out.split('\n').map((s) => s.trim()).filter(Boolean)
        : []
      return json(res, 200, { current: cur.out.trim(), local })
    }
    if (req.method === 'POST' && url.pathname === '/repos/checkout') {
      const { path: p, branch, create } = await readBody(req)
      if (!branch || !branch.trim()) return json(res, 400, { error: 'branch required' })
      const args = create ? ['checkout', '-b', branch] : ['checkout', branch]
      const r = await git(p, args)
      return json(res, 200, { ok: r.code === 0, branch, output: (r.out + r.err).trim().slice(0, 1500) })
    }

    return json(res, 404, { error: 'not found' })
  } catch (e) {
    return json(res, 500, { error: String(e.message || e) })
  }
})

// ---- embedded terminal (/pty) — real interactive `claude` TUI over a WebSocket ----
// Powers brainstorm's claude-cli mode: the browser (xterm.js) attaches to a PTY here so the
// full Claude Code TUI runs with no offloading. Deps (ws + node-pty) are lazy-loaded so the
// rest of the agent needs no npm install. Guarded: 127.0.0.1 bind + localhost-origin allowlist.
let _wss = null
let _pty = null
async function getWss() {
  if (_wss) return _wss
  const { WebSocketServer } = await import('ws')
  _wss = new WebSocketServer({ noServer: true })
  return _wss
}
async function getPty() {
  if (_pty) return _pty
  const m = await import('node-pty')
  _pty = m.default ?? m
  return _pty
}

// Only allow the local DevLoom UI (or same-host tools) to open a terminal — blocks a random
// website from driving your shell (CSWSH). Combined with the 127.0.0.1 bind below.
function allowedOrigin(origin) {
  if (!origin) return true // non-browser clients (curl/tests) send no Origin
  try {
    const h = new URL(origin).hostname
    return h === 'localhost' || h === '127.0.0.1' || h === '::1'
  } catch { return false }
}

function launchArgv(sessionId, resume, cwd) {
  // Build the claude command, then drop the user into a live shell (so the terminal survives
  // claude exiting — they can re-run, resume, or poke around). For a returning session we try
  // --resume but FALL BACK to --session-id with the same id if Claude has no transcript for it
  // yet (e.g. the first open never completed a turn) — otherwise `--resume` dead-ends with
  // "No conversation found with session ID".
  const sh = IS_WIN ? 'powershell.exe' : (process.env.SHELL || '/bin/bash')
  let claudeCmd
  if (!sessionId) {
    claudeCmd = 'claude'
  } else if (!resume) {
    claudeCmd = `claude --session-id ${sessionId}`
  } else if (IS_WIN) {
    claudeCmd = `claude --resume ${sessionId}; if ($LASTEXITCODE -ne 0) { claude --session-id ${sessionId} }`
  } else {
    claudeCmd = `claude --resume ${sessionId} || claude --session-id ${sessionId}`
  }
  // Explicitly cd into cwd first — the user's shell profile may change directory (PowerShell
  // often lands in the home dir), which would make claude open the WRONG workspace (and keep
  // re-prompting to trust it). -NoProfile also avoids that side effect on Windows.
  if (IS_WIN) {
    // PowerShell's Set-Location changes $PWD but NOT [Environment]::CurrentDirectory, which is
    // what native child processes (claude) inherit — so claude would otherwise open in the
    // process's start dir (home). Set both so claude's workspace is actually `cwd`.
    const q = cwd ? cwd.replace(/'/g, "''") : ''
    const cd = cwd ? `Set-Location -LiteralPath '${q}'; [Environment]::CurrentDirectory = '${q}'; ` : ''
    return [sh, ['-NoLogo', '-NoProfile', '-NoExit', '-Command', cd + claudeCmd]]
  }
  const cd = cwd ? `cd '${cwd.replace(/'/g, "'\\''")}' && ` : ''
  return [sh, ['-lc', `${cd}${claudeCmd}; exec ${sh}`]]
}

// Mark a folder as trusted in ~/.claude.json (same effect as answering Claude's "trust this
// folder?" prompt with Yes). Without this, an interactive claude blocks at the trust prompt —
// so a fresh terminal session never completes a turn, nothing is saved, and --resume later
// fails with "No conversation found". We only launch in folders the user chose (repo or the
// configured working dir), so trusting them is exactly what they'd do by hand.
function ensureTrusted(dir) {
  try {
    if (!dir) return
    const cfgPath = path.join(os.homedir(), '.claude.json')
    if (!fs.existsSync(cfgPath)) return
    const cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'))
    cfg.projects = cfg.projects || {}
    // Claude keys projects by forward-slash path with a lowercase drive letter on Windows.
    let key = dir.replace(/\\/g, '/')
    if (/^[A-Za-z]:/.test(key)) key = key[0].toLowerCase() + key.slice(1)
    const entry = cfg.projects[key] || {}
    if (entry.hasTrustDialogAccepted === true) return // already trusted — no write
    entry.hasTrustDialogAccepted = true
    cfg.projects[key] = entry
    const tmp = cfgPath + '.devloom.tmp'
    fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2))
    fs.renameSync(tmp, cfgPath)
    console.log('pty: pre-trusted folder', key)
  } catch (e) {
    console.log('pty: could not pre-trust folder:', e.message)
  }
}

async function startPty(ws, url) {
  let pty
  try { pty = await getPty() } catch (e) {
    try { ws.send(`\r\n[terminal unavailable — run \`npm install\` in agent/ (${e.message})]\r\n`); ws.close() } catch {}
    return
  }
  const q = url.searchParams
  const wanted = q.get('cwd') || ''
  const cwd = wanted && fs.existsSync(wanted) ? wanted : os.homedir()
  const sessionId = q.get('sessionId') || ''
  const resume = q.get('resume') === 'true'
  const cols = Math.max(1, Number(q.get('cols')) || 80)
  const rows = Math.max(1, Number(q.get('rows')) || 24)

  ensureTrusted(cwd) // skip Claude's "trust this folder?" prompt so the session can start

  const [cmd, args] = launchArgv(sessionId, resume, cwd)

  // The agent may itself have been launched from inside Claude Code (e.g. started by a
  // `claude` session). Its env then carries "child session" markers that turn OFF transcript
  // saving in the claude we spawn — which silently breaks --resume ("No conversation found").
  // Strip those markers + force persistence so this runs as a normal top-level session.
  const env = { ...process.env }
  for (const k of ['CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT', 'CLAUDE_CODE_SSE_PORT',
                   'CLAUDE_CODE_CHILD_SESSION', 'CLAUDE_CODE_SESSION_ID']) {
    delete env[k]
  }
  env.TERM = 'xterm-256color'
  env.CLAUDE_CODE_FORCE_SESSION_PERSISTENCE = '1'

  let term
  try {
    term = pty.spawn(cmd, args, {
      name: 'xterm-256color', cols, rows, cwd, env,
    })
  } catch (e) {
    try { ws.send(`\r\n[failed to start terminal: ${e.message}]\r\n`); ws.close() } catch {}
    return
  }

  term.onData((d) => { try { ws.send(d) } catch {} })
  term.onExit(({ exitCode }) => { try { ws.send(`\r\n[terminal exited (${exitCode})]\r\n`); ws.close() } catch {} })

  ws.on('message', (raw) => {
    let msg
    try { msg = JSON.parse(raw.toString()) } catch { return }
    if (msg.type === 'input' && typeof msg.data === 'string') term.write(msg.data)
    else if (msg.type === 'resize') { try { term.resize(Math.max(1, msg.cols | 0), Math.max(1, msg.rows | 0)) } catch {} }
  })
  ws.on('close', () => { try { term.kill() } catch {} })
  console.log(`pty: ${cmd} (${resume ? 'resume' : 'session'} ${sessionId || 'none'}) in ${cwd}`)
}

server.on('upgrade', async (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`)
  if (url.pathname !== '/pty') { socket.destroy(); return }
  if (!allowedOrigin(req.headers.origin)) {
    socket.write('HTTP/1.1 403 Forbidden\r\n\r\n'); socket.destroy(); return
  }
  let wss
  try { wss = await getWss() } catch (e) {
    socket.write('HTTP/1.1 501 Not Implemented\r\n\r\nterminal deps missing: run `npm install` in agent/\r\n')
    socket.destroy(); return
  }
  wss.handleUpgrade(req, socket, head, (ws) => startPty(ws, url))
})

server.listen(PORT, HOST, () => {
  console.log(`DevLoom host agent listening on http://${HOST}:${PORT}`)
  console.log('Endpoints: GET /health, POST /claude, WS /pty (terminal), repos endpoints')
})
