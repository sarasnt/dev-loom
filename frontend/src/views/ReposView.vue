<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { RepoView, BrowseResult, RepoChanges } from '../types'
import {
  fetchRepos,
  scanRepoFolder,
  addRepoPath,
  removeRepo,
  setRepoIdentity,
  repoPull,
  repoPush,
  repoPr,
  browseFs,
  repoChanges,
  repoStage,
  repoUnstage,
  repoCommit,
} from '../api'

const repos = ref<RepoView[]>([])
const agentUp = ref(false)
const loading = ref(true)
const busy = ref('')
const flash = ref('')
const pathInput = ref('')
const editing = ref<string | null>(null)
const editName = ref('')
const editEmail = ref('')

// folder browser
const browse = ref<{ open: boolean; data: BrowseResult | null; loading: boolean }>({
  open: false, data: null, loading: false,
})
// per-repo changes panel
const openChanges = ref<string | null>(null)
const changes = ref<Record<string, RepoChanges>>({})
const commitMsg = ref<Record<string, string>>({})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const r = await fetchRepos()
    agentUp.value = r.agentUp
    repos.value = r.repos
  } finally {
    loading.value = false
  }
}

async function scan() {
  const root = pathInput.value.trim()
  if (!root || busy.value) return
  busy.value = 'add'; flash.value = ''
  try { repos.value = await scanRepoFolder(root); agentUp.value = true; flash.value = `Scanned ${root}.`; pathInput.value = '' }
  catch { flash.value = 'Scan failed — is the host agent running?' }
  finally { busy.value = '' }
}

async function addOne() {
  const p = pathInput.value.trim()
  if (!p || busy.value) return
  busy.value = 'add'; flash.value = ''
  try { repos.value = await addRepoPath(p); agentUp.value = true; flash.value = `Added ${p}.`; pathInput.value = '' }
  catch { flash.value = 'Not a git repo (or agent down).' }
  finally { busy.value = '' }
}

// ---- browse ----
async function openBrowse() {
  browse.value.open = true
  await navigate('')
}
async function navigate(p: string) {
  browse.value.loading = true
  try { browse.value.data = await browseFs(p) }
  catch { flash.value = 'Browse failed — is the host agent running?'; browse.value.open = false }
  finally { browse.value.loading = false }
}
async function useCurrentFolder() {
  if (!browse.value.data) return
  pathInput.value = browse.value.data.path
  browse.value.open = false
  await scan()
}
async function addCurrentRepo() {
  if (!browse.value.data) return
  pathInput.value = browse.value.data.path
  browse.value.open = false
  await addOne()
}

// ---- pull/push/pr ----
async function act(r: RepoView, fn: () => Promise<{ ok?: boolean; output?: string; url?: string; web?: boolean; error?: string }>, label: string) {
  busy.value = r.id; flash.value = ''
  try {
    const res = await fn()
    if (res.url && (res.web || res.ok)) window.open(res.url, '_blank', 'noopener')
    flash.value = `${r.name}: ${label} ${res.ok === false ? '✗ ' + (res.error || res.output || '') : '✓'}`
    await load()
  } catch { flash.value = `${r.name}: ${label} failed.` }
  finally { busy.value = '' }
}

// ---- git identity ----
function startEdit(r: RepoView) { editing.value = r.id; editName.value = r.userName; editEmail.value = r.userEmail }
async function saveEdit(r: RepoView) {
  busy.value = r.id
  try { await setRepoIdentity(r.id, editName.value, editEmail.value); editing.value = null; flash.value = `${r.name}: git identity updated.`; await load() }
  finally { busy.value = '' }
}

async function remove(r: RepoView) {
  if (!confirm(`Remove ${r.name} from DevLoom? (your files are untouched)`)) return
  busy.value = r.id
  try { await removeRepo(r.id); await load() } finally { busy.value = '' }
}

