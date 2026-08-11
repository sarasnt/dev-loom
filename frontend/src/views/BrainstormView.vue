<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { BrainstormData } from '../types'
import { useRoute } from 'vue-router'
import {
  fetchBrainstorm,
  fetchBrainstormSession,
  createBrainstormSession,
  renameBrainstormSession,
  deleteBrainstormSession,
  sendBrainstorm,
  addBrainstormContext,
  removeBrainstormContext,
  fetchWork,
} from '../api'
import type { WorkRow } from '../types'
import { useDashboardStore } from '../stores/dashboard'
import SourceChip from '../components/SourceChip.vue'
import BoundaryToken from '../components/BoundaryToken.vue'
import LoomLoader from '../components/LoomLoader.vue'
import TerminalPane from '../components/TerminalPane.vue'
import ModelSelect from '../components/ModelSelect.vue'
import { renderMarkdown } from '../utils/markdown'
import { isRemoteModel } from '../utils/models'
import type { Boundary } from '../types'

const thinkingSteps = [
  'reading your sources…',
  'weaving a response…',
  'checking assumptions…',
]

const store = useDashboardStore()

const data = ref<BrainstormData | null>(null)
const loading = ref(true)
const draft = ref('')
const sending = ref(false)
const switching = ref(false)
const chatEl = ref<HTMLElement | null>(null)
const streamingText = ref('') // live-streamed reply while sending
const sessionModel = ref('') // this session's model (seeded from the Brainstorm screen default)
const API = (import.meta.env.VITE_API_BASE as string) ?? '/api/v1'

// Boundary-crossing confirmation (chat ⇄ claude-cli). See requestModel().
const boundary = ref<{ open: boolean; dir: 'toCli' | 'fromCli'; target: string; remember: boolean }>(
  { open: false, dir: 'toCli', target: '', remember: false },
)

