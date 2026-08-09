#!/usr/bin/env node
// DevLoom host agent — a tiny local bridge the (containerised) backend calls via
// host.docker.internal to reach things a Linux container can't: your logged-in `claude`
// CLI (subscription), git, and gh/glab. Cross-platform: Windows, WSL, Debian.
//
//   node agent/devloom-agent.mjs           # listens on 127.0.0.1:8765
//   DEVLOOM_AGENT_PORT=9000 node agent/devloom-agent.mjs
//
// No dependencies — Node built-ins only. Prereqs for the Claude bridge: the `claude` CLI
// installed and logged in (claude 2.x). Repos features also use `git` and `gh`/`glab`.

import http from 'node:http'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

const PORT = Number(process.env.DEVLOOM_AGENT_PORT || 8765)
const HOST = process.env.DEVLOOM_AGENT_HOST || '127.0.0.1'
const IS_WIN = process.platform === 'win32'

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
async function claude(system, prompt) {
  const args = ['-p', '--output-format', 'json']
  if (system) args.push('--append-system-prompt', system)
  const r = await run('claude', args, { input: prompt, timeoutMs: 240000 })
  if (r.code !== 0) {
    throw new Error(`claude exited ${r.code}: ${(r.err || r.out).slice(0, 300)}`)
  }
  // `--output-format json` → an object with a `result` string (the assistant text).
  try {
    const parsed = JSON.parse(r.out)
    const text = parsed.result ?? parsed.text ?? ''
    return { text: String(text), model: parsed.model || 'claude-code' }
  } catch {
    return { text: r.out.trim(), model: 'claude-code' } // fall back to raw output
  }
}

// ---- git / repositories ----
function git(cwd, args) {
  return run('git', args, { cwd, timeoutMs: 120000 })
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
  const [branch, remote, dirty, upstream] = await Promise.all([
    git(dir, ['rev-parse', '--abbrev-ref', 'HEAD']),
    git(dir, ['remote', 'get-url', 'origin']),
    git(dir, ['status', '--porcelain']),
    git(dir, ['rev-list', '--left-right', '--count', '@{u}...HEAD']),
  ])
  const remoteUrl = remote.code === 0 ? remote.out.trim() : ''
  let behind = 0, ahead = 0
  if (upstream.code === 0) {
    const m = upstream.out.trim().split(/\s+/)
    behind = Number(m[0] || 0); ahead = Number(m[1] || 0)
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
    ahead, behind,
    user: cfg,
  }
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
    if (req.method === 'POST' && url.pathname === '/claude') {
      const body = await readBody(req)
      if (!body.prompt) return json(res, 400, { error: 'prompt required' })
      const out = await claude(body.system, body.prompt)
      return json(res, 200, out)
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
      const { path: p } = await readBody(req)
      const r = await git(p, ['push'])
      return json(res, 200, { ok: r.code === 0, output: (r.out + r.err).trim().slice(0, 2000) })
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

    return json(res, 404, { error: 'not found' })
  } catch (e) {
    return json(res, 500, { error: String(e.message || e) })
  }
})

server.listen(PORT, HOST, () => {
  console.log(`DevLoom host agent listening on http://${HOST}:${PORT}`)
  console.log('Endpoints: GET /health, POST /claude  (repos endpoints coming next)')
})
