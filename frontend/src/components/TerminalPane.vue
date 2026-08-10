<script setup lang="ts">
// Embedded interactive Claude Code terminal (brainstorm's claude-cli mode). Opens a PTY on
// the host agent over a WebSocket and renders the real `claude` TUI with xterm.js — nothing
// is offloaded, so slash commands, permission prompts and plan mode all work. The session id
// is shared with the claude-code chat, so you can `claude --resume <id>` in your own terminal.
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { openBrainstormTerminal } from '../api'

const props = defineProps<{ sessionId: string }>()

const host = ref<HTMLElement | null>(null)
const status = ref<'connecting' | 'open' | 'closed' | 'error'>('connecting')
const resumeCmd = ref('')
const copied = ref(false)
const cwd = ref<string | null>(null)
const term = shallowRef<Terminal | null>(null)
let ws: WebSocket | null = null
let fit: FitAddon | null = null
let ro: ResizeObserver | null = null

// The host agent runs on the local machine at :8765 (browser reaches it directly).
const AGENT_PORT = 8765

async function connect() {
  const t = new Terminal({
    fontSize: 13,
    fontFamily: 'var(--mono, ui-monospace, monospace)',
    cursorBlink: true,
    scrollback: 5000,
    theme: { background: '#0e0f13', foreground: '#d7dae0', cursor: '#8ab4f8' },
  })
  fit = new FitAddon()
  t.loadAddon(fit)
  t.open(host.value!)
  term.value = t
  doFit() // initial size once the element is in the DOM

  let info
  try {
    info = await openBrainstormTerminal(props.sessionId)
  } catch {
    status.value = 'error'
    t.writeln('\x1b[31mCould not reach the backend to open a terminal session.\x1b[0m')
    return
  }
  cwd.value = info.cwd
  resumeCmd.value = `claude --resume ${info.sessionId}`

  const params = new URLSearchParams({
    cwd: info.cwd ?? '',
    sessionId: info.sessionId,
    resume: String(info.resume),
    cols: String(t.cols),
    rows: String(t.rows),
  })
  const url = `ws://${location.hostname}:${AGENT_PORT}/pty?${params.toString()}`
  ws = new WebSocket(url)

  ws.onopen = () => {
    status.value = 'open'
    t.onData((d) => ws?.readyState === WebSocket.OPEN && ws.send(JSON.stringify({ type: 'input', data: d })))
    t.focus()
    doFit()
    window.setTimeout(doFit, 150) // re-fit once claude's TUI has drawn
  }
  ws.onmessage = (ev) => t.write(typeof ev.data === 'string' ? ev.data : '')
  ws.onclose = () => { if (status.value !== 'error') status.value = 'closed' }
  ws.onerror = () => {
    status.value = 'error'
    t.writeln('\r\n\x1b[31mCould not connect to the host agent on :' + AGENT_PORT + '.\x1b[0m')
    t.writeln('\x1b[90mStart it with:  node agent/devloom-agent.mjs  (and `npm install` in agent/ first).\x1b[0m')
  }

  // Defer fit out of the ResizeObserver callback (rAF) — fitting synchronously inside it is
  // dropped by the browser's RO-loop guard, which is why resizing "did nothing".
  ro = new ResizeObserver(() => requestAnimationFrame(doFit))
  ro.observe(host.value!)
  window.addEventListener('resize', onWinResize) // belt-and-suspenders for window resizes
}

function onWinResize() {
  requestAnimationFrame(doFit)
}

// Resize the terminal to its container, then tell the PTY the new dimensions.
function doFit() {
  const t = term.value
  if (!t || !fit) return
  try { fit.fit() } catch { /* element not measurable yet */ }
  sendResize()
}

function sendResize() {
  const t = term.value
  if (!t || ws?.readyState !== WebSocket.OPEN) return
  ws.send(JSON.stringify({ type: 'resize', cols: t.cols, rows: t.rows }))
}

async function copyResume() {
  if (!resumeCmd.value) return
  try {
    await navigator.clipboard?.writeText(resumeCmd.value)
  } catch {
    /* clipboard blocked — the command is shown inline to copy manually */
  }
  copied.value = true
  window.setTimeout(() => (copied.value = false), 1600)
}

onMounted(connect)
onBeforeUnmount(() => {
  ro?.disconnect()
  window.removeEventListener('resize', onWinResize)
  ws?.close()
  term.value?.dispose()
})
</script>

<template>
  <div class="termwrap">
    <div class="termbar mono">
      <span class="dot" :class="status"></span>
      <span class="st">{{ status === 'open' ? 'claude · interactive' : status }}</span>
      <span v-if="cwd" class="cwd" :title="cwd">⑂ {{ cwd }}</span>
      <span class="spacer"></span>
      <span class="resumelbl">resume in your terminal:</span>
      <code v-if="resumeCmd" class="resumecmd" :title="resumeCmd">{{ resumeCmd }}</code>
      <button v-if="resumeCmd" class="resume" @click="copyResume">
        {{ copied ? '✓ copied' : '⧉ copy' }}
      </button>
    </div>
    <div ref="host" class="term"></div>
  </div>
</template>

<style scoped>
.termwrap { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.termbar {
  display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--dim);
  padding: 6px 10px; border: 1px solid var(--line); border-bottom: 0;
  border-radius: 8px 8px 0 0; background: var(--surface);
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--faint-text); }
.dot.open { background: var(--healthy); }
.dot.connecting { background: var(--warp-hi); }
.dot.closed { background: var(--faint-text); }
.dot.error { background: var(--failed, #d66); }
.st { text-transform: uppercase; letter-spacing: 0.08em; }
.cwd { color: var(--warp-hi); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 46ch; }
.spacer { margin-left: auto; }
.resumelbl { color: var(--faint-text); }
.resumecmd {
  font-size: 11px; color: var(--warp-hi); background: var(--bg); border: 1px solid var(--line);
  border-radius: 5px; padding: 2px 7px; max-width: 34ch; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; user-select: all;
}
.resume {
  border: 1px solid var(--line); background: var(--chip-bg); color: var(--dim);
  border-radius: 5px; padding: 3px 8px; font-size: 11px; cursor: pointer; white-space: nowrap;
}
.resume:hover { color: var(--ink); border-color: var(--warp); }
.term {
  flex: 1; min-height: 0; background: #0e0f13; border: 1px solid var(--line);
  border-radius: 0 0 8px 8px; padding: 8px; overflow: hidden;
}
</style>
