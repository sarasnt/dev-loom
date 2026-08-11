<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'
import type { BrowseResult } from '../types'
import type { InstalledModel } from '../types'
import { fetchSettings, saveTerminalWorkdir, browseFs, fetchInstalledModels, removeModel, addRepoDir, removeRepoDir, saveNotificationSettings, testNotification, saveGitSettings } from '../api'
import type { NotifySettings } from '../types'
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

// ---- local models (Ollama) ----
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

const workdir = ref('')
const repoDirs = ref<string[]>([])
const newDir = ref('')
const loading = ref(true)
const saving = ref(false)
const flash = ref('')
// The browse modal is shared: `browseFor` says whether picking a folder sets the terminal
// workdir or adds a repository directory.
const browseFor = ref<'workdir' | 'repodir'>('workdir')
const browse = ref<{ open: boolean; data: BrowseResult | null; loading: boolean }>({
  open: false,
  data: null,
  loading: false,
})

// Desktop-notification preferences (spec: Daily Briefing + notifications).
const notify = ref<NotifySettings>({
  enabled: false, digestTime: '08:30', quietStart: '22:00', quietEnd: '08:00',
  urgentCi: true, urgentReview: true, prWaitHours: 24,
})
const notifyFlash = ref('')
const testing = ref(false)

// Push protection guardrail (global default; overridable per repo on the Repos page).
const pushMode = ref<'off' | 'all' | 'protected'>('protected')
const protectedPatterns = ref('main, master, develop, dev')
const gitFlash = ref('')

onMounted(async () => {
  try {
    const s = await fetchSettings()
    workdir.value = s.terminalWorkdir
    repoDirs.value = s.repoDirs ?? []
    if (s.notify) notify.value = s.notify
    pushMode.value = s.gitPushProtection ?? 'protected'
    protectedPatterns.value = s.gitProtectedPatterns ?? 'main, master, develop, dev'
  } catch { /* ignore */ }
  loadInstalled()
  loading.value = false
})

async function saveGit() {
  gitFlash.value = ''
  try {
    const s = await saveGitSettings(pushMode.value, protectedPatterns.value)
    pushMode.value = s.gitPushProtection; protectedPatterns.value = s.gitProtectedPatterns
    gitFlash.value = 'Saved.'
  } catch { gitFlash.value = 'Could not save.' }
}

async function saveNotify() {
  notifyFlash.value = ''
  try { notify.value = (await saveNotificationSettings(notify.value)).notify; notifyFlash.value = 'Saved.' }
  catch { notifyFlash.value = 'Could not save.' }
}
async function runTest() {
  if (testing.value) return
  testing.value = true; notifyFlash.value = ''
  try {
    const r = await testNotification()
    notifyFlash.value = r.ok ? 'Sent — check your desktop.' : `Failed: ${r.error ?? 'is the host agent running?'}`
  } catch {
    notifyFlash.value = 'Failed — is the host agent running?'
  } finally { testing.value = false }
}

async function addDir(path: string) {
  const p = (path || '').trim()
  if (!p || saving.value) return
  saving.value = true; flash.value = ''
  try { repoDirs.value = (await addRepoDir(p)).repoDirs; newDir.value = ''; flash.value = 'Directory added — use Sync on the Repos page to import repos.' }
  catch { flash.value = 'Could not add directory.' }
  finally { saving.value = false }
}
async function removeDir(path: string) {
  saving.value = true; flash.value = ''
  try { repoDirs.value = (await removeRepoDir(path)).repoDirs }
  catch { flash.value = 'Could not remove directory.' }
  finally { saving.value = false }
}
onBeforeUnmount(() => pullEs?.close())

async function save() {
  if (saving.value) return
  saving.value = true
  flash.value = ''
  try {
    workdir.value = (await saveTerminalWorkdir(workdir.value.trim())).terminalWorkdir
    flash.value = workdir.value ? 'Saved.' : 'Cleared — terminals will open in your home folder.'
  } catch {
    flash.value = 'Could not save.'
  } finally {
    saving.value = false
  }
}

