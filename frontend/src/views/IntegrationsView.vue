<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useDashboardStore } from '../stores/dashboard'
import type { SourceType, SourceView } from '../types'
import {
  fetchSourceTypes,
  fetchSources,
  createSource,
  updateSource,
  deleteSourceInstance,
  testSourceConfig,
  syncSourceInstance,
} from '../api'
import SettingsTabs from '../components/SettingsTabs.vue'
import LoomLoader from '../components/LoomLoader.vue'

const store = useDashboardStore()
const types = ref<SourceType[]>([])
const sources = ref<SourceView[]>([])
const loading = ref(true)
const busy = ref('') // id (or 'new') currently working
const flash = ref('')

// Add/Edit form state
const form = ref<{
  open: boolean
  editingId: string | null
  type: string
  deployment: string
  name: string
  fields: Record<string, string>
  testMsg: string
} | null>(null)

onMounted(async () => {
  const [t, s] = await Promise.all([fetchSourceTypes(), fetchSources()])
  types.value = t
  sources.value = s
  loading.value = false
})

const currentType = computed(() => types.value.find((t) => t.type === form.value?.type))
const currentDeployment = computed(() =>
  currentType.value?.deployments.find((d) => d.id === form.value?.deployment),
)

async function reload() {
  sources.value = await fetchSources()
  await store.ensureLoaded() // rail badge / Today reflect changes
}

function openAdd() {
  const t = types.value[0]
  form.value = {
    open: true,
    editingId: null,
    type: t?.type ?? '',
    deployment: t?.deployments[0]?.id ?? '',
    name: '',
    fields: {},
    testMsg: '',
  }
}

function openEdit(s: SourceView) {
  form.value = {
    open: true,
    editingId: s.id,
    type: s.type,
    deployment: s.deployment,
    name: s.name,
    fields: s.baseUrl ? { baseUrl: s.baseUrl } : {},
    testMsg: '',
  }
}

function onTypeChange() {
  if (!form.value) return
  form.value.deployment = currentType.value?.deployments[0]?.id ?? ''
  form.value.fields = {}
}

function payload() {
  const f = form.value!
  return { type: f.type, deployment: f.deployment, name: f.name, fields: f.fields }
}

async function testForm() {
  if (!form.value) return
  busy.value = 'form'
  form.value.testMsg = 'testing…'
  try {
    const r = await testSourceConfig(payload())
    form.value.testMsg = r.ok ? '✓ credentials valid' : `✗ ${r.error ?? 'failed'}`
  } catch {
    form.value.testMsg = '✗ test failed'
  } finally {
    busy.value = ''
  }
}

async function saveForm() {
  if (!form.value || !form.value.name.trim() || busy.value) return
  busy.value = 'form'
  flash.value = ''
  try {
    if (form.value.editingId) {
      await updateSource(form.value.editingId, payload())
      flash.value = `Updated ${form.value.name}.`
    } else {
      await createSource(payload())
      flash.value = `Added ${form.value.name} — syncing…`
    }
    form.value = null
    await reload()
  } catch (e) {
    flash.value = `Could not save (is DEVLOOM_SECRET set?).`
  } finally {
    busy.value = ''
  }
}

async function resync(s: SourceView) {
  busy.value = s.id
  flash.value = ''
  try {
    const r = await syncSourceInstance(s.id)
    flash.value = `${s.name}: synced ${r.ingested} item${r.ingested === 1 ? '' : 's'}.`
    await reload()
  } finally {
    busy.value = ''
  }
}

async function disconnect(s: SourceView) {
  if (!confirm(`Disconnect ${s.name}? Removes it and its ${s.items} synced items (your source is untouched).`)) return
  busy.value = s.id
  try {
    await deleteSourceInstance(s.id)
    flash.value = `${s.name} disconnected.`
    await reload()
  } finally {
    busy.value = ''
  }
}

