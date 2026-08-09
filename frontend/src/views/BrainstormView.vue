<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import type { BrainstormData } from '../types'
import { fetchBrainstorm, sendBrainstorm } from '../api'
import SourceChip from '../components/SourceChip.vue'
import BoundaryToken from '../components/BoundaryToken.vue'
import LoomLoader from '../components/LoomLoader.vue'

const thinkingSteps = [
  'reading your sources…',
  'weaving a response…',
  'checking assumptions…',
]

const data = ref<BrainstormData | null>(null)
const loading = ref(true)
const draft = ref('')
const sending = ref(false)
const chatEl = ref<HTMLElement | null>(null)

onMounted(async () => {
  data.value = await fetchBrainstorm()
  loading.value = false
})

async function scrollToEnd() {
  await nextTick()
  if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
}

async function send() {
  const text = draft.value.trim()
  if (!text || sending.value || !data.value) return
  const session = data.value.active
  // Capture the prior conversation BEFORE adding this turn, so the model has context.
  const history = session.messages.map((m) => ({ role: m.role, text: m.text }))
  session.messages.push({ role: 'you', text })
  draft.value = ''
  sending.value = true
  await scrollToEnd()
  try {
    const sourceIds = session.inContext.map((s) => s.id)
    const reply = await sendBrainstorm(text, sourceIds, history)
    session.messages.push(reply)
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
</script>

<template>
  <div v-if="loading" class="loadwrap"><LoomLoader label="loading session…" /></div>
  <div v-else-if="data" class="brain">
    <!-- sessions -->
    <aside class="sess">
      <div class="nb">+ New session</div>
      <div v-for="s in data.sessions" :key="s.id" class="s" :class="{ on: s.id === data.active.id }">
        {{ s.title }}
      </div>
      <div class="spring"></div>
      <div class="vis mono">visibility: ● personal ○ workspace</div>
    </aside>

    <!-- conversation -->
    <main class="chat">
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
      <div v-for="s in data.active.inContext" :key="s.id" class="trow">
        <SourceChip :ref-item="s" :show-boundary="true" />
        <span class="x" aria-hidden="true">✕</span>
      </div>
      <div class="add"><span class="chip">+ Add source</span></div>
      <div class="lab mono">Model</div>
      <div class="select mono">{{ data.active.model }} ▾</div>
      <BoundaryToken class="bt" :boundary="data.active.boundary" />
    </aside>
  </div>
</template>

<style scoped>
.empty { color: var(--faint-text); padding: 24px; }
.loadwrap { display: flex; justify-content: center; align-items: center; height: 100%; }
.think-loom { align-items: flex-start; }
.brain { display: grid; grid-template-columns: 182px 1fr 250px; height: 100%; }
.sess { border-right: 1px solid var(--line); padding: 14px 12px; background: var(--rail-bg); display: flex; flex-direction: column; }
.nb { font-size: 13px; color: var(--warp-hi); padding: 6px 10px; margin-bottom: 6px; }
.s { padding: 8px 10px; border-radius: 8px; color: var(--dim); font-size: 13px; }
.s.on { background: var(--warp-weft); color: var(--ink); }
.spring { margin-top: auto; }
.vis { font-size: 12px; color: var(--faint-text); }
.chat { display: flex; flex-direction: column; padding: 16px 18px; min-height: 0; }
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
.trow .x { margin-left: auto; color: var(--faint-text); }
.add { margin: 8px 0 18px; }
.select { border: 1px solid var(--line); border-radius: 6px; padding: 6px 9px; background: var(--chip-bg); color: var(--ink); font-size: 12px; }
.bt { margin-top: 12px; }
</style>