async function openBrowse(target: 'workdir' | 'repodir' = 'workdir') {
  browseFor.value = target
  browse.value.open = true
  await navigate((target === 'workdir' ? workdir.value : newDir.value) || '')
}
async function navigate(p: string) {
  browse.value.loading = true
  try { browse.value.data = await browseFs(p) }
  catch { flash.value = 'Browse failed — is the host agent running?'; browse.value.open = false }
  finally { browse.value.loading = false }
}
function useFolder() {
  if (!browse.value.data) return
  const picked = browse.value.data.path
  browse.value.open = false
  if (browseFor.value === 'repodir') addDir(picked)
  else workdir.value = picked
}
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head"><h1>General</h1></div>
    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else>
      <div v-if="flash" class="flash mono">{{ flash }}</div>

      <section class="block">
        <div class="lab mono">Claude Code terminal — working directory</div>
        <p class="prose">
          Where a <b>claude-cli</b> terminal opens when the brainstorm isn't tied to a repo.
          Pick a folder you trust so Claude Code stops asking on every session. Leave blank to
          use your home folder. (Repo-scoped brainstorms still open in their repo.)
        </p>
        <div class="row">
          <input
            v-model="workdir"
            class="in mono"
            type="text"
            placeholder="e.g. C:\Users\you\Documents\projects"
            :disabled="saving"
            @keydown.enter="save"
          />
          <button class="btn" :disabled="saving" @click="openBrowse('workdir')">Browse…</button>
          <button class="btn pri" :disabled="saving" @click="save">Save</button>
        </div>
      </section>

      <section class="block">
        <div class="lab mono">Repository directories</div>
        <p class="prose">
          Parent folders DevLoom scans when you click <b>Sync</b> on the Repositories page. Any
          subfolder containing a <code>.git</code> is added automatically — drop new clones into
          one of these and a Sync brings them in. (You can still add repos or folders manually.)
        </p>
        <div class="mlist">
          <div v-for="d in repoDirs" :key="d" class="mrow mono">
            <span class="mn">{{ d }}</span>
            <button class="btn ghost mrm" :disabled="saving" @click="removeDir(d)">Remove</button>
          </div>
          <div v-if="!repoDirs.length" class="empty mono">no directories configured</div>
        </div>
        <div class="row">
          <input
            v-model="newDir"
            class="in mono"
            type="text"
            placeholder="e.g. C:\Users\you\Documents\projects"
            :disabled="saving"
            @keydown.enter="addDir(newDir)"
          />
          <button class="btn" :disabled="saving" @click="openBrowse('repodir')">Browse…</button>
          <button class="btn pri" :disabled="saving || !newDir.trim()" @click="addDir(newDir)">Add</button>
        </div>
      </section>

      <section class="block">
        <div class="lab mono">Notifications</div>
        <p class="prose">
          A morning briefing plus real-time alerts for urgent events, delivered as native desktop
          notifications through the host agent. Nothing is sent during quiet hours (overnight urgent
          items fold into the morning digest). Requires the host agent running.
        </p>
        <div v-if="notifyFlash" class="flash mono">{{ notifyFlash }}</div>
        <label class="nrow">
          <input type="checkbox" v-model="notify.enabled" @change="saveNotify" />
          <span>Enable desktop notifications</span>
        </label>
        <div class="ngrid">
          <label class="nfield"><span class="mono fld">Morning digest</span>
            <input type="time" v-model="notify.digestTime" class="in mono nin" @change="saveNotify" /></label>
          <label class="nfield"><span class="mono fld">Quiet from</span>
            <input type="time" v-model="notify.quietStart" class="in mono nin" @change="saveNotify" /></label>
          <label class="nfield"><span class="mono fld">Quiet until</span>
            <input type="time" v-model="notify.quietEnd" class="in mono nin" @change="saveNotify" /></label>
          <label class="nfield"><span class="mono fld">PR-wait alert (hours)</span>
            <input type="number" min="1" v-model.number="notify.prWaitHours" class="in mono nin" @change="saveNotify" /></label>
        </div>
        <div class="nchecks">
          <label class="nrow"><input type="checkbox" v-model="notify.urgentCi" @change="saveNotify" /><span>Alert on new CI failures</span></label>
          <label class="nrow"><input type="checkbox" v-model="notify.urgentReview" @change="saveNotify" /><span>Alert when a review is requested of you</span></label>
        </div>
        <div class="row">
          <button class="btn" :disabled="testing" @click="runTest">{{ testing ? 'Sending…' : 'Test notification' }}</button>
          <span class="idnote">Pops a desktop toast via the host agent (must be running).</span>
        </div>
      </section>

      <section class="block">
        <div class="lab mono">Push protection</div>
        <p class="prose">
          Guards every push DevLoom makes (and agent runs). This is the global default — you can
          override it per repository on the Repositories page.
        </p>
        <div v-if="gitFlash" class="flash mono">{{ gitFlash }}</div>
        <div class="nchecks">
          <label class="nrow"><input type="radio" value="off" v-model="pushMode" @change="saveGit" /><span>Off<span class="mono hint"> — allow all pushes</span></span></label>
          <label class="nrow"><input type="radio" value="protected" v-model="pushMode" @change="saveGit" /><span>Protected branches<span class="mono hint"> — block pushes to matching branches</span></span></label>
          <label class="nrow"><input type="radio" value="all" v-model="pushMode" @change="saveGit" /><span>Block all<span class="mono hint"> — no pushes from DevLoom</span></span></label>
        </div>
        <div v-if="pushMode === 'protected'" class="row">
          <span class="mono fld">Protected (regex, comma-sep)</span>
          <input v-model="protectedPatterns" class="in mono" placeholder="main, master, develop, dev" @keydown.enter="saveGit" />
          <button class="btn pri" @click="saveGit">Save</button>
        </div>
      </section>

      <section class="block">
        <div class="lab mono">Local models (Ollama)</div>
        <p class="prose">
          Install or remove local models. Your set is remembered and re-pulled on startup, so it
          survives a fresh volume. Lean pick: <code>qwen2.5-coder:7b</code>.
        </p>
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
            class="in mono"
            list="ollama-suggested"
            placeholder="e.g. qwen3-coder:30b"
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
      </section>

      <section class="block">
        <div class="lab mono">Builds — default analysis model</div>
        <p class="prose">
          The model the Build-failure screen opens with. You can still pick a different one there
          for a single re-run without changing this default. (Local + your keyed remote models;
          Claude Code CLI is Brainstorm-only.)
        </p>
        <div class="row">
          <span class="mono fld">Default model</span>
          <ModelSelect screen="builds" />
        </div>
      </section>

      <section class="block">
        <div class="lab mono">Brainstorm — switching to/from Claude CLI</div>
        <p class="prose">
          Claude Interactive CLI runs outside DevLoom's boundaries, so switching a session
          to or from it can't carry the conversation across. Choose what happens when you switch:
        </p>
        <div class="row">
          <span class="mono fld">Switching <b>to</b> Claude CLI</span>
          <select class="in narrow mono" :value="brainstormSwitch.toCli" @change="store.setBrainstormSwitch('toCli', ($event.target as HTMLSelectElement).value as any)">
            <option v-for="o in switchOpts" :key="o.v" :value="o.v">{{ o.label }}</option>
          </select>
        </div>
        <div class="row">
          <span class="mono fld">Switching <b>from</b> Claude CLI</span>
          <select class="in narrow mono" :value="brainstormSwitch.fromCli" @change="store.setBrainstormSwitch('fromCli', ($event.target as HTMLSelectElement).value as any)">
            <option v-for="o in switchOpts" :key="o.v" :value="o.v">{{ o.label }}</option>
          </select>
        </div>
      </section>
    </template>

    <!-- folder browser modal -->
    <div v-if="browse.open" class="modal" @click.self="browse.open = false">
      <div class="picker">
        <div class="pkhead">
          <span class="mono pkpath">{{ browse.data?.path || '…' }}</span>
          <button class="btn ghost" @click="browse.open = false">✕</button>
        </div>
        <div class="pklist">
          <button v-if="browse.data?.parent" class="pkrow up" @click="navigate(browse.data.parent!)">⤴ ..</button>
          <button v-for="d in browse.data?.drives ?? []" :key="d.path" class="pkrow" @click="navigate(d.path)">🖴 {{ d.name }}</button>
          <button v-for="d in browse.data?.dirs ?? []" :key="d.path" class="pkrow" @click="navigate(d.path)">📁 {{ d.name }}</button>
          <div v-if="browse.loading" class="mono clean">…</div>
        </div>
        <div class="pkfoot">
          <span class="mono hint">Open a folder, then use it as the terminal's start directory.</span>
          <button class="btn pri" :disabled="!browse.data?.path" @click="useFolder">Use this folder</button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head h1 { font-size: 22px; margin-bottom: 18px; }
