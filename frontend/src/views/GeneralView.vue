<script setup lang="ts">
// Settings › General — machine/workflow preferences: terminal workdir, repo directories,
// notifications, push protection, Fleet defaults. (Model things live in Settings › Models.)
import { onMounted, ref } from 'vue'
import type { BrowseResult, NotifySettings } from '../types'
import { fetchSettings, saveTerminalWorkdir, browseFs, addRepoDir, removeRepoDir, saveNotificationSettings, testNotification, saveGitSettings, saveFleetSettings } from '../api'
import SettingsTabs from '../components/SettingsTabs.vue'

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

// Fleet: default isolation for background edit runs.
const worktreesDefault = ref(true)
const fleetFlash = ref('')

onMounted(async () => {
  try {
    const s = await fetchSettings()
    workdir.value = s.terminalWorkdir
    repoDirs.value = s.repoDirs ?? []
    if (s.notify) notify.value = s.notify
    pushMode.value = s.gitPushProtection ?? 'protected'
    protectedPatterns.value = s.gitProtectedPatterns ?? 'main, master, develop, dev'
    worktreesDefault.value = s.fleetWorktreesDefault ?? true
  } catch { /* ignore */ }
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

async function saveFleet() {
  fleetFlash.value = ''
  try {
    worktreesDefault.value = (await saveFleetSettings(worktreesDefault.value)).fleetWorktreesDefault
    fleetFlash.value = 'Saved.'
  } catch { fleetFlash.value = 'Could not save.' }
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
        <div class="lab mono">Fleet</div>
        <p class="prose">
          Background <b>edit</b> runs can execute in their own git worktree (branch
          <code>devloom/run-N</code>) so several agents can work one repo at once without touching
          your checkout. This sets the launch dialog's default; each run can still override it.
        </p>
        <div v-if="fleetFlash" class="flash mono">{{ fleetFlash }}</div>
        <label class="nrow">
          <input type="checkbox" v-model="worktreesDefault" @change="saveFleet" />
          <span>Isolate edit runs in a worktree by default</span>
        </label>
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
          <span class="mono hint">Open a folder, then use it.</span>
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
.fld { font-size: 12px; color: var(--dim); min-width: 190px; }
.nrow { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink); margin: 6px 0; cursor: pointer; }
.ngrid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px 20px; margin: 10px 0; }
.nfield { display: flex; align-items: center; gap: 10px; }
.nfield .fld { min-width: 130px; }
.nin { max-width: 140px; }
.nchecks { margin: 8px 0 12px; }
.hint { color: var(--faint-text); }
.idnote { font-size: 11px; color: var(--faint-text); }
.prose code { font-family: var(--mono); font-size: 11.5px; background: var(--bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--warp-hi); }
.mlist { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.mrow { display: flex; align-items: center; gap: 12px; font-size: 12.5px; border: 1px solid var(--line); border-radius: 8px; padding: 7px 12px; background: var(--bg); }
.mrow .mn { color: var(--ink); }
.mrow .mrm { padding: 3px 9px; margin-left: auto; }
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
