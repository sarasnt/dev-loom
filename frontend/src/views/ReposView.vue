<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
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
  repoBranches,
  repoCheckout,
  createBrainstormSession,
  fetchRepoSessions,
  setRepoLocalOnly,
} from '../api'
import { storeToRefs } from 'pinia'
import { useDashboardStore } from '../stores/dashboard'
import { isRemoteModel } from '../utils/models'

const router = useRouter()
const store = useDashboardStore()
const { models } = storeToRefs(store)

// Models offered to brainstorm a repo. A local-only repo may use ONLY local models (no
// claude-cli, no remote API) — otherwise the full list (local + remote + claude-cli).
function repoModels(r: RepoView): string[] {
  return r.localOnly ? models.value.filter((m) => !isRemoteModel(m)) : models.value
}
async function toggleLocalOnly(r: RepoView) {
  const updated = await setRepoLocalOnly(r.id, !r.localOnly)
  const i = repos.value.findIndex((x) => x.id === r.id)
  if (i >= 0) repos.value[i] = { ...repos.value[i], localOnly: updated.localOnly }
}
function modelLabel(m: string): string {
  return m === 'claude-cli' ? 'Claude CLI · interactive terminal' : m
}

// ---- repository health (repo-spec §7) ----
const openHealth = ref('')
type Health = { label: string; tone: 'ok' | 'info' | 'warn' }
// Working tree: operation-in-progress wins, then changes, else clean.
function workTree(r: RepoView): Health {
  if (r.operation) return { label: `${r.operation} in progress`, tone: 'warn' }
  const n = (r.staged || 0) + (r.unstaged || 0) + (r.untracked || 0)
  return n ? { label: `${n} change${n > 1 ? 's' : ''}`, tone: 'warn' } : { label: 'Clean', tone: 'ok' }
}
// Upstream synchronization vs the tracked branch.
function upstreamState(r: RepoView): Health {
  if (!r.hasUpstream) return { label: 'No upstream', tone: 'warn' }
  if (r.ahead && r.behind) return { label: `Diverged ↑${r.ahead} ↓${r.behind}`, tone: 'warn' }
  if (r.behind) return { label: `Needs pull ↓${r.behind}`, tone: 'warn' }
  if (r.ahead) return { label: `Needs push ↑${r.ahead}`, tone: 'info' }
  return { label: 'Up to date', tone: 'ok' }
}
const repoSessions = ref<{ id: string; title: string; repoPath: string }[]>([])
function sessionsFor(path: string) {
  return repoSessions.value.filter((s) => s.repoPath === path)
}
// Which repo's "Brainstorm here" menu is open (choose the model before starting).
const bmenu = ref('')
// claude-cli → an interactive terminal opened IN this repo (cwd = repo). claude-code → the
// repo-iterating chat. Both run Claude Code; other models don't operate on a repo.
async function brainstormHere(r: RepoView, model: string) {
  bmenu.value = ''
  busy.value = r.id
  try {
    const title = model === 'claude-cli' ? `Terminal · ${r.name}` : `Brainstorm · ${r.name}`
    const s = await createBrainstormSession(title, r.path, model)
    router.push({ path: '/brainstorm', query: { session: s.id } })
  } finally {
    busy.value = ''
  }
}
function openSession(id: string) {
  router.push({ path: '/brainstorm', query: { session: id } })
}

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
// per-repo branch switcher
const openBranch = ref<string | null>(null)
const branchList = ref<Record<string, string[]>>({})
const newBranch = ref<Record<string, string>>({})

onMounted(() => { store.ensureLoaded(); load() })

