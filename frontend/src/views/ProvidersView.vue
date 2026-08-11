<script setup lang="ts">
// Settings › Models — everything model-related in one place: providers (local + keyed),
// installing/removing local models, per-screen defaults, and CLI switching behavior.
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import type { ProvidersData, KeyProvider, InstalledModel } from '../types'
import { fetchProviders, setProviderKey, clearProviderKey, fetchInstalledModels, removeModel } from '../api'
import { useDashboardStore } from '../stores/dashboard'
import SettingsTabs from '../components/SettingsTabs.vue'
import ModelSelect from '../components/ModelSelect.vue'

const store = useDashboardStore()
const { brainstormSwitch } = storeToRefs(store)
const switchOpts = [
  { v: 'ask', label: 'Ask each time' },
  { v: 'new', label: 'Open a new session' },
  { v: 'cancel', label: "Don't switch" },
]

const data = ref<ProvidersData | null>(null)
const loading = ref(true)
const draft = ref<Record<string, string>>({ anthropic: '', openai: '' })
const busy = ref('')
const flash = ref('')

// ---- local model manager (Ollama) ----
const API = (import.meta.env.VITE_API_BASE as string) ?? '/api/v1'
const installed = ref<InstalledModel[]>([])
const pullName = ref('')
const pulling = ref('')
const pullPct = ref(0)
const pullStatus = ref('')
const pullErr = ref('')
// Curated suggestions (users can also type any Ollama tag).
const suggested = [
  'qwen2.5-coder:7b', 'qwen2.5-coder:32b', 'qwen3-coder:30b',
  'gpt-oss:20b', 'llama3.1:8b', 'deepseek-r1:8b',
]
let pullEs: EventSource | null = null

function gb(bytes: number) {
  return bytes ? (bytes / 1e9).toFixed(1) + ' GB' : ''
}
async function loadInstalled() {
  try { installed.value = await fetchInstalledModels() } catch { installed.value = [] }
}
async function removeInstalled(name: string) {
  if (!confirm(`Remove ${name} from Ollama?`)) return
  await removeModel(name)
  await loadInstalled()
  await store.ensureLoaded() // update model dropdowns
}
function installModel() {
  const name = pullName.value.trim()
  if (!name || pulling.value) return
  pulling.value = name
  pullPct.value = 0
  pullStatus.value = 'starting…'
  pullErr.value = ''
  pullEs?.close()
  pullEs = new EventSource(`${API}/models/pull/stream?name=${encodeURIComponent(name)}`)
  pullEs.addEventListener('progress', (e) => {
    try {
      const p = JSON.parse((e as MessageEvent).data)
      pullStatus.value = p.status || 'pulling…'
      if (p.total && p.completed) pullPct.value = Math.round((p.completed / p.total) * 100)
    } catch { /* ignore */ }
  })
  pullEs.addEventListener('done', async () => {
    pullEs?.close(); pullEs = null
    pulling.value = ''; pullName.value = ''; pullPct.value = 100
    await loadInstalled(); await store.ensureLoaded()
  })
  pullEs.addEventListener('error', (e) => {
    try { pullErr.value = JSON.parse((e as MessageEvent).data).error } catch { pullErr.value = 'pull failed (is the host reachable?)' }
    pullEs?.close(); pullEs = null; pulling.value = ''
  })
}

onMounted(async () => {
  data.value = await fetchProviders()
  loadInstalled()
  loading.value = false
})
onBeforeUnmount(() => pullEs?.close())

async function saveKey(provider: string) {
  const key = (draft.value[provider] || '').trim()
  if (!key || busy.value) return
  busy.value = provider
  flash.value = ''
  try {
    data.value = await setProviderKey(provider, key)
    draft.value[provider] = ''
    flash.value = `${provider} key saved (encrypted).`
    await store.ensureLoaded() // remote models now selectable
  } catch {
    flash.value = `Could not save the ${provider} key.`
  } finally {
    busy.value = ''
  }
}

async function removeKey(provider: string) {
  if (busy.value || !confirm(`Remove the ${provider} API key?`)) return
  busy.value = provider
  try {
    data.value = await clearProviderKey(provider)
    flash.value = `${provider} key removed.`
    await store.ensureLoaded()
  } finally {
    busy.value = ''
  }
}