// ---- changes / staging / commit ----
async function toggleChanges(r: RepoView) {
  if (openChanges.value === r.id) { openChanges.value = null; return }
  openChanges.value = r.id
  await refreshChanges(r.id)
}
async function refreshChanges(id: string) {
  changes.value[id] = await repoChanges(id)
}
async function stage(r: RepoView, files: string[]) {
  busy.value = r.id
  try { await repoStage(r.id, files); await refreshChanges(r.id); await load() } finally { busy.value = '' }
}
async function unstage(r: RepoView, files: string[]) {
  busy.value = r.id
  try { await repoUnstage(r.id, files); await refreshChanges(r.id) } finally { busy.value = '' }
}
async function commit(r: RepoView) {
  const m = (commitMsg.value[r.id] || '').trim()
  if (!m || busy.value) return
  busy.value = r.id; flash.value = ''
  try {
    const res = await repoCommit(r.id, m)
    flash.value = `${r.name}: commit ${res.ok ? '✓' : '✗ ' + (res.output || '')}`
    if (res.ok) commitMsg.value[r.id] = ''
    await refreshChanges(r.id); await load()
  } finally { busy.value = '' }
}
function stagedCount(id: string) { return changes.value[id]?.staged.length ?? 0 }
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Repositories</h1>
      <span class="when mono">{{ repos.length }} repos · {{ agentUp ? 'agent connected' : 'agent offline' }}</span>
    </div>

    <div v-if="!agentUp" class="warnbar mono">
      Start the DevLoom host agent to manage local repos: <b>node agent/devloom-agent.mjs</b>
    </div>

    <div class="addbar">
      <input v-model="pathInput" class="in" placeholder="/path/to/projects (folder) or /path/to/a/repo" @keydown.enter="scan" />
      <button class="btn" :disabled="!agentUp" @click="openBrowse">Browse…</button>
      <button class="btn" :disabled="busy === 'add' || !pathInput.trim()" @click="scan">Scan folder</button>
      <button class="btn" :disabled="busy === 'add' || !pathInput.trim()" @click="addOne">Add repo</button>
    </div>

    <div v-if="flash" class="flash mono">{{ flash }}</div>

    <div v-if="loading" class="mono empty">loading…</div>
    <div v-else-if="!repos.length" class="mono empty">No repositories yet — browse or scan a folder.</div>

    <template v-else>
      <section v-for="r in repos" :key="r.id" class="repo">
        <div class="rh">
          <span class="hostpill mono" :class="r.host">{{ r.host }}</span>
          <h3>{{ r.name }}</h3>
          <span class="branch mono">⎇ {{ r.branch || '—' }}</span>
          <span v-if="r.dirty" class="tag mono dirty">uncommitted</span>
          <span v-if="r.ahead" class="tag mono">↑{{ r.ahead }}</span>
          <span v-if="r.behind" class="tag mono">↓{{ r.behind }}</span>
          <span class="path mono">{{ r.path }}</span>
        </div>

        <div v-if="editing === r.id" class="idedit">
          <input v-model="editName" class="in sm" placeholder="git user.name" />
          <input v-model="editEmail" class="in sm" placeholder="git user.email" />
          <button class="btn pri" :disabled="busy === r.id" @click="saveEdit(r)">Save</button>
          <button class="btn ghost" @click="editing = null">Cancel</button>
        </div>
        <div v-else class="idrow mono">identity: {{ r.userName || '—' }} &lt;{{ r.userEmail || '—' }}&gt;</div>

        <div class="acts">
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="toggleChanges(r)">
            {{ openChanges === r.id ? 'Hide changes' : 'Changes' }}
          </button>
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="act(r, () => repoPull(r.id), 'pull')">Pull</button>
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="act(r, () => repoPush(r.id), 'push')">Push</button>
          <button class="btn" :disabled="busy === r.id || !agentUp || r.host === 'none'" @click="act(r, () => repoPr(r.id), 'open PR/MR')">
            {{ r.host === 'gitlab' ? 'Open MR' : 'Open PR' }}
          </button>
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="startEdit(r)">Git identity</button>
          <button class="btn ghost" :disabled="busy === r.id" @click="remove(r)">Remove</button>
        </div>

        <!-- changes / staging / commit -->
        <div v-if="openChanges === r.id" class="changes">
          <div class="cgroup">
            <div class="clab mono">
              Staged ({{ changes[r.id]?.staged.length ?? 0 }})
              <button v-if="changes[r.id]?.staged.length" class="mini" @click="unstage(r, [])">unstage all</button>
            </div>
            <div v-for="f in changes[r.id]?.staged ?? []" :key="'s' + f.file" class="crow">
              <span class="cstat mono staged">{{ f.status }}</span>
              <span class="cfile mono">{{ f.file }}</span>
              <button class="mini" @click="unstage(r, [f.file])">unstage</button>
            </div>
          </div>
          <div class="cgroup">
            <div class="clab mono">
              Unstaged ({{ (changes[r.id]?.unstaged.length ?? 0) + (changes[r.id]?.untracked.length ?? 0) }})
              <button
                v-if="(changes[r.id]?.unstaged.length ?? 0) + (changes[r.id]?.untracked.length ?? 0)"
                class="mini"
                @click="stage(r, [])"
              >stage all</button>
            </div>
            <div v-for="f in changes[r.id]?.unstaged ?? []" :key="'u' + f.file" class="crow">
              <span class="cstat mono">{{ f.status }}</span>
              <span class="cfile mono">{{ f.file }}</span>
              <button class="mini" @click="stage(r, [f.file])">stage</button>
            </div>
            <div v-for="f in changes[r.id]?.untracked ?? []" :key="'n' + f.file" class="crow">
              <span class="cstat mono new">new</span>
              <span class="cfile mono">{{ f.file }}</span>
              <button class="mini" @click="stage(r, [f.file])">stage</button>
            </div>
            <div v-if="!changes[r.id]?.staged.length && !changes[r.id]?.unstaged.length && !changes[r.id]?.untracked.length" class="mono clean">working tree clean</div>
          </div>
          <div class="commitbar">
            <input v-model="commitMsg[r.id]" class="in" placeholder="Commit message…" @keydown.enter="commit(r)" />
            <button class="btn pri" :disabled="busy === r.id || !stagedCount(r.id) || !(commitMsg[r.id] || '').trim()" @click="commit(r)">
              Commit
            </button>
            <button class="btn" :disabled="busy === r.id" @click="act(r, () => repoPush(r.id), 'push')">Push</button>
          </div>
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
        <div class="pkbody">
          <button v-if="browse.data?.parent" class="pkrow up" @click="navigate(browse.data.parent!)">⤴ ..</button>
          <button v-for="d in browse.data?.drives ?? []" :key="d.path" class="pkrow" @click="navigate(d.path)">🖴 {{ d.name }}</button>
          <button v-for="d in browse.data?.dirs ?? []" :key="d.path" class="pkrow" @click="navigate(d.path)">
            <span>{{ d.repo ? '⑂' : '📁' }} {{ d.name }}</span>
          </button>
          <div v-if="browse.loading" class="mono clean">…</div>
        </div>
        <div class="pkfoot">
          <span class="mono hint">{{ browse.data?.isRepo ? 'This folder is a git repo.' : 'Scan adds every repo inside this folder.' }}</span>
          <span class="spacer"></span>
          <button v-if="browse.data?.isRepo" class="btn" @click="addCurrentRepo">Add this repo</button>
          <button class="btn pri" @click="useCurrentFolder">Scan this folder</button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16px; }