const dotClass = (s: SourceView) => (s.state === 'connected' ? 'healthy' : 'off')
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head">
      <h1>Sources</h1>
      <button class="btn pri" @click="openAdd">+ Add source</button>
    </div>
    <p class="sub">Connect Jira, GitHub, Notion and calendars — cloud or on-prem. Name each one; its items show &amp; filter by that name. Credentials are encrypted on your machine.</p>

    <div v-if="flash" class="flash mono">{{ flash }}</div>

    <!-- Add / Edit form -->
    <section v-if="form?.open" class="editor">
      <div class="erow">
        <label>Type</label>
        <select v-model="form.type" class="in" :disabled="!!form.editingId" @change="onTypeChange">
          <option v-for="t in types" :key="t.type" :value="t.type">{{ t.label }}</option>
        </select>
        <select v-if="currentType && currentType.deployments.length > 1" v-model="form.deployment" class="in" :disabled="!!form.editingId">
          <option v-for="d in currentType.deployments" :key="d.id" :value="d.id">{{ d.label }}</option>
        </select>
      </div>
      <div class="erow">
        <label>Name</label>
        <input v-model="form.name" class="in grow" placeholder="e.g. CSW Jira" />
      </div>
      <div v-for="f in currentDeployment?.fields ?? []" :key="f.key" class="erow">
        <label>{{ f.label }}</label>
        <input
          v-model="form.fields[f.key]"
          class="in grow"
          :type="f.secret ? 'password' : 'text'"
          :placeholder="f.secret && form.editingId ? '•••• (unchanged)' : f.placeholder"
          autocomplete="off"
        />
      </div>
      <div class="eacts">
        <button class="btn" :disabled="busy === 'form'" @click="testForm">Test</button>
        <span v-if="form.testMsg" class="mono tmsg">{{ form.testMsg }}</span>
        <span class="spacer"></span>
        <button class="btn ghost" @click="form = null">Cancel</button>
        <button class="btn pri" :disabled="busy === 'form' || !form.name.trim()" @click="saveForm">
          {{ form.editingId ? 'Save' : 'Add source' }}
        </button>
      </div>
    </section>

    <div v-if="loading" class="loadwrap"><LoomLoader label="loading sources…" /></div>
    <div v-else-if="!sources.length && !form?.open" class="mono empty">No sources yet — add one to start syncing.</div>

    <template v-else>
      <section v-for="s in sources" :key="s.id" class="prov">
        <div class="ph">
          <span class="dot" :class="dotClass(s)" aria-hidden="true"></span>
          <h3>{{ s.name }}</h3>
          <span class="tlabel mono">{{ s.typeLabel }} · {{ s.deployment }}</span>
          <span v-if="busy === s.id" class="pstate mono">◐ working…</span>
          <span v-else-if="s.detail" class="tag mono" :class="{ ok: s.state === 'connected' }">{{ s.detail }}</span>
        </div>
        <div class="acts">
          <button class="btn" :disabled="busy === s.id" @click="resync(s)">Re-sync</button>
          <button class="btn" :disabled="busy === s.id" @click="openEdit(s)">Edit</button>
          <button class="btn ghost" :disabled="busy === s.id" @click="disconnect(s)">Disconnect</button>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.head h1 { font-size: 22px; }
.sub { color: var(--faint-text); font-size: 12.5px; margin: 0 0 16px; max-width: 70ch; }
.empty { color: var(--faint-text); padding: 20px 0; }
.loadwrap { display: flex; justify-content: center; padding: 40px 0; }
.flash { font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.editor { border: 1px solid var(--warp); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 16px; }
.erow { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.erow label { width: 130px; color: var(--dim); font-size: 12.5px; }
.in { background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; color: var(--ink); font-size: 13px; }
.in.grow { flex: 1; max-width: 460px; }
.in:focus { outline: none; border-color: var(--warp); }
.eacts { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.eacts .spacer { flex: 1; }
.tmsg { font-size: 12px; color: var(--dim); }
.prov { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 14px 16px; margin-bottom: 12px; }
.ph { display: flex; align-items: center; gap: 10px; }
.ph h3 { font-size: 15px; }
.tlabel { color: var(--faint-text); font-size: 11px; }
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.healthy { background: var(--healthy); }
.dot.off { background: var(--faint); }
.pstate { margin-left: auto; color: var(--warp-hi); font-size: 12px; }
.tag { margin-left: auto; font-size: 11px; color: var(--faint-text); }
.tag.ok { color: var(--healthy); }
.acts { display: flex; gap: 8px; margin-top: 12px; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
</style>