const dollars = (c?: number | null) => (c == null ? '' : `$${(c / 100).toFixed(2)}`)
const usedPct = (p: KeyProvider) =>
  p.capCents ? Math.min(100, Math.round((100 * (p.usedCents ?? 0)) / p.capCents)) : 0
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head"><h1>Models</h1></div>
    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else-if="data">
      <div v-if="flash" class="flash mono">{{ flash }}</div>
      <div v-if="!data.canStoreKeys" class="warnbar mono">
        Set <b>DEVLOOM_SECRET</b> in backend/.env to store API keys encrypted in-app.
      </div>

      <!-- local -->
      <section class="prov">
        <div class="ph">
          <span class="dot healthy" aria-hidden="true"></span>
          <h3>Local · {{ data.local.name }}</h3>
          <span class="boundary local mono"><span aria-hidden="true">⌂</span> nothing leaves</span>
        </div>
        <div class="row">
          <span class="mono lbl">active</span>
          <span class="select mono">{{ data.local.active }}</span>
          <span v-if="data.local.loaded" class="tag ok mono">● loaded</span>
        </div>

        <!-- installed models manager -->
        <div class="mlist">
          <div v-for="m in installed" :key="m.name" class="mrow mono">
            <span class="mn">{{ m.name }}</span>
            <span class="msz">{{ gb(m.size) }}</span>
            <button class="btn ghost mrm" :disabled="pulling !== ''" @click="removeInstalled(m.name)">Remove</button>
          </div>
          <div v-if="!installed.length" class="empty mono">no local models installed</div>
        </div>
        <div class="row pullrow">
          <input
            v-model="pullName"
            class="keyin mono"
            list="ollama-suggested"
            placeholder="install: e.g. qwen3-coder:30b"
            :disabled="pulling !== ''"
            @keydown.enter="installModel"
          />
          <datalist id="ollama-suggested">
            <option v-for="s in suggested" :key="s" :value="s" />
          </datalist>
          <button class="btn pri" :disabled="pulling !== '' || !pullName.trim()" @click="installModel">
            {{ pulling ? 'Installing…' : 'Install' }}
          </button>
        </div>
        <div v-if="pulling" class="pullprog">
          <div class="pbar"><i :style="{ width: pullPct + '%' }"></i></div>
          <span class="mono pstat">{{ pullStatus }} · {{ pullPct }}%</span>
        </div>
        <div v-if="pullErr" class="mono pullerr">{{ pullErr }}</div>
        <div class="avail mono">Your set is remembered and re-pulled on startup. Lean pick: qwen2.5-coder:7b.</div>
      </section>

      <!-- keyed providers -->
      <section v-for="p in [data.anthropic, data.openai]" :key="p.key" class="prov" :class="{ muted: !p.hasKey }">
        <div class="ph">
          <span class="dot" :class="p.hasKey ? 'healthy' : 'off'" aria-hidden="true"></span>
          <h3>{{ p.name }} <span class="opt mono">· optional · your key</span></h3>
          <span class="boundary remote mono"><span aria-hidden="true">◉</span> {{ p.boundaryLabel }}</span>
        </div>

        <!-- has a key -->
        <template v-if="p.hasKey">
          <div class="row">
            <span class="mono lbl">key</span>
            <span class="mono keyhint">{{ p.maskedKey }}</span>
            <span v-if="p.valid" class="tag ok mono">✓ set</span>
            <button class="btn ghost" :disabled="busy === p.key" @click="removeKey(p.key)">Remove</button>
          </div>
          <div v-if="p.capCents" class="row">
            <span class="mono lbl">cap {{ dollars(p.capCents) }} · used</span>
            <span class="meter"><i :style="{ width: usedPct(p) + '%' }"></i></span>
            <span class="mono">{{ dollars(p.usedCents) }}</span>
          </div>
          <div v-if="p.models?.length" class="avail mono">models: {{ p.models.join(' · ') }}</div>
        </template>

        <!-- no key yet -->
        <template v-else>
          <div class="row">
            <input
              v-model="draft[p.key]"
              class="keyin mono"
              type="password"
              :placeholder="p.key === 'anthropic' ? 'sk-ant-…' : 'sk-…'"
              :disabled="!data.canStoreKeys || busy === p.key"
              autocomplete="off"
              @keydown.enter="saveKey(p.key)"
            />
            <button class="btn pri" :disabled="!data.canStoreKeys || busy === p.key || !draft[p.key]" @click="saveKey(p.key)">
              Save key
            </button>
          </div>
          <div class="avail mono">Adds {{ p.models?.join(' · ') }} to your model picker.</div>
        </template>
      </section>

      <!-- per-screen defaults -->
      <section class="prov">
        <div class="ph"><span class="dot healthy" aria-hidden="true"></span><h3>Defaults</h3></div>
        <div class="row">
          <span class="mono lbl">Builds — default analysis model</span>
          <ModelSelect screen="builds" />
        </div>
        <div class="avail mono">The Build-failure screen opens with this; you can still pick another per run.</div>
      </section>

      <!-- brainstorm ⇄ claude-cli switching -->
      <section class="prov">
        <div class="ph"><span class="dot healthy" aria-hidden="true"></span><h3>Switching to/from Claude CLI</h3></div>
        <div class="avail mono" style="margin-bottom: 10px">
          Claude Interactive CLI runs outside DevLoom's boundaries, so a switch can't carry the
          conversation across. Choose what happens:
        </div>
        <div class="row">
          <span class="mono lbl">Switching <b>to</b> Claude CLI</span>
          <select class="keyin narrow mono" :value="brainstormSwitch.toCli" @change="store.setBrainstormSwitch('toCli', ($event.target as HTMLSelectElement).value as any)">
            <option v-for="o in switchOpts" :key="o.v" :value="o.v">{{ o.label }}</option>
          </select>
        </div>
        <div class="row">
          <span class="mono lbl">Switching <b>from</b> Claude CLI</span>
          <select class="keyin narrow mono" :value="brainstormSwitch.fromCli" @change="store.setBrainstormSwitch('fromCli', ($event.target as HTMLSelectElement).value as any)">
            <option v-for="o in switchOpts" :key="o.v" :value="o.v">{{ o.label }}</option>
          </select>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head h1 { font-size: 22px; margin-bottom: 18px; }