// Read the SSE stream from POST /brainstorm/messages/stream, calling onDelta per chunk.
async function streamReply(
  sessionId: string,
  message: string,
  onDelta: (t: string) => void,
  onDone: (text: string, model: string) => void,
) {
  const resp = await fetch(`${API}/brainstorm/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, message, sourceIds: [], model: effectiveModel.value || undefined }),
  })
  if (!resp.ok || !resp.body) throw new Error(`stream ${resp.status}`)
  const reader = resp.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let event = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
      }
      const dataStr = dataLines.join('\n')
      if (!dataStr) continue
      let payload: Record<string, string>
      try { payload = JSON.parse(dataStr) } catch { continue }
      if (event === 'delta') onDelta(payload.t ?? '')
      else if (event === 'done') onDone(payload.text ?? '', payload.model ?? 'claude-code')
      else if (event === 'error') throw new Error(payload.error || 'stream error')
    }
  }
}

// This session's model. A claude-cli-locked session is always the terminal; otherwise it's the
// session's chosen model (seeded from the Brainstorm screen default). Model choice is bounded
// to Brainstorm — switching here never touches other screens.
const effectiveModel = computed(() =>
  data.value?.active.cliMode ? 'claude-cli' : (sessionModel.value || store.modelFor('brainstorm')),
)
const isCliMode = computed(() => effectiveModel.value === 'claude-cli')

// Boundary reflects the SELECTED model, shown right by the model selector (single source).
const modelBoundary = computed<Boundary>(() =>
  isRemoteModel(effectiveModel.value)
    ? { mode: 'remote', label: 'Leaves your machine' }
    : { mode: 'local', label: 'On your machine' },
)

// Previous claude-cli sessions (to resume from within interactive mode).
const cliSessions = computed(() =>
  (data.value?.sessions ?? []).filter((s) => s.cliMode && s.id !== data.value?.active.id),
)

// Seed the per-session model when a session loads, and reflect it on the rail boundary token.
function syncSessionModel() {
  const a = data.value?.active
  if (!a) return
  sessionModel.value = a.cliMode ? 'claude-cli' : store.modelFor('brainstorm')
  store.reflectModel(effectiveModel.value)
}

// Handle a model pick from the in-screen selector. Switching across the chat⇄claude-cli
// boundary would lose the session (its conversation lives elsewhere), so instead of converting
// in place we offer to open a NEW session — honoring the saved preference (Settings > General).
function requestModel(target: string) {
  const cur = effectiveModel.value
  const crossing = (cur === 'claude-cli') !== (target === 'claude-cli')
  if (!crossing) {
    // Same side (e.g. gpt-oss → claude-code chat): switch in place for this session.
    sessionModel.value = target
    store.setModelFor('brainstorm', target)
    return
  }
  const dir: 'toCli' | 'fromCli' = target === 'claude-cli' ? 'toCli' : 'fromCli'
  // Switching TO claude-cli on a session with no conversation yet loses nothing — just turn
  // this (empty) session into the terminal in place, no warning. terminalInfo persists cli_mode.
  if (dir === 'toCli' && (data.value?.active.messages?.length ?? 0) === 0) {
    sessionModel.value = 'claude-cli'
    store.reflectModel('claude-cli')
    return
  }
  const pref = store.brainstormSwitch[dir]
  if (pref === 'new') openNewWith(target)
  else if (pref === 'cancel') { /* keep current session/model */ }
  else boundary.value = { open: true, dir, target, remember: false }
}

async function openNewWith(model: string) {
  if (!data.value) return
  const repoPath = data.value.active.repoPath ?? undefined
  const title = model === 'claude-cli' ? 'Claude CLI session' : 'New brainstorm'
  const s = await createBrainstormSession(title, repoPath, model)
  data.value.sessions.unshift({ id: s.id, title: s.title, cliMode: s.cliMode })
  data.value.active = s
  store.setModelFor('brainstorm', model)
  sessionModel.value = model
  await scrollToEnd()
}

function boundaryYes() {
  const { dir, target, remember } = boundary.value
  if (remember) store.setBrainstormSwitch(dir, 'new')
  boundary.value.open = false
  openNewWith(target)
}
function boundaryNo() {
  const { dir, remember } = boundary.value
  if (remember) store.setBrainstormSwitch(dir, 'cancel')
  boundary.value.open = false // current session/model unchanged
}

// Resume a previous claude-cli session from within interactive mode.
function resumeCli(e: Event) {
  const id = (e.target as HTMLSelectElement).value
  if (id) selectSession(id)
  ;(e.target as HTMLSelectElement).value = ''
}

const route = useRoute()
const editingSession = ref<string | null>(null)
const editTitle = ref('')

onMounted(async () => {
  await store.ensureLoaded() // populate the model list for the in-panel dropdown
  data.value = await fetchBrainstorm()
  loading.value = false
  // Deep-link: /brainstorm?session=<id> opens a specific session (e.g. from a repo card).
  const want = route.query.session
  if (typeof want === 'string' && data.value && data.value.active.id !== want) {
    await selectSession(want)
  }
  syncSessionModel()
  await scrollToEnd()
})

function startRename(id: string, current: string) {
  editingSession.value = id
  editTitle.value = current
}
async function commitRename(id: string) {
  const t = editTitle.value.trim()
  editingSession.value = null
  if (!data.value || !t) return
  const updated = await renameBrainstormSession(id, t)
  const ref = data.value.sessions.find((s) => s.id === id)
  if (ref) ref.title = updated.title
  if (data.value.active.id === id) data.value.active.title = updated.title
}

async function scrollToEnd() {
  await nextTick()
  if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
}

async function selectSession(id: string) {
  if (!data.value || switching.value || id === data.value.active.id) return
  switching.value = true
  try {
    data.value.active = await fetchBrainstormSession(id)
    syncSessionModel()
  } finally {
    switching.value = false
    await scrollToEnd()
  }
}

async function newSession() {
  if (!data.value) return
  // A fresh session uses the Brainstorm screen's current model.
  const s = await createBrainstormSession(undefined, undefined, store.modelFor('brainstorm'))
  data.value.sessions.unshift({ id: s.id, title: s.title, cliMode: s.cliMode })
  data.value.active = s
  draft.value = ''
  syncSessionModel()
  await scrollToEnd()
}

async function removeSession(id: string) {
  if (!data.value) return
  if (!confirm('Delete this brainstorm session and its history?')) return
  await deleteBrainstormSession(id)
  data.value.sessions = data.value.sessions.filter((s) => s.id !== id)
  if (data.value.active.id === id) {
    if (data.value.sessions.length) {
      data.value.active = await fetchBrainstormSession(data.value.sessions[0].id)
    } else {
      // none left → reload; the backend hands back a fresh empty session
      data.value = await fetchBrainstorm()
    }
    syncSessionModel()
    await scrollToEnd()
  }
}


// Once the backend titles a fresh session from its first message, mirror it in the sidebar.
function syncActiveTitle() {
  if (!data.value) return
  const a = data.value.active
  const first = a.messages.find((m) => m.role === 'you')
  if (first && a.title === 'New brainstorm') {
    a.title = first.text.length > 48 ? first.text.slice(0, 48) + '…' : first.text
    const ref = data.value.sessions.find((s) => s.id === a.id)
    if (ref) ref.title = a.title
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || sending.value || !data.value) return
  const session = data.value.active

  // In-composer /model — same rules as the selector (crossing the claude-cli boundary prompts).
  if (text.startsWith('/model')) {
    const m = text.slice('/model'.length).trim()
    draft.value = ''
    if (m) requestModel(m)
    await scrollToEnd()
    return
  }

  session.messages.push({ role: 'you', text })
  draft.value = ''
  sending.value = true
  streamingText.value = ''
  await scrollToEnd()
  try {
    await streamReply(
      session.id,
      text,
      (delta) => { streamingText.value += delta; scrollToEnd() },
      (finalText, model) => {
        session.messages.push({ role: 'ai', text: finalText, model, hypothesis: true })
      },
    )
    syncActiveTitle()
  } catch {
    session.messages.push({
      role: 'ai',
      text: streamingText.value || 'Could not reach the model. Is the backend (and, for claude-code, the host agent) running?',
      hypothesis: false,
    })
  } finally {
    streamingText.value = ''
    sending.value = false
    await scrollToEnd()
  }
}

// The most recent AI reply used a model; if you've since switched, offer to redo it.
const canRedo = computed(() => {
  if (!data.value || sending.value || isCliMode.value) return false
  const lastAi = [...data.value.active.messages].reverse().find((m) => m.role === 'ai' && m.model)
  return (
    !!lastAi &&
    !!lastAi.model &&
    lastAi.model !== 'stub-deterministic' &&
    !!effectiveModel.value &&
    lastAi.model !== effectiveModel.value
  )
})

// ---- context (editable) ----
const addingCtx = ref(false)
const workItems = ref<WorkRow[]>([])
const pickWork = ref('')
const freeInput = ref('')

async function openAddContext() {
  // Load work items BEFORE showing the panel, so the dropdown is never opened empty (race).
  if (!addingCtx.value && !workItems.value.length) {
    try { workItems.value = await fetchWork() } catch { workItems.value = [] }
  }
  addingCtx.value = !addingCtx.value
}
async function addContext(kind: string, ref: string, label: string) {
  if (!data.value || !label) return
  data.value.active = await addBrainstormContext(data.value.active.id, { kind, ref, label })
}
async function addPickedWork() {
  const w = workItems.value.find((x) => x.id === pickWork.value)
  if (!w) return
  await addContext('workitem', w.id, `${w.title} (${w.source})`)
  pickWork.value = ''
}
async function addFree() {
  const v = freeInput.value.trim()
  if (!v) return
  // a path-looking value → file, else a free note
  await addContext(/[\\/.]/.test(v) ? 'file' : 'note', v, v)
  freeInput.value = ''
}
async function removeContext(ctxId: string) {
  if (!data.value) return
  data.value.active = await removeBrainstormContext(data.value.active.id, ctxId)
}
const ctxGlyph = (kind: string) =>
  kind === 'repo' ? '⑂' : kind === 'workitem' ? '◆' : kind === 'file' ? '▤' : '•'

async function redoLast() {
  if (!data.value || sending.value) return
  const session = data.value.active
  const msgs = session.messages
  let i = msgs.length - 1
  while (i >= 0 && msgs[i].role !== 'you') i--
  if (i < 0) return
  const userText = msgs[i].text
  msgs.splice(i + 1) // drop the previous AI reply(ies) — we regenerate them
  sending.value = true
  await scrollToEnd()
  try {
    const sourceIds = session.inContext.map((s) => s.id)
    const reply = await sendBrainstorm(session.id, userText, sourceIds)
    session.messages.push(reply)
  } catch {
    session.messages.push({ role: 'ai', text: 'Could not reach the model.', hypothesis: false })
  } finally {
    sending.value = false
    await scrollToEnd()
  }
}
</script>

<template>
  <div v-if="loading" class="loadwrap"><LoomLoader label="loading session…" /></div>
  <div v-else-if="data" class="brain">
    <!-- sessions -->
    <aside class="sess">
      <button class="nb" @click="newSession">+ New session</button>
      <div
        v-for="s in data.sessions"
        :key="s.id"
        class="srow"
        :class="{ on: s.id === data.active.id }"
      >
        <input
          v-if="editingSession === s.id"
          v-model="editTitle"
          class="srename mono"
          @keydown.enter="commitRename(s.id)"
          @keydown.esc="editingSession = null"
          @blur="commitRename(s.id)"
        />
        <template v-else>
          <button class="s" :title="s.title" @click="selectSession(s.id)" @dblclick="startRename(s.id, s.title)">{{ s.title }}</button>
          <button class="sre" aria-label="Rename session" title="Rename" @click.stop="startRename(s.id, s.title)">✎</button>
          <button class="sx" aria-label="Delete session" title="Delete session" @click.stop="removeSession(s.id)">✕</button>
        </template>
      </div>
      <div class="spring"></div>
      <div class="vis mono">visibility: ● personal ○ workspace</div>
    </aside>

    <!-- conversation -->
    <main class="chat">
      <!-- claude-cli: full interactive Claude Code in an embedded terminal -->
      <template v-if="isCliMode">
        <div class="clibar mono">
          <span class="clitag">⌨ interactive · outside DevLoom boundaries</span>
          <select v-if="cliSessions.length" class="clisel" aria-label="Resume a CLI session" @change="resumeCli">
            <option value="">↻ resume another CLI session…</option>
            <option v-for="s in cliSessions" :key="s.id" :value="s.id">{{ s.title }}</option>
          </select>
        </div>
        <TerminalPane :key="data.active.id" :session-id="data.active.id" class="termpane" />
      </template>
      <template v-else>
      <div v-if="data.active.repoPath" class="repobar mono">
        ⑂ Claude Code · iterating in <b>{{ data.active.repoPath }}</b> (read-only)
      </div>
      <div ref="chatEl" class="stream">
        <div v-for="(m, i) in data.active.messages" :key="i" class="msg" :class="m.role">
          <div class="who mono">{{ m.role === 'you' ? 'you' : `DevLoom · ${m.model ?? 'local'}` }}</div>
          <div class="bub">
            <span v-if="m.role === 'you'">{{ m.text }}</span>
            <span v-else class="md" v-html="renderMarkdown(m.text)"></span>
            <span v-if="m.hypothesis" class="reason-mark" aria-label="model reasoning">reasoning°</span>
          </div>
          <div v-if="m.sources?.length" class="thread mono">
            └ thread → <SourceChip v-for="s in m.sources" :key="s.id" :ref-item="s" />
          </div>
        </div>
        <div v-if="sending" class="msg ai">
          <div class="who mono">DevLoom · {{ sessionModel || data.active.model }}</div>
          <div class="bub">
            <template v-if="streamingText"><span class="md" v-html="renderMarkdown(streamingText)"></span><span class="cursor" aria-hidden="true">▍</span></template>
            <LoomLoader v-else class="think-loom" :steps="thinkingSteps" size="sm" />
          </div>
        </div>
      </div>

      <div v-if="canRedo" class="redo mono">
        <span>Model switched to <b>{{ effectiveModel }}</b> since the last reply.</span>
        <button class="redo-btn" @click="redoLast">Redo with {{ effectiveModel }} ↻</button>
      </div>

      <div class="composer">
        <input
          v-model="draft"
          class="composer-input"
          type="text"
          placeholder="Type a message…  (/model <name> to switch)"
          :disabled="sending"
          @keydown.enter="send"
          aria-label="Message"
        />
        <button class="send" :disabled="sending || !draft.trim()" @click="send">Send ⏎</button>
      </div>
      <div class="saveas mono">
        Save as:
        <span class="chip">Note</span><span class="chip">Task</span>
        <span class="chip">Decision</span><span class="chip">Draft spec</span>
      </div>
      </template>
    </main>

    <!-- source tray -->
    <aside class="tray">
      <div class="lab mono">In context ({{ data.active.inContext.length }})</div>
      <div v-for="c in data.active.inContext" :key="c.id" class="trow">
        <span class="ctxglyph mono" aria-hidden="true">{{ ctxGlyph(c.kind) }}</span>
        <span class="ctxlabel" :title="c.ref || c.label">{{ c.label }}</span>
        <button v-if="!c.pinned" class="x" aria-label="Remove from context" @click="removeContext(c.id)">✕</button>
        <span v-else class="pin mono" title="Pinned">📌</span>
      </div>
      <div v-if="!data.active.inContext.length" class="mono ctxempty">nothing yet — add sources below</div>

      <div class="add">
        <button class="chip addbtn" @click="openAddContext">{{ addingCtx ? '− Close' : '+ Add source' }}</button>
      </div>
      <div v-if="addingCtx" class="addpanel">
        <select v-model="pickWork" class="ctxsel mono">
          <option value="">Work item (Jira / Notion / PR)…</option>
          <option v-for="w in workItems" :key="w.id" :value="w.id">{{ w.source }} · {{ w.title }}</option>
        </select>
        <button class="chip" :disabled="!pickWork" @click="addPickedWork">Add</button>
        <input v-model="freeInput" class="ctxin mono" placeholder="…or a file path / note" @keydown.enter="addFree" />
        <button class="chip" :disabled="!freeInput.trim()" @click="addFree">Add</button>
      </div>
      <div class="lab mono">Model <span class="hint">· this brainstorm</span></div>
      <ModelSelect
        screen="brainstorm"
        include-agent
        manual
        :model-value="effectiveModel"
        @change="requestModel"
      />
      <BoundaryToken class="bt" :boundary="modelBoundary" />
    </aside>

    <!-- boundary-crossing confirmation (chat ⇄ claude-cli) -->
    <div v-if="boundary.open" class="modal" @click.self="boundaryNo">
      <div class="dlg">
        <h3 class="dlgh">{{ boundary.dir === 'toCli' ? 'Switch to Claude Interactive CLI?' : 'Switch to a chat model?' }}</h3>
        <p class="dlgp" v-if="boundary.dir === 'toCli'">
          Claude Interactive CLI runs <b>outside DevLoom's boundaries</b>. Switching won't carry this
          conversation across — this session would be lost. Open a <b>new</b> brainstorm session using
          <code>claude-cli</code> instead?
        </p>
        <p class="dlgp" v-else>
          Claude Interactive CLI runs outside DevLoom's boundaries, so this terminal conversation
          won't transfer to <code>{{ boundary.target }}</code>. You can resume it later with
          <code v-if="data.active.claudeSessionId">claude --resume {{ data.active.claudeSessionId }}</code><code v-else>claude --resume</code>.
          Open a <b>new</b> brainstorm session using <code>{{ boundary.target }}</code> instead?
        </p>
        <label class="dlgremember mono">
          <input type="checkbox" v-model="boundary.remember" /> Remember my choice (change in Settings → General)
        </label>
        <div class="dlgbtns">
          <button class="btn ghost" @click="boundaryNo">No</button>
          <button class="btn pri" @click="boundaryYes">Yes, open a new session</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.empty { color: var(--faint-text); padding: 24px; }
.loadwrap { display: flex; justify-content: center; align-items: center; height: 100%; }
.think-loom { align-items: flex-start; }
.redo {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px;
  padding: 8px 12px; margin-bottom: 10px; font-size: 12px; color: var(--dim);
}
.redo-btn {
  margin-left: auto; font-size: 12px; font-weight: 600; border-radius: 6px; padding: 5px 11px;
  border: 1px solid var(--warp); background: var(--warp); color: var(--on-warp); cursor: pointer; white-space: nowrap;
}
.redo-btn:hover { background: var(--warp-hi); }
.brain { display: grid; grid-template-columns: 182px 1fr 250px; height: 100%; }
.sess { border-right: 1px solid var(--line); padding: 14px 12px; background: var(--rail-bg); display: flex; flex-direction: column; }
.nb {
  display: block; width: 100%; text-align: left; background: transparent; border: 0;
  font-size: 13px; color: var(--warp-hi); padding: 6px 10px; margin-bottom: 6px; cursor: pointer;
}
.nb:hover { color: var(--ink); }
.srow {
  display: flex; align-items: center; gap: 4px; border-radius: 8px;
}
.srow:hover { background: var(--nav-hover); }
.srow.on { background: var(--warp-weft); }
.s {
  flex: 1; min-width: 0; text-align: left; background: transparent; border: 0;
  padding: 8px 10px; color: var(--dim); font-size: 13px; cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.srow:hover .s, .srow.on .s { color: var(--ink); }
.sx, .sre {
  border: 0; background: transparent; color: var(--faint-text); cursor: pointer;
  font-size: 11px; padding: 4px 6px; opacity: 0; border-radius: 6px;
}
.srow:hover .sx, .srow:hover .sre { opacity: 1; }
.sx:hover { color: var(--failed); }
.sre:hover { color: var(--warp-hi); }
.srename {
  flex: 1; min-width: 0; background: var(--bg); border: 1px solid var(--warp);
  border-radius: 6px; padding: 6px 8px; color: var(--ink); font-size: 12.5px;
}
.srename:focus { outline: none; }
.spring { margin-top: auto; }
.vis { font-size: 12px; color: var(--faint-text); }
.chat { display: flex; flex-direction: column; padding: 16px 18px; min-height: 0; }
.termpane { flex: 1; min-height: 0; }
.clibar { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.clitag { font-size: 11px; color: var(--warp-hi); border: 1px solid var(--warp); border-radius: 6px; padding: 3px 8px; }
.clisel { margin-left: auto; background: var(--chip-bg); border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; color: var(--ink); font-size: 12px; cursor: pointer; }
.clisel:hover { border-color: var(--warp); }
.hint { text-transform: none; letter-spacing: 0; color: var(--faint-text); }
.modal { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
.dlg { width: 480px; max-width: 92vw; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; padding: 18px 20px; }
.dlgh { font-size: 16px; margin-bottom: 10px; }
.dlgp { font-size: 13px; color: var(--dim); line-height: 1.55; margin: 0 0 12px; }
.dlgp code { font-family: var(--mono); font-size: 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--warp-hi); }
.dlgremember { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--dim); margin-bottom: 14px; }
.dlgbtns { display: flex; justify-content: flex-end; gap: 10px; }
.btn { font-size: 13px; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn:hover { border-color: var(--warp); }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.repobar { font-size: 12px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 7px 12px; margin-bottom: 12px; }
.stream { flex: 1; overflow: auto; min-height: 0; }
.msg { margin-bottom: 16px; max-width: 58ch; }
.thinking { color: var(--faint-text); }
.thinking::after { content: ''; animation: none; }
.who { font-size: 11px; color: var(--faint-text); margin-bottom: 4px; }
.bub { font-size: 14px; color: var(--ink); line-height: 1.55; white-space: pre-wrap; }
/* rendered markdown — block layout, so no pre-wrap gaps */
.md { white-space: normal; display: block; }
.md :deep(.md-p) { margin: 0 0 8px; }
.md :deep(.md-p:last-child) { margin-bottom: 0; }
.md :deep(.md-h) { font-weight: 600; color: var(--ink); margin: 12px 0 6px; }
.md :deep(.md-h1) { font-size: 16px; }
.md :deep(.md-h2), .md :deep(.md-h3) { font-size: 14.5px; }
.md :deep(.md-ul), .md :deep(.md-ol) { margin: 4px 0 8px; padding-left: 20px; }
.md :deep(li) { margin: 2px 0; }
.md :deep(.md-code) { font-family: var(--mono); font-size: 12px; background: var(--chip-bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; }
.md :deep(.md-pre) { background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; overflow: auto; margin: 6px 0; }
.md :deep(.md-pre code) { font-family: var(--mono); font-size: 12px; white-space: pre; background: none; border: 0; padding: 0; }
.md :deep(a) { color: var(--warp-hi); text-decoration: underline; }
.md :deep(strong) { color: var(--ink); font-weight: 600; }
.cursor { color: var(--warp-hi); animation: blink 1s steps(2) infinite; }
@keyframes blink { 50% { opacity: 0; } }
@media (prefers-reduced-motion: reduce) { .cursor { animation: none; } }
.msg.ai .bub { color: var(--dim); }
.reason-mark { font-family: var(--mono); font-size: 10px; color: var(--warp); border: 1px solid var(--warp); border-radius: 4px; padding: 1px 5px; margin-left: 6px; }
.thread { font-size: 11px; color: var(--warp-hi); margin-top: 8px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.composer { border: 1px solid var(--line); border-radius: 10px; padding: 8px 8px 8px 13px; display: flex; align-items: center; gap: 10px; }
.composer:focus-within { border-color: var(--warp); }
.composer-input { flex: 1; background: transparent; border: 0; outline: none; color: var(--ink); font-family: var(--sans); font-size: 14px; }
.composer-input::placeholder { color: var(--faint-text); }
.send { border: 1px solid var(--warp); background: var(--warp); color: var(--on-warp); border-radius: 6px; padding: 6px 12px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.send:disabled { opacity: 0.45; cursor: default; }
.saveas { display: flex; gap: 8px; margin-top: 12px; font-size: 12px; color: var(--faint-text); align-items: center; }
.chip { font-size: 11px; color: var(--dim); border: 1px solid var(--line); border-radius: 5px; padding: 3px 7px; background: var(--chip-bg); }
.tray { border-left: 1px solid var(--line); padding: 16px; background: var(--surface); display: flex; flex-direction: column; }
.lab { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--faint-text); margin: 4px 0 10px; }
.trow { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
.trow .x { margin-left: auto; background: transparent; border: 0; color: var(--faint-text); cursor: pointer; font-size: 11px; }
.trow .x:hover { color: var(--failed); }
.ctxglyph { color: var(--warp-hi); width: 14px; text-align: center; }
.ctxlabel { font-size: 12.5px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pin { margin-left: auto; font-size: 10px; }
.ctxempty { color: var(--faint-text); font-size: 11.5px; padding: 4px 0; }
.add { margin: 8px 0 12px; }
.addbtn { background: transparent; border: 1px solid var(--line); cursor: pointer; }
.addbtn:hover { border-color: var(--warp); }
.addpanel { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
.ctxsel, .ctxin { width: 100%; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 5px 8px; color: var(--ink); font-size: 12px; }
.ctxsel:focus, .ctxin:focus { outline: none; border-color: var(--warp); }
.select { border: 1px solid var(--line); border-radius: 6px; padding: 6px 9px; background: var(--chip-bg); color: var(--ink); font-size: 12px; }
.mselect {
  width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 6px 9px;
  background: var(--chip-bg); color: var(--ink); font-size: 12px; cursor: pointer;
}
.mselect:hover { border-color: var(--warp); }
.mselect:focus { outline: none; border-color: var(--warp); }
.bt { margin-top: 12px; }
</style>
