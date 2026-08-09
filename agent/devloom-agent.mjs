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
    return json(res, 404, { error: 'not found' })
  } catch (e) {
    return json(res, 500, { error: String(e.message || e) })
  }
})

server.listen(PORT, HOST, () => {
  console.log(`DevLoom host agent listening on http://${HOST}:${PORT}`)
  console.log('Endpoints: GET /health, POST /claude  (repos endpoints coming next)')
})