.empty { color: var(--faint-text); padding: 20px 0; }
.flash { font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.warnbar { font-size: 12.5px; color: var(--dim); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.prov { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.prov.muted { opacity: 0.9; }
.ph { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.ph h3 { font-size: 15px; }
.opt { color: var(--faint-text); font-size: 11px; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.healthy { background: var(--healthy); }
.dot.off { background: var(--faint); }
.boundary { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; font-size: 11px; border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; }
.boundary.local { color: var(--dim); }
.boundary.local span { color: var(--healthy); }
.boundary.remote { border-color: var(--warp); color: var(--warp-hi); }
.boundary.remote span { color: var(--warp); }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.lbl { color: var(--dim); font-size: 12px; }
.keyhint { color: var(--warp-hi); }
.keyin { flex: 1; max-width: 360px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; color: var(--ink); font-size: 12px; }
.keyin:focus { outline: none; border-color: var(--warp); }
.keyin.narrow { flex: 0 0 auto; width: 200px; cursor: pointer; }
.select { border: 1px solid var(--line); border-radius: 6px; padding: 5px 9px; background: var(--chip-bg); color: var(--ink); font-size: 12px; }
.tag.ok { color: var(--healthy); border: 1px solid var(--healthy); border-radius: 5px; padding: 2px 6px; font-size: 10px; }
.avail { color: var(--faint-text); font-size: 11.5px; }
.meter { height: 8px; border-radius: 5px; background: var(--raised); overflow: hidden; width: 160px; }
.meter i { display: block; height: 100%; background: var(--warp); }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 5px 10px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
/* installed models */
.mlist { display: flex; flex-direction: column; gap: 6px; margin: 12px 0; }
.mrow { display: flex; align-items: center; gap: 12px; font-size: 12.5px; border: 1px solid var(--line); border-radius: 8px; padding: 7px 12px; background: var(--bg); }
.mrow .mn { color: var(--ink); }
.mrow .msz { color: var(--faint-text); margin-left: auto; }
.mrow .mrm { padding: 3px 9px; }
.pullrow { margin-top: 4px; }
.pullprog { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.pbar { flex: 1; height: 8px; border-radius: 5px; background: var(--raised); overflow: hidden; }
.pbar i { display: block; height: 100%; background: var(--warp); transition: width 0.3s ease; }
.pstat { font-size: 11px; color: var(--dim); white-space: nowrap; }
.pullerr { margin-top: 8px; color: var(--failed, #d66); font-size: 12px; }
</style>