.head h1 { font-size: 26px; }
.when { font-size: 12px; color: var(--faint-text); }
.warnbar { font-size: 12.5px; color: var(--dim); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }
.addbar { display: flex; gap: 8px; margin-bottom: 14px; }
.in { flex: 1; max-width: 560px; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: 7px 11px; color: var(--ink); font-size: 13px; }
.in.sm { max-width: 240px; flex: none; }
.in:focus { outline: none; border-color: var(--warp); }
.flash { font-size: 12.5px; color: var(--warp-hi); border: 1px solid var(--warp); background: var(--warp-weft); border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; white-space: pre-wrap; }
.empty { color: var(--faint-text); padding: 20px 0; }
.repo { border: 1px solid var(--line); border-radius: var(--r-card); background: var(--surface); padding: 14px 16px; margin-bottom: 12px; }
.rh { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.rh h3 { font-size: 15px; }
.hostpill { font-size: 10px; text-transform: uppercase; border: 1px solid var(--line); border-radius: 5px; padding: 2px 6px; color: var(--dim); }
.hostpill.github { color: var(--ink); border-color: var(--line-hi); }
.hostpill.bitbucket { color: #4a9; border-color: #4a9; }
.hostpill.gitlab { color: #e24329; border-color: #e24329; }
.branch { font-size: 11px; color: var(--warp-hi); }
.path { font-size: 11px; color: var(--faint-text); margin-left: auto; }
.tag { font-size: 10px; border: 1px solid var(--line); border-radius: 5px; padding: 2px 6px; color: var(--dim); }
.tag.dirty { color: var(--warp-hi); border-color: var(--warp); }
.idrow { font-size: 11px; color: var(--faint-text); margin: 8px 0; }
.idedit { display: flex; gap: 8px; margin: 8px 0; align-items: center; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
/* changes */
.changes { margin-top: 12px; border-top: 1px solid var(--line); padding-top: 12px; }
.cgroup { margin-bottom: 10px; }
.clab { font-size: 11px; color: var(--faint-text); text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 6px; display: flex; gap: 10px; align-items: center; }
.crow { display: flex; align-items: center; gap: 10px; padding: 3px 0; font-size: 12.5px; }
.cstat { font-size: 10px; width: 68px; color: var(--dim); }
.cstat.staged { color: var(--healthy); }
.cstat.new { color: var(--warp-hi); }
.cfile { color: var(--ink); flex: 1; }
.clean { color: var(--faint-text); font-size: 12px; }
.mini { font-size: 11px; background: transparent; border: 1px solid var(--line); border-radius: 5px; padding: 1px 8px; color: var(--dim); cursor: pointer; }
.mini:hover { border-color: var(--warp); color: var(--ink); }
.commitbar { display: flex; gap: 8px; margin-top: 8px; }
/* picker modal */
.modal { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
.picker { width: 560px; max-height: 70vh; background: var(--surface); border: 1px solid var(--warp); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; }
.pkhead { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--line); }
.pkpath { flex: 1; font-size: 12px; color: var(--dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pkbody { overflow: auto; padding: 6px; flex: 1; }
.pkrow { display: block; width: 100%; text-align: left; background: transparent; border: 0; padding: 7px 10px; border-radius: 6px; color: var(--ink); font-size: 13px; cursor: pointer; }
.pkrow:hover { background: var(--nav-hover); }
.pkrow.up { color: var(--warp-hi); }
.pkfoot { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-top: 1px solid var(--line); }
.pkfoot .spacer { flex: 1; }
.pkfoot .hint { font-size: 11px; color: var(--faint-text); }
</style>