.empty { color: var(--faint-text); padding: 20px 0; }
.flash { font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.block { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 16px; margin-bottom: 14px; }
.lab { font-size: 10px; letter-spacing: 0.14em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 10px; }
.prose { color: var(--dim); font-size: 13px; margin: 0 0 12px; max-width: 72ch; line-height: 1.5; }
.row { display: flex; align-items: center; gap: 10px; }
.in { flex: 1; max-width: 460px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px; color: var(--ink); font-size: 12px; }
.in:focus { outline: none; border-color: var(--warp); }
.in.narrow { flex: 0 0 auto; max-width: 220px; cursor: pointer; }
.fld { font-size: 12px; color: var(--dim); min-width: 190px; }
.nrow { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink); margin: 6px 0; cursor: pointer; }
.ngrid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px 20px; margin: 10px 0; }
.nfield { display: flex; align-items: center; gap: 10px; }
.nfield .fld { min-width: 130px; }
.nin { max-width: 140px; }
.nchecks { margin: 8px 0 12px; }
.prose code { font-family: var(--mono); font-size: 11.5px; background: var(--bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--warp-hi); }
.mlist { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
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
.btn { font-size: 13px; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; white-space: nowrap; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.modal { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
.picker { width: 560px; max-width: 92vw; max-height: 80vh; display: flex; flex-direction: column; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.pkhead { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.pkpath { flex: 1; font-size: 12px; color: var(--warp-hi); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pklist { flex: 1; overflow: auto; padding: 8px; }
.pkrow { display: block; width: 100%; text-align: left; background: transparent; border: 0; border-radius: 6px; padding: 7px 10px; color: var(--ink); font-size: 13px; cursor: pointer; }
.pkrow:hover { background: var(--nav-hover); }
.pkrow.up { color: var(--dim); }
.clean { color: var(--faint-text); padding: 8px 10px; }
.pkfoot { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-top: 1px solid var(--line); }
.pkfoot .hint { flex: 1; font-size: 11.5px; color: var(--faint-text); }
</style>
