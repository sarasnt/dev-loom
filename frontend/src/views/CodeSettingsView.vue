<script setup lang="ts">
// Settings › Repos & runs — everything about how DevLoom touches your code: where it looks for
// repositories, what it is allowed to push, and how agent runs are isolated.
//
// Split out of General, which had accumulated these alongside notifications and backup. They were
// unrelated to each other and related to the Repos and Fleet screens, so they sit together now and
// the pile is smaller.
import { onMounted, ref, useTemplateRef } from 'vue'
import { fetchSettings, saveTerminalWorkdir, addRepoDir, removeRepoDir, saveGitSettings, saveFleetSettings } from '../api'
import SettingsTabs from '../components/SettingsTabs.vue'
import FolderPicker from '../components/FolderPicker.vue'

const workdir = ref('')
const repoDirs = ref<string[]>([])
const newDir = ref('')
const loading = ref(true)
const saving = ref(false)
const flash = ref('')

// Push protection guardrail (global default; overridable per repo on the Repos page).
const pushMode = ref<'off' | 'all' | 'protected'>('protected')
const protectedPatterns = ref('main, master, develop, dev')
const gitFlash = ref('')

// Fleet: default isolation for background edit runs.
const worktreesDefault = ref(true)
const fleetFlash = ref('')

const picker = useTemplateRef<InstanceType<typeof FolderPicker>>('picker')
// Which field a picked folder lands in — the picker itself doesn't care what it's picking for.
const pickFor = ref<'workdir' | 'repodir'>('workdir')

function browseFor(target: 'workdir' | 'repodir') {
  pickFor.value = target
  picker.value?.show(target === 'workdir' ? workdir.value : newDir.value)
}
function onPicked(path: string) {
  if (pickFor.value === 'repodir') addDir(path)
  else workdir.value = path
}

onMounted(async () => {
  try {
    const s = await fetchSettings()
    workdir.value = s.terminalWorkdir
    repoDirs.value = s.repoDirs ?? []
    pushMode.value = s.gitPushProtection ?? 'protected'
    protectedPatterns.value = s.gitProtectedPatterns ?? 'main, master, develop, dev'
    worktreesDefault.value = s.fleetWorktreesDefault ?? true
  } catch { /* leave defaults */ }
  loading.value = false
})

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

async function addDir(path: string) {
  const p = (path || '').trim()
  if (!p || saving.value) return
  saving.value = true; flash.value = ''
  try {
    repoDirs.value = (await addRepoDir(p)).repoDirs
    newDir.value = ''
    flash.value = 'Directory added — use Sync on the Repositories page to import repos.'
  } catch { flash.value = 'Could not add directory.' }
  finally { saving.value = false }
}
async function removeDir(path: string) {
  saving.value = true; flash.value = ''
  try { repoDirs.value = (await removeRepoDir(path)).repoDirs }
  catch { flash.value = 'Could not remove directory.' }
  finally { saving.value = false }
}

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
</script>

<template>
  <main class="main">
    <SettingsTabs />
    <div class="head"><h1>Repos &amp; runs</h1></div>
    <div v-if="loading" class="mono empty">loading…</div>
    <template v-else>
      <div v-if="flash" class="flash mono">{{ flash }}</div>

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
          <button class="btn" :disabled="saving" @click="browseFor('repodir')">Browse…</button>
          <button class="btn pri" :disabled="saving || !newDir.trim()" @click="addDir(newDir)">Add</button>
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
        <div class="lab mono">Agent runs</div>
        <p class="prose">
          Background <b>edit</b> runs can execute in their own git worktree (branch
          <code>devloom/run-N</code>) so several agents can work one repo at once without touching
          your checkout. This sets the launch dialog's default; each run can still override it.
          Runs on a local model always isolate, whatever this says.
        </p>
        <div v-if="fleetFlash" class="flash mono">{{ fleetFlash }}</div>
        <label class="nrow">
          <input type="checkbox" v-model="worktreesDefault" @change="saveFleet" />
          <span>Isolate edit runs in a worktree by default</span>
        </label>
      </section>

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
          <button class="btn" :disabled="saving" @click="browseFor('workdir')">Browse…</button>
          <button class="btn pri" :disabled="saving" @click="save">Save</button>
        </div>
      </section>
    </template>

    <FolderPicker ref="picker" @picked="onPicked" @error="(m) => (flash = m)" />
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
.prose code { font-family: var(--mono); font-size: 11.5px; background: var(--bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--warp-hi); }
.row { display: flex; align-items: center; gap: 10px; }
.in { flex: 1; max-width: 460px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px; color: var(--ink); font-size: 12px; }
.in:focus { outline: none; border-color: var(--warp); }
.fld { font-size: 12px; color: var(--dim); min-width: 190px; }
.nrow { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink); margin: 6px 0; cursor: pointer; }
.nchecks { margin: 8px 0 12px; }
.hint { color: var(--faint-text); }
.mlist { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.mrow { display: flex; align-items: center; gap: 12px; font-size: 12.5px; border: 1px solid var(--line); border-radius: 8px; padding: 7px 12px; background: var(--bg); }
.mrow .mn { color: var(--ink); }
.mrow .mrm { padding: 3px 9px; margin-left: auto; }
.btn { font-size: 13px; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; white-space: nowrap; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
</style>
