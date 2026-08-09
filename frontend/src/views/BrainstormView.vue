<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import type { BrainstormData } from '../types'
import {
  fetchBrainstorm,
  fetchBrainstormSession,
  createBrainstormSession,
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

const thinkingSteps = [
  'reading your sources…',
  'weaving a response…',
  'checking assumptions…',
]

const store = useDashboardStore()
const { activeModel } = storeToRefs(store)

const data = ref<BrainstormData | null>(null)
const loading = ref(true)
const draft = ref('')
const sending = ref(false)
const switching = ref(false)
const chatEl = ref<HTMLElement | null>(null)

onMounted(async () => {
  await store.ensureLoaded() // populate the model list for the in-panel dropdown
  data.value = await fetchBrainstorm()
  loading.value = false
  await scrollToEnd()
})

async function scrollToEnd() {
  await nextTick()
  if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
}

async function selectSession(id: string) {
  if (!data.value || switching.value || id === data.value.active.id) return
  switching.value = true
  try {
    data.value.active = await fetchBrainstormSession(id)
  } finally {
    switching.value = false
    await scrollToEnd()
  }
}

async function newSession() {
  if (!data.value) return
  const s = await createBrainstormSession()
  data.value.sessions.unshift({ id: s.id, title: s.title })
  data.value.active = s
  draft.value = ''
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
  session.messages.push({ role: 'you', text })
  draft.value = ''
  sending.value = true
  await scrollToEnd()
  try {
    const sourceIds = session.inContext.map((s) => s.id)
    const reply = await sendBrainstorm(session.id, text, sourceIds)
    session.messages.push(reply)
    syncActiveTitle()
  } catch {
    session.messages.push({
      role: 'ai',
      text: 'Could not reach the model. Is the backend (and Ollama) running?',
      hypothesis: false,
    })
  } finally {
    sending.value = false
    await scrollToEnd()
  }
}

// The most recent AI reply used a model; if you've since switched, offer to redo it.
const canRedo = computed(() => {
  if (!data.value || sending.value) return false
  const lastAi = [...data.value.active.messages].reverse().find((m) => m.role === 'ai' && m.model)
  return (
    !!lastAi &&
    !!lastAi.model &&
    lastAi.model !== 'stub-deterministic' &&
    !!activeModel.value &&
    lastAi.model !== activeModel.value
  )
})

// ---- context (editable) ----
const addingCtx = ref(false)
const workItems = ref<WorkRow[]>([])
const pickWork = ref('')
const freeInput = ref('')

async function openAddContext() {
  addingCtx.value = !addingCtx.value
  if (addingCtx.value && !workItems.value.length) {
    try { workItems.value = await fetchWork() } catch { workItems.value = [] }
  }
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
        <button class="s" @click="selectSession(s.id)">{{ s.title }}</button>
        <button class="sx" aria-label="Delete session" title="Delete session" @click.stop="removeSession(s.id)">
          ✕
        </button>
      </div>
      <div class="spring"></div>
      <div class="vis mono">visibility: ● personal ○ workspace</div>
    </aside>

    <!-- conversation -->
    <main class="chat">
      <div v-if="data.active.repoPath" class="repobar mono">
        ⑂ Claude Code · iterating in <b>{{ data.active.repoPath }}</b> (read-only)
      </div>
      <div ref="chatEl" class="stream">
        <div v-for="(m, i) in data.active.messages" :key="i" class="msg" :class="m.role">
          <div class="who mono">{{ m.role === 'you' ? 'you' : `DevLoom · ${m.model ?? 'local'}` }}</div>
          <div class="bub">
            {{ m.text }}
            <span v-if="m.hypothesis" class="reason-mark" aria-label="model reasoning">reasoning°</span>
          </div>
          <div v-if="m.sources?.length" class="thread mono">
            └ thread → <SourceChip v-for="s in m.sources" :key="s.id" :ref-item="s" />
          </div>
        </div>
        <div v-if="sending" class="msg ai">
          <div class="who mono">DevLoom · {{ data.active.model }}</div>
          <div class="bub thinking">
            <LoomLoader class="think-loom" :steps="thinkingSteps" size="sm" />
          </div>
        </div>
      </div>

      <div v-if="canRedo" class="redo mono">
        <span>Model switched to <b>{{ activeModel }}</b> since the last reply.</span>
        <button class="redo-btn" @click="redoLast">Redo with {{ activeModel }} ↻</button>
      </div>

      <div class="composer">
        <input
          v-model="draft"
          class="composer-input"
          type="text"
          placeholder="Type a message…"
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
      <div class="lab mono">Model</div>
      <div class="select mono">{{ activeModel || data.active.model }} · <span class="railhint">choose in the left rail</span></div>
      <BoundaryToken class="bt" :boundary="data.active.boundary" />
    </aside>
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
.sx {
  border: 0; background: transparent; color: var(--faint-text); cursor: pointer;
  font-size: 11px; padding: 4px 8px; opacity: 0; border-radius: 6px;
}
.srow:hover .sx { opacity: 1; }
.sx:hover { color: var(--failed); }
.spring { margin-top: auto; }
.vis { font-size: 12px; color: var(--faint-text); }
.chat { display: flex; flex-direction: column; padding: 16px 18px; min-height: 0; }
.repobar { font-size: 12px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 7px 12px; margin-bottom: 12px; }
.stream { flex: 1; overflow: auto; min-height: 0; }
.msg { margin-bottom: 16px; max-width: 58ch; }
.thinking { color: var(--faint-text); }
.thinking::after { content: ''; animation: none; }
.who { font-size: 11px; color: var(--faint-text); margin-bottom: 4px; }
.bub { font-size: 14px; color: var(--ink); line-height: 1.55; }
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