async function load() {
  loading.value = true
  try {
    const r = await fetchRepos()
    agentUp.value = r.agentUp
    repos.value = r.repos
    try { repoSessions.value = await fetchRepoSessions() } catch { /* keep */ }
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

// ---- branches ----
async function toggleBranches(r: RepoView) {
  if (openBranch.value === r.id) { openBranch.value = null; return }
  openBranch.value = r.id
  try { branchList.value[r.id] = (await repoBranches(r.id)).local } catch { branchList.value[r.id] = [] }
}
async function switchBranch(r: RepoView, branch: string, create = false) {
  if (!branch || !branch.trim() || busy.value) return
  busy.value = r.id; flash.value = ''
  try {
    const res = await repoCheckout(r.id, branch.trim(), create)
    flash.value = res.ok ? `${r.name}: now on ${res.branch}` : `${r.name}: checkout ✗ ${res.output}`
    if (res.ok) { openBranch.value = null; newBranch.value[r.id] = '' }
    await load()
  } finally { busy.value = '' }
}
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
          <button class="branchbtn mono" :disabled="!agentUp" title="Switch branch" @click="toggleBranches(r)">
            ⎇ {{ r.branch || '—' }} ▾
          </button>
          <span class="hchip mono" :class="workTree(r).tone" :title="'Working tree'">{{ workTree(r).label }}</span>
          <span class="hchip mono" :class="upstreamState(r).tone" :title="r.upstream ? 'vs ' + r.upstream : 'Upstream'">{{ upstreamState(r).label }}</span>
          <button class="hmore mono" :aria-expanded="openHealth === r.id" @click="openHealth = openHealth === r.id ? '' : r.id">
            health {{ openHealth === r.id ? '▴' : '▾' }}
          </button>
          <span class="path mono">{{ r.path }}</span>
        </div>

        <!-- metadata: Git identity lives here (repo config), NOT in the action row -->
        <div class="metarow mono">
          <span class="slug">{{ r.slug || r.remote || 'local repo' }}</span>
          <template v-if="editing !== r.id">
            <span class="idsep">·</span>
            <span class="idlabel">Commits as</span>
            <b class="idname">{{ r.userName || '(unset)' }}</b>
            <span class="idemail" :title="r.userEmail">&lt;{{ r.userEmail || 'no email' }}&gt;</span>
            <button class="idchange" :disabled="!agentUp" @click="startEdit(r)">Change</button>
            <span v-if="!r.userName || !r.userEmail" class="idwarn" title="New commits need a name + email">⚠ identity incomplete</span>
          </template>
        </div>

        <!-- expanded health rows (repo-spec §11) -->
        <div v-if="openHealth === r.id" class="healthbox">
          <div class="hrow"><span class="hk mono">Working tree</span><span class="hv" :class="workTree(r).tone">{{ workTree(r).label }}</span>
            <span v-if="r.staged || r.unstaged || r.untracked" class="mono hdet">{{ r.staged }} staged · {{ r.unstaged }} unstaged · {{ r.untracked }} untracked</span></div>
          <div class="hrow"><span class="hk mono">Upstream</span><span class="hv" :class="upstreamState(r).tone">{{ upstreamState(r).label }}</span>
            <span class="mono hdet">{{ r.upstream ? 'tracks ' + r.upstream : 'no tracking branch configured' }}</span></div>
        </div>

        <div v-if="openBranch === r.id" class="branchpanel">
          <span class="clab mono">Switch branch</span>
          <div class="branches">
            <button
              v-for="b in branchList[r.id] ?? []"
              :key="b"
              class="brow mono"
              :class="{ cur: b === r.branch }"
              :disabled="busy === r.id"
              @click="switchBranch(r, b)"
            >
              {{ b === r.branch ? '● ' : '' }}{{ b }}
            </button>
            <span v-if="!(branchList[r.id] ?? []).length" class="mono clean">no local branches</span>
          </div>
          <div class="newbranch">
            <input v-model="newBranch[r.id]" class="in sm" placeholder="new-branch-name" @keydown.enter="switchBranch(r, newBranch[r.id], true)" />
            <button class="btn" :disabled="busy === r.id || !(newBranch[r.id] || '').trim()" @click="switchBranch(r, newBranch[r.id], true)">
              Create &amp; switch
            </button>
          </div>
        </div>

        <div v-if="editing === r.id" class="idedit">
          <input v-model="editName" class="in sm" placeholder="git user.name" />
          <input v-model="editEmail" class="in sm" placeholder="git user.email" />
          <button class="btn pri" :disabled="busy === r.id" @click="saveEdit(r)">Save</button>
          <button class="btn ghost" @click="editing = null">Cancel</button>
          <span class="idnote mono">Applies to future commits only.</span>
        </div>

        <div class="acts">
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="toggleChanges(r)">
            {{ openChanges === r.id ? 'Hide changes' : 'Changes' }}
          </button>
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="act(r, () => repoPull(r.id), 'pull')">Pull</button>
          <button class="btn" :disabled="busy === r.id || !agentUp" @click="act(r, () => repoPush(r.id), 'push')">Push</button>
          <button class="btn" :disabled="busy === r.id || !agentUp || r.host === 'none'" @click="act(r, () => repoPr(r.id), 'open PR/MR')">
            {{ r.host === 'gitlab' ? 'Open MR' : 'Open PR' }}
          </button>
          <button
            class="btn"
            :class="{ localon: r.localOnly }"
            :disabled="busy === r.id"
            :title="r.localOnly ? 'Local-only: only local models can brainstorm this repo' : 'Allow remote models for this repo'"
            @click="toggleLocalOnly(r)"
          >{{ r.localOnly ? '🔒 Local-only' : '🔓 Any model' }}</button>
          <div class="splitwrap">
            <button
              class="btn brainstorm"
              :disabled="busy === r.id || !agentUp"
              title="Choose a model to brainstorm this repo"
              @click="bmenu = bmenu === r.id ? '' : r.id"
            >
              ✎ Brainstorm here ▾
            </button>
            <div v-if="bmenu === r.id" class="bmenu" @click.self="bmenu = ''">
              <div class="bmlab mono">{{ r.localOnly ? 'local models only' : 'choose a model' }}</div>
              <button v-for="m in repoModels(r)" :key="m" class="bmi" @click="brainstormHere(r, m)">
                {{ modelLabel(m) }}
              </button>
              <div v-if="!repoModels(r).length" class="bmi empty mono">no local models pulled</div>
            </div>
          </div>
          <button class="btn ghost" :disabled="busy === r.id" @click="remove(r)">Remove</button>
        </div>

        <div v-if="sessionsFor(r.path).length" class="rsessions">
          <span class="rslab mono">brainstorms:</span>
          <button v-for="s in sessionsFor(r.path)" :key="s.id" class="rschip" @click="openSession(s.id)">
            ✎ {{ s.title }}
          </button>
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
.branchbtn { font-size: 11px; color: var(--warp-hi); background: transparent; border: 1px solid var(--line); border-radius: 5px; padding: 2px 8px; cursor: pointer; }
.branchbtn:hover { border-color: var(--warp); }
.branchbtn:disabled { opacity: 0.5; cursor: not-allowed; }
.branchpanel { margin: 10px 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); }
.branches { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0; }
.brow { font-size: 12px; background: transparent; border: 1px solid var(--line); border-radius: 5px; padding: 3px 9px; color: var(--dim); cursor: pointer; }
.brow:hover { border-color: var(--warp); color: var(--ink); }
.brow.cur { color: var(--warp-hi); border-color: var(--warp); }
.newbranch { display: flex; gap: 8px; margin-top: 6px; }
.path { font-size: 11px; color: var(--faint-text); margin-left: auto; }
.tag { font-size: 10px; border: 1px solid var(--line); border-radius: 5px; padding: 2px 6px; color: var(--dim); }
.tag.dirty { color: var(--warp-hi); border-color: var(--warp); }
.idedit { display: flex; gap: 8px; margin: 8px 0; align-items: center; flex-wrap: wrap; }
.idnote { font-size: 11px; color: var(--faint-text); }
/* health chips + rows */
.hchip { font-size: 10px; border: 1px solid var(--line); border-radius: 5px; padding: 2px 7px; }
.hchip.ok { color: var(--healthy); border-color: color-mix(in srgb, var(--healthy) 50%, var(--line)); }
.hchip.info { color: var(--warp-hi); border-color: var(--warp); }
.hchip.warn { color: var(--chip-fail, #d88); border-color: var(--failed, #a55); }
.hmore { font-size: 10px; color: var(--dim); background: transparent; border: 1px solid var(--line); border-radius: 5px; padding: 2px 7px; cursor: pointer; }
.hmore:hover { border-color: var(--warp); color: var(--ink); }
.metarow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 8px 0 2px; font-size: 11.5px; color: var(--faint-text); }
.metarow .slug { color: var(--dim); }
.metarow .idsep { color: var(--line-hi, var(--line)); }
.metarow .idname { color: var(--ink); font-weight: 600; }
.metarow .idemail { color: var(--faint-text); }
.metarow .idchange { font-size: 11px; color: var(--warp-hi); background: transparent; border: 0; cursor: pointer; padding: 0 2px; text-decoration: underline; }
.metarow .idwarn { color: var(--chip-fail, #d88); }
.healthbox { border: 1px solid var(--line); border-radius: 8px; background: var(--bg); padding: 8px 12px; margin: 8px 0; }
.hrow { display: flex; align-items: center; gap: 12px; padding: 4px 0; font-size: 12px; }
.hrow .hk { width: 96px; color: var(--faint-text); text-transform: uppercase; letter-spacing: 0.08em; font-size: 10px; }
.hrow .hv { font-size: 12px; }
.hrow .hv.ok { color: var(--healthy); } .hrow .hv.info { color: var(--warp-hi); } .hrow .hv.warn { color: var(--chip-fail, #d88); }
.hrow .hdet { margin-left: auto; color: var(--faint-text); font-size: 11px; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.rsessions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.rslab { font-size: 11px; color: var(--faint-text); }
.rschip { font-size: 12px; color: var(--warp-hi); background: transparent; border: 1px solid var(--line); border-radius: 20px; padding: 3px 10px; cursor: pointer; }
.rschip:hover { border-color: var(--warp); }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 6px 12px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn:hover { border-color: var(--warp); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.btn.brainstorm { border-color: var(--warp); color: var(--warp-hi); }
.splitwrap { position: relative; display: inline-flex; }
.btn.split { border-top-right-radius: 0; border-bottom-right-radius: 0; }
.btn.caret { border-top-left-radius: 0; border-bottom-left-radius: 0; border-left: 0; padding: 6px 8px; }
.bmenu { position: absolute; top: calc(100% + 4px); right: 0; z-index: 20; min-width: 240px; background: var(--surface); border: 1px solid var(--line); border-radius: 10px; padding: 6px; box-shadow: 0 8px 24px rgba(0,0,0,0.35); }
.bmi { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; width: 100%; text-align: left; background: transparent; border: 0; border-radius: 7px; padding: 8px 10px; color: var(--ink); font-size: 13px; cursor: pointer; }
.bmi:hover { background: var(--nav-hover); }
.bmi .mono { font-size: 11px; color: var(--faint-text); }
.bmi.empty { color: var(--faint-text); cursor: default; }
.bmi.empty:hover { background: transparent; }
.bmlab { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); padding: 4px 10px 6px; }
.btn.localon { border-color: var(--healthy); color: var(--healthy); }
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
