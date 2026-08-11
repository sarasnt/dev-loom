<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import type { BrowseResult } from '../types'
import { fetchSettings, saveTerminalWorkdir, browseFs } from '../api'
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

const workdir = ref('')
const loading = ref(true)
const saving = ref(false)
const flash = ref('')
const browse = ref<{ open: boolean; data: BrowseResult | null; loading: boolean }>({
  open: false,
  data: null,
  loading: false,
})

onMounted(async () => {
  try { workdir.value = (await fetchSettings()).terminalWorkdir } catch { /* ignore */ }
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

async function openBrowse() {
  browse.value.open = true
  await navigate(workdir.value || '')
}
async function navigate(p: string) {
  browse.value.loading = true
  try { browse.value.data = await browseFs(p) }
  catch { flash.value = 'Browse failed — is the host agent running?'; browse.value.open = false }
  finally { browse.value.loading = false }
}
function useFolder() {
  if (!browse.value.data) return
  workdir.value = browse.value.data.path
  browse.value.open = false
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
          <button class="btn" :disabled="saving" @click="openBrowse">Browse…</button>
          <button class="btn pri" :disabled="saving" @click="save">Save</button>
        </div>
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
