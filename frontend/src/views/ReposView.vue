<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { computed } from 'vue'
import type { RepoView, BrowseResult, RepoChanges, SourceStatus, ConflictStatus, WorktreeInfo, PushProtection, HistoryResult, CommitDetail } from '../types'
import {
  fetchRepos,
  scanRepoFolder,
  syncRepos,
  addRepoPath,
  removeRepo,
  setRepoIdentity,
  repoPull,
  repoPush,
  repoAbort,
  repoPr,
  browseFs,
  repoChanges,
  repoStage,
  repoUnstage,
  repoCommit,
  repoBranches,
  repoCheckout,
  repoSource,
  setRepoSource,
  repoWorktrees,
  repoHistory,
  repoCommitDetail,
  repoSquash,
  repoPushProtection,
  setRepoPushProtection,
  repoFetch,
  repoConflict,
  createBrainstormSession,
  fetchRepoSessions,
  setRepoLocalOnly,
} from '../api'
import { storeToRefs } from 'pinia'
import { qualifyNames } from '../utils/repoNames'
import RepoBrainstormButton from '../components/RepoBrainstormButton.vue'
import AppToast from '../components/AppToast.vue'
import UpstreamChip from '../components/UpstreamChip.vue'
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

// ---- repository health (repo-spec §7) ----
const openHealth = ref('')
type Health = { label: string; tone: 'ok' | 'info' | 'warn' }
// Working tree: operation-in-progress wins, then changes, else clean.
function workTree(r: RepoView): Health {
  if (r.operation) return { label: `${r.operation} in progress`, tone: 'warn' }
  const n = (r.staged || 0) + (r.unstaged || 0) + (r.untracked || 0)
  return n ? { label: `${n} change${n > 1 ? 's' : ''}`, tone: 'warn' } : { label: 'Clean', tone: 'ok' }
}
// Upstream state (and the pull it implies) lives in components/UpstreamChip.vue.
// Source-branch model (repo-spec §6/§7.3): where this branch forks from + drift. Fetched
// lazily the first time a repo's health rows expand (needs a git call the list doesn't carry).
const sourceStatus = ref<Record<string, SourceStatus | null>>({})
const sourceLoading = ref<Record<string, boolean>>({})
const editSource = ref<string | null>(null) // repo whose source-branch edit is open
const sourceInput = ref<Record<string, string>>({})
// Conflict prediction + last-refresh (repo-spec §7.4). Also lazily loaded on health expand.
const conflict = ref<Record<string, ConflictStatus | null>>({})
const conflictLoading = ref<Record<string, boolean>>({})
const refreshing = ref<Record<string, boolean>>({})
function toggleHealth(r: RepoView) {
  openHealth.value = openHealth.value === r.id ? '' : r.id
  if (openHealth.value === r.id) {
    if (sourceStatus.value[r.id] === undefined) loadSource(r)
    if (conflict.value[r.id] === undefined) loadConflict(r)
    if (pushProt.value[r.id] === undefined) loadPushProt(r)
  }
}
// Per-repo push-protection override (falls back to the global default).
const pushProt = ref<Record<string, PushProtection | null>>({})
const editProt = ref<string | null>(null)
const protMode = ref<Record<string, string>>({})
const protPatterns = ref<Record<string, string>>({})
async function loadPushProt(r: RepoView) {
  try { pushProt.value[r.id] = await repoPushProtection(r.id) } catch { pushProt.value[r.id] = null }
}
function openProtEdit(r: RepoView) {
  const p = pushProt.value[r.id]
  editProt.value = r.id
  protMode.value[r.id] = p?.overridden ? p.mode : 'inherit'
  protPatterns.value[r.id] = p?.patterns ?? 'main, master, develop, dev'
}
async function saveProt(r: RepoView) {
  try {
    pushProt.value[r.id] = await setRepoPushProtection(r.id, protMode.value[r.id] ?? 'inherit', protPatterns.value[r.id] ?? '')
  } finally { editProt.value = null }
}
async function loadConflict(r: RepoView) {
  conflictLoading.value[r.id] = true
  try { conflict.value[r.id] = await repoConflict(r.id, r.branch) }
  catch { conflict.value[r.id] = null }
  finally { conflictLoading.value[r.id] = false }
}
// Manual refresh: fetch remote refs once, then recompute source drift + conflict (FR-06).
async function refreshRepo(r: RepoView) {
  if (refreshing.value[r.id]) return
  refreshing.value[r.id] = true
  flash.value = null
  try {
    const res = await repoFetch(r.id)
    if (!res.ok) say(`Fetch failed for ${repoLabel(r)}: ${res.error ?? 'unknown error'}`, 'error')
    await Promise.all([loadSource(r), loadConflict(r)])
  } finally {
    refreshing.value[r.id] = false
  }
}
// Conflict-risk chip: label + tone from the predicted state.
function conflictHealth(c: ConflictStatus | null | undefined): Health {
  if (!c) return { label: 'Not checked', tone: 'info' }
  if (c.state === 'conflict') {
    const n = c.files.length
    return { label: n ? `Conflicts likely (${n} file${n > 1 ? 's' : ''})` : 'Conflicts likely', tone: 'warn' }
  }
  // "No conflicts" against refs we have never fetched is an assumption, not a prediction. The
  // backend flags exactly this (stale, lastFetch null) so it can be said out loud; the chip was
  // reporting a confident green result for a comparison it hadn't actually made.
  if (c.state === 'clean') {
    return c.stale
      ? { label: 'No conflicts vs local refs', tone: 'info' }
      : { label: 'No conflicts', tone: 'ok' }
  }
  if (c.state === 'unable') return { label: 'Unable to check', tone: 'warn' }
  return { label: 'Unknown', tone: 'info' } // unknown / stale
}
// "3m ago" / "2h ago" / "just now" / "never" from an ISO instant.
function refreshLabel(iso: string | null | undefined): string {
  if (!iso) return 'never fetched'
  const ms = Date.now() - new Date(iso).getTime()
  if (ms < 60_000) return 'just now'
  const m = Math.floor(ms / 60_000)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.floor(h / 24)}d ago`
}
async function loadSource(r: RepoView) {
  sourceLoading.value[r.id] = true
  try { sourceStatus.value[r.id] = await repoSource(r.id, r.branch) }
  catch { sourceStatus.value[r.id] = null }
  finally { sourceLoading.value[r.id] = false }
}
// Drift of HEAD vs its source branch → chip label + tone.
function sourceHealth(s: SourceStatus | null | undefined): Health {
  if (!s || s.origin === 'unknown') return { label: 'Unknown', tone: 'warn' }
  if (s.missing) return { label: `${s.source} missing`, tone: 'warn' }
  if (s.sourceBehind) return { label: `Behind ${s.source} by ${s.sourceBehind}`, tone: 'warn' }
  return { label: `Current with ${s.source}`, tone: 'ok' }
}
// How the source branch was resolved — shown as the row detail.
function sourceOrigin(s: SourceStatus | null | undefined): string {
  if (!s) return 'not resolved'
  if (s.origin === 'override') return 'saved for this branch'
  if (s.origin === 'pr') return 'from open PR'
  if (s.origin === 'default') return 'repo default'
  return `no source (default ${s.defaultBranch ?? '—'})`
}
async function openSourceEdit(r: RepoView) {
  editSource.value = r.id
  sourceInput.value[r.id] = sourceStatus.value[r.id]?.source ?? ''
  if (!branchList.value[r.id]) {
    try { branchList.value[r.id] = (await repoBranches(r.id)).local } catch { branchList.value[r.id] = [] }
  }
}
async function saveSource(r: RepoView) {
  const src = (sourceInput.value[r.id] ?? '').trim()
  sourceLoading.value[r.id] = true
  try { sourceStatus.value[r.id] = await setRepoSource(r.id, r.branch, src || null) }
  finally { sourceLoading.value[r.id] = false; editSource.value = null }
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
    // A worktree carries its branch, or three worktrees of one repo yield three identical titles.
    const label = r.isLinkedWorktree && r.branch ? `${repoLabel(r)} ⎇ ${r.branch}` : repoLabel(r)
    const title = model === 'claude-cli' ? `Terminal · ${label}` : `Brainstorm · ${label}`
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
// Labels are computed over every tracked repo, not just the visible ones, so a name does not
// change when the worktree-grouping toggle hides a row.
const repoLabels = computed(() => qualifyNames(repos.value.map((r) => r.path)))
function repoLabel(r: RepoView): string {
  return repoLabels.value.get(r.path) ?? r.name
}
const agentUp = ref(false)
const loading = ref(true)
const busy = ref('')

// ---- worktree grouping (repos spec §9) ----
// Worktrees of one repo share a git common-dir; when grouping is on we show the main checkout as
// the primary card and nest its linked worktrees under it. Toggle persists locally.
const groupWorktrees = ref(localStorage.getItem('devloom.groupWorktrees') !== 'off')
function toggleGrouping() {
  groupWorktrees.value = !groupWorktrees.value
  localStorage.setItem('devloom.groupWorktrees', groupWorktrees.value ? 'on' : 'off')
}
const norm = (p: string) => (p || '').replace(/\\/g, '/').toLowerCase()

// Worktrees of one repo share a git common-dir. Grouped in a single pass and cached: the template
// asks for a card's children several times per render (the expander, its count, the row loop, the
// empty state), and doing that as a filter-per-call re-normalised every commonDir in the list each
// time.
const byCommonDir = computed(() => {
  const m = new Map<string, RepoView[]>()
  if (!groupWorktrees.value) return m
  for (const r of repos.value) {
    if (!r.commonDir) continue
    const k = norm(r.commonDir)
    const bucket = m.get(k)
    if (bucket) bucket.push(r)
    else m.set(k, [r])
  }
  return m
})
function siblings(r: RepoView): RepoView[] {
  return (r.commonDir && byCommonDir.value.get(norm(r.commonDir))) || []
}
// A repo is nested (hidden from the top level) when grouping is on, it's a linked worktree, and a
// primary (main checkout) for the same repo is present in the list.
function isNested(r: RepoView) {
  return groupWorktrees.value && r.isLinkedWorktree
    && siblings(r).some((x) => x.id !== r.id && !x.isLinkedWorktree)
}
const visibleRepos = computed(() => repos.value.filter((r) => !isNested(r)))
// Tracked linked worktrees of a primary repo.
function trackedChildren(primary: RepoView): RepoView[] {
  if (!groupWorktrees.value || primary.isLinkedWorktree) return []
  return siblings(primary).filter((r) => r.id !== primary.id && r.isLinkedWorktree)
}

// Which checkout a card is pointed at. Selecting a worktree re-aims the whole card — every action
// and every panel — rather than giving each row its own copy of the controls. The card says loudly
// which tree is active, because at this point Remove follows the selection too.
const selectedWt = ref<Record<string, string>>({})
function activeRepo(primary: RepoView): RepoView {
  const id = selectedWt.value[primary.id]
  if (!id || id === primary.id) return primary
  return trackedChildren(primary).find((c) => c.id === id) ?? primary
}
function selectWt(primary: RepoView, target: RepoView) {
  if (target.id === primary.id) delete selectedWt.value[primary.id]
  else selectedWt.value[primary.id] = target.id
  // The panels are keyed by repo id; leaving them open would show the previous tree's data.
  openHealth.value = ''
  openBranch.value = null
  openChanges.value = null
}
// Expander state + lazily-loaded on-disk worktree list (includes untracked worktrees).
const openWt = ref('')
const wtList = ref<Record<string, WorktreeInfo[]>>({})
async function toggleWorktrees(r: RepoView) {
  openWt.value = openWt.value === r.id ? '' : r.id
  if (openWt.value === r.id && wtList.value[r.id] === undefined) {
    try { wtList.value[r.id] = await repoWorktrees(r.id) } catch { wtList.value[r.id] = [] }
  }
}
// On-disk worktrees that DevLoom doesn't yet track (offer to add them).
function untrackedWorktrees(r: RepoView): WorktreeInfo[] {
  return (wtList.value[r.id] ?? []).filter((w) => !w.tracked && norm(w.path) !== norm(r.path))
}
async function addWorktree(path: string) {
  if (busy.value) return
  busy.value = 'add'; flash.value = null
  try { repos.value = await addRepoPath(path); say(`Added ${path}.`) }
  catch { say('Could not add worktree.', 'error') }
  finally { busy.value = '' }
}
function isRunWorktree(branch: string | null): boolean {
  return !!branch && branch.startsWith('devloom/run-')
}
const flash = ref<{ text: string; tone: 'ok' | 'error' } | null>(null)
const say = (text: string, tone: 'ok' | 'error' = 'ok') => { flash.value = { text, tone } }
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
// Chips are pleasant at 5 branches and a wall at 20 — past this, the panel switches to a
// filter box over a scrolling list, current branch pinned first.
const BRANCH_CHIP_LIMIT = 6
const branchQuery = ref<Record<string, string>>({})
function visibleBranches(r: RepoView): string[] {
  const all = branchList.value[r.id] ?? []
  const q = (branchQuery.value[r.id] ?? '').trim().toLowerCase()
  const hit = q ? all.filter((b) => b.toLowerCase().includes(q)) : all
  // Current branch first, so "where am I" never needs scrolling.
  return [...hit].sort((a, b) => (a === r.branch ? -1 : b === r.branch ? 1 : 0))
}

onMounted(() => { store.ensureLoaded(); load() })

// `loading` blanks the entire list for a "loading…" line, which after an action reads as a page
// refresh and takes the scroll position with it. The first load should still say it is loading;
// every refresh *after* an action should not.
async function load({ silent = false }: { silent?: boolean } = {}) {
  if (!silent) loading.value = true
  try {
    const r = await fetchRepos()
    agentUp.value = r.agentUp
    repos.value = r.repos
    try { repoSessions.value = await fetchRepoSessions() } catch { /* keep */ }
  } finally {
    if (!silent) loading.value = false
  }
}

async function scan() {
  const root = pathInput.value.trim()
  if (!root || busy.value) return
  busy.value = 'add'; flash.value = null
  try { repos.value = await scanRepoFolder(root); agentUp.value = true; say(`Scanned ${root}.`); pathInput.value = '' }
  catch { say('Scan failed — is the host agent running?', 'error') }
  finally { busy.value = '' }
}

async function addOne() {
  const p = pathInput.value.trim()
  if (!p || busy.value) return
  busy.value = 'add'; flash.value = null
  try { repos.value = await addRepoPath(p); agentUp.value = true; say(`Added ${p}.`); pathInput.value = '' }
  catch { say('Not a git repo (or agent down).', 'error') }
  finally { busy.value = '' }
}

// Sync: scan the parent directories configured in Settings and import any new repos.
async function sync() {
  if (busy.value) return
  busy.value = 'sync'; flash.value = null
  try {
    const r = await syncRepos()
    agentUp.value = r.agentUp
    repos.value = r.repos
    // "nothing configured" is a dead end rather than a result, so it stays until dismissed.
    if (!r.dirs.length) say('No repository directories configured — add some in Settings › General.', 'error')
    else say(`Synced ${r.dirs.length} director${r.dirs.length > 1 ? 'ies' : 'y'} — ${r.added} new repo${r.added === 1 ? '' : 's'} added.`)
    try { repoSessions.value = await fetchRepoSessions() } catch { /* keep */ }
  } catch { say('Sync failed — is the host agent running?', 'error') }
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
  catch { say('Browse failed — is the host agent running?', 'error'); browse.value.open = false }
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
  busy.value = r.id; flash.value = null
  try {
    const res = await fn()
    if (res.url && (res.web || res.ok)) window.open(res.url, '_blank', 'noopener')
    const bad = res.ok === false
    say(`${repoLabel(r)}: ${label} ${bad ? '✗ ' + (res.error || res.output || '') : '✓'}`, bad ? 'error' : 'ok')
    await load({ silent: true })
  } catch { say(`${repoLabel(r)}: ${label} failed.`, 'error') }
  finally { busy.value = '' }
}

// ---- git identity ----
function startEdit(r: RepoView) { editing.value = r.id; editName.value = r.userName; editEmail.value = r.userEmail }
async function saveEdit(r: RepoView) {
  busy.value = r.id
  try { await setRepoIdentity(r.id, editName.value, editEmail.value); editing.value = null; say(`${repoLabel(r)}: git identity updated.`); await load({ silent: true }) }
  finally { busy.value = '' }
}

async function remove(r: RepoView) {
  if (!confirm(`Remove ${r.name} from DevLoom? (your files are untouched)`)) return
  busy.value = r.id
  try { await removeRepo(r.id); await load({ silent: true }) } finally { busy.value = '' }
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
  try { await repoStage(r.id, files); await refreshChanges(r.id); await load({ silent: true }) } finally { busy.value = '' }
}
async function unstage(r: RepoView, files: string[]) {
  busy.value = r.id
  try { await repoUnstage(r.id, files); await refreshChanges(r.id) } finally { busy.value = '' }
}
async function commit(r: RepoView) {
  const m = (commitMsg.value[r.id] || '').trim()
  if (!m || busy.value) return
  busy.value = r.id; flash.value = null
  try {
    const res = await repoCommit(r.id, m)
    say(`${repoLabel(r)}: commit ${res.ok ? '✓' : '✗ ' + (res.output || '')}`, res.ok ? 'ok' : 'error')
    if (res.ok) commitMsg.value[r.id] = ''
    await refreshChanges(r.id); await load({ silent: true })
  } finally { busy.value = '' }
}
function stagedCount(id: string) { return changes.value[id]?.staged.length ?? 0 }

// ---- commit / push split button ----
// One control that adapts: commit (+optionally push) when there are changes, plain push when the
// branch is only ahead, blocked when there is nothing to do. The dropdown exposes each sub-action
// explicitly plus a lease-protected force push.
const pushMenu = ref('')                                    // which repo's push dropdown is open
const commitPrompt = ref<{ id: string; push: boolean } | null>(null) // inline commit-message popover
function changeCount(r: RepoView) { return (r.staged || 0) + (r.unstaged || 0) + (r.untracked || 0) }
function canCommit(r: RepoView) { return changeCount(r) > 0 && !r.operation }
function canPush(r: RepoView) { return (r.ahead || 0) > 0 }
function pushBlocked(r: RepoView) { return !canCommit(r) && !canPush(r) }
function primaryPushLabel(r: RepoView) {
  if (canCommit(r)) return 'Commit & Push'
  return canPush(r) ? `Push ↑${r.ahead}` : 'Push'
}
// Primary click: commit+push when dirty, else a plain push.
function primaryPush(r: RepoView) {
  if (canCommit(r)) startCommit(r, true)
  else if (canPush(r)) pushOnly(r)
}
function startCommit(r: RepoView, push: boolean) {
  pushMenu.value = ''
  commitPrompt.value = { id: r.id, push }
  if (!commitMsg.value[r.id]) commitMsg.value[r.id] = ''
}
// Stage everything (git add -A), commit, then optionally push. Surfaces a push rejection as an
// actionable warning (offer force) rather than a silent failure.
async function commitAndPush(r: RepoView, push: boolean) {
  const m = (commitMsg.value[r.id] || '').trim()
  if (!m || busy.value) return
  busy.value = r.id; flash.value = null
  try {
    await repoStage(r.id, [])
    const c = await repoCommit(r.id, m)
    if (!c.ok) { say(`${repoLabel(r)}: commit ✗ ${c.output || ''}`, 'error'); return }
    commitMsg.value[r.id] = ''
    commitPrompt.value = null
    if (push) {
      const p = await repoPush(r.id)
      if (!p.ok && p.rejected) pushReject.value = r.id
      say(`${repoLabel(r)}: commit ✓ · push ${p.ok ? '✓' : '✗ ' + (p.output || '')}`, p.ok ? 'ok' : 'error')
    } else {
      say(`${repoLabel(r)}: commit ✓`)
    }
    await load({ silent: true })
    if (openChanges.value === r.id) await refreshChanges(r.id)
  } finally { busy.value = '' }
}
// Which repo had its push rejected (non-fast-forward) → show a force-push hint.
const pushReject = ref('')
async function pushOnly(r: RepoView) {
  pushMenu.value = ''
  busy.value = r.id; flash.value = null
  try {
    const p = await repoPush(r.id)
    pushReject.value = !p.ok && p.rejected ? r.id : ''
    say(`${repoLabel(r)}: push ${p.ok ? '✓' : '✗ ' + (p.output || '')}`, p.ok ? 'ok' : 'error')
    await load({ silent: true })
  } finally { busy.value = '' }
}
// Force push uses --force-with-lease (backend never does a bare --force). Guarded by a confirm
// because it rewrites the remote branch (repo-spec §9.5).
async function forcePush(r: RepoView) {
  pushMenu.value = ''
  if (!confirm(`Force-push ${r.branch} to its remote using --force-with-lease?\n\nThis rewrites the remote branch. It is refused if the remote moved since your last fetch.`)) return
  busy.value = r.id; flash.value = null
  try {
    const p = await repoPush(r.id, true)
    pushReject.value = ''
    say(`${repoLabel(r)}: force-push ${p.ok ? '✓' : '✗ ' + (p.output || '')}`, p.ok ? 'ok' : 'error')
    await load({ silent: true })
  } finally { busy.value = '' }
}
// Back out of a merge/rebase/cherry-pick/revert we can't resolve in-app (restores prior state).
async function abortOp(r: RepoView) {
  if (!confirm(`Abort the in-progress ${r.operation} in ${r.name}? This restores the branch to its state before the ${r.operation} started.`)) return
  busy.value = r.id; flash.value = null
  try {
    const a = await repoAbort(r.id)
    say(`${repoLabel(r)}: ${a.operation ?? 'operation'} abort ${a.ok ? '✓' : '✗ ' + (a.output || '')}`, a.ok ? 'ok' : 'error')
    await load({ silent: true })
  } finally { busy.value = '' }
}

// ---- history + guarded squash (repo-spec §8–§9) ----
const openHistory = ref<string | null>(null)
const histUnique = ref(true) // unique-to-source is the preferred (and squash-eligible) view
const history = ref<Record<string, HistoryResult | null>>({})
const histLoading = ref(false)
const openCommit = ref('')
const commitDetail = ref<CommitDetail | null>(null)
// Squash selection is top-anchored: picking a row selects it and everything newer (contiguous
// from HEAD) — the only shape a soft-reset squash supports.
const squashThrough = ref(-1)
const squashDlg = ref<RepoView | null>(null)
const squashMsg = ref('')
const squashAck = ref(false)
const squashBusy = ref(false)

async function toggleHistory(r: RepoView) {
  if (openHistory.value === r.id) { openHistory.value = null; return }
  openHistory.value = r.id
  squashThrough.value = -1
  openCommit.value = ''
  await loadHistory(r)
}
async function loadHistory(r: RepoView) {
  histLoading.value = true
  try { history.value[r.id] = await repoHistory(r.id, histUnique.value) }
  catch { history.value[r.id] = null }
  finally { histLoading.value = false }
}
function setUnique(r: RepoView, v: boolean) {
  histUnique.value = v
  squashThrough.value = -1
  loadHistory(r)
}
function commitsOf(r: RepoView) {
  return history.value[r.id]?.commits ?? []
}
async function toggleCommit(r: RepoView, hash: string) {
  if (openCommit.value === hash) { openCommit.value = ''; return }
  openCommit.value = hash
  commitDetail.value = null
  try { commitDetail.value = await repoCommitDetail(r.id, hash) } catch { /* leave null */ }
}
function relTime(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime()
  const m = Math.floor(ms / 60_000)
  if (m < 1) return 'now'
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  const d = Math.floor(h / 24)
  return d < 30 ? `${d}d` : `${Math.floor(d / 30)}mo`
}
// A row is range-eligible when it and everything newer are non-merge commits.
function rangeEligible(r: RepoView, idx: number) {
  const cs = commitsOf(r)
  for (let i = 0; i <= idx && i < cs.length; i++) if (cs[i].merge) return false
  return true
}
function selectThrough(idx: number) {
  squashThrough.value = squashThrough.value === idx ? -1 : idx
}
const selCount = computed(() => squashThrough.value + 1)
function selectedPublished(r: RepoView) {
  return commitsOf(r).slice(0, selCount.value).filter((c) => c.published).length
}
function openSquash(r: RepoView) {
  const cs = commitsOf(r).slice(0, selCount.value)
  const oldest = cs[cs.length - 1]
  const others = cs.slice(0, -1).map((c) => '- ' + c.subject)
  squashMsg.value = oldest.subject + (others.length ? '\n\n' + others.join('\n') : '')
  squashAck.value = false
  squashDlg.value = r
}
async function doSquash(r: RepoView) {
  if (squashBusy.value || !squashMsg.value.trim()) return
  squashBusy.value = true
  flash.value = null
  try {
    const res = await repoSquash(r.id, selCount.value, squashMsg.value, selectedPublished(r) > 0)
    if (res.ok) {
      say(`Squashed ${selCount.value} commits → ${res.newHead}. Recover: ${res.recover}`)
      squashDlg.value = null
      squashThrough.value = -1
      await loadHistory(r)
      await load({ silent: true })
    } else {
      say('Squash blocked — ' + (res.error ?? 'unknown reason'), 'error')
      squashDlg.value = null
    }
  } catch {
    say('Squash failed — is the host agent running?', 'error')
  } finally { squashBusy.value = false }
}

// ---- branches ----
async function toggleBranches(r: RepoView) {
  if (openBranch.value === r.id) { openBranch.value = null; return }
  openBranch.value = r.id
  try { branchList.value[r.id] = (await repoBranches(r.id)).local } catch { branchList.value[r.id] = [] }
}
async function switchBranch(r: RepoView, branch: string, create = false) {
  if (!branch || !branch.trim() || busy.value) return
  busy.value = r.id; flash.value = null
  try {
    const res = await repoCheckout(r.id, branch.trim(), create)
    if (res.ok) say(`${repoLabel(r)}: now on ${res.branch}`)
    else say(`${repoLabel(r)}: checkout ✗ ${res.output}`, 'error')
    if (res.ok) { openBranch.value = null; newBranch.value[r.id] = '' }
    await load({ silent: true })
  } finally { busy.value = '' }
}
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Repositories</h1>
      <button
        class="wtoggle mono"
        :class="{ on: groupWorktrees }"
        title="Group a repo's worktrees under one card"
        @click="toggleGrouping"
      >⑂ Group worktrees {{ groupWorktrees ? 'on' : 'off' }}</button>
      <span class="when mono">{{ visibleRepos.length }} repos · {{ agentUp ? 'agent connected' : 'agent offline' }}</span>
    </div>

    <div v-if="!agentUp" class="warnbar mono">
      Start the DevLoom host agent to manage local repos: <b>node agent/devloom-agent.mjs</b>
    </div>

    <div class="addbar">
      <input v-model="pathInput" class="in" placeholder="/path/to/projects (folder) or /path/to/a/repo" @keydown.enter="scan" />
      <button class="btn" :disabled="!agentUp" @click="openBrowse">Browse…</button>
      <button class="btn" :disabled="busy === 'add' || !pathInput.trim()" @click="scan">Scan folder</button>
      <button class="btn" :disabled="busy === 'add' || !pathInput.trim()" @click="addOne">Add repo</button>
      <button
        class="btn pri"
        :disabled="busy === 'sync' || !agentUp"
        title="Scan the directories configured in Settings and add any new git repos"
        @click="sync"
      >{{ busy === 'sync' ? 'Syncing…' : 'Sync' }}</button>
    </div>

    <AppToast v-if="flash" :text="flash.text" :tone="flash.tone" @close="flash = null" />

    <div v-if="loading" class="mono empty">loading…</div>
    <div v-else-if="!repos.length" class="mono empty">No repositories yet — browse or scan a folder.</div>

    <template v-else>
      <section v-for="r in visibleRepos" :key="r.id" class="repo">
        <template v-for="ar in [activeRepo(r)]" :key="ar.id">
        <div class="rh">
          <!-- Identity is the repository; the branch, chips and path describe whichever checkout
               the card is currently aimed at. The expander belongs to the repository too — hang it
               off `ar` and it disappears the moment you select a worktree, stranding you there. -->
          <span class="hostpill mono" :class="r.host">{{ r.host }}</span>
          <h3>{{ repoLabel(r) }}</h3>
          <button
            v-if="ar.id !== r.id"
            class="actingon mono"
            title="This card is aimed at a worktree — click to go back to the main checkout"
            @click="selectWt(r, r)"
          >◉ {{ ar.branch || 'worktree' }} ✕</button>
          <button class="branchbtn mono" :disabled="!agentUp" title="Switch branch" @click="toggleBranches(ar)">
            ⎇ {{ ar.branch || '—' }} ▾
          </button>
          <span class="hchip mono" :class="workTree(ar).tone" :title="'Working tree'">{{ workTree(ar).label }}</span>
          <UpstreamChip
            class="hchip mono"
            :repo="ar"
            :disabled="busy === ar.id || !agentUp"
            @pull="act(ar, () => repoPull(ar.id), 'pull')"
          />
          <button class="hmore mono" :aria-expanded="openHealth === ar.id" @click="toggleHealth(ar)">
            health {{ openHealth === ar.id ? '▴' : '▾' }}
          </button>
          <button
            v-if="groupWorktrees && !r.isLinkedWorktree && agentUp && trackedChildren(r).length"
            class="hmore mono wt"
            :aria-expanded="openWt === r.id"
            title="Checkouts of this repository"
            @click="toggleWorktrees(r)"
          >⑂ {{ trackedChildren(r).length }} worktree{{ trackedChildren(r).length > 1 ? 's' : '' }} {{ openWt === r.id ? '▴' : '▾' }}</button>
          <span class="path mono">{{ ar.path }}</span>
        </div>

        <!-- Every checkout of this repo, main first, exactly one selected. Selecting re-aims
             the whole card rather than giving each row its own copy of the controls. -->
        <div v-if="groupWorktrees && openWt === r.id" class="wtbox">
          <div class="wtrow" :class="{ sel: activeRepo(r).id === r.id }">
            <button
              class="wtpick mono"
              :aria-pressed="activeRepo(r).id === r.id"
              :title="activeRepo(r).id === r.id ? 'Already selected' : 'Point this card at the main checkout'"
              @click="selectWt(r, r)"
            >{{ activeRepo(r).id === r.id ? '◉' : '○' }} main</button>
            <span class="wtbranch mono">⎇ {{ r.branch || '—' }}</span>
            <span class="hchip mono" :class="workTree(r).tone">{{ workTree(r).label }}</span>
            <UpstreamChip class="hchip mono" :repo="r" :disabled="busy === r.id || !agentUp" @pull="act(r, () => repoPull(r.id), 'pull')" />
            <span class="wtpath mono">{{ r.path }}</span>
          </div>
          <div v-for="c in trackedChildren(r)" :key="c.id" class="wtrow" :class="{ sel: activeRepo(r).id === c.id }">
            <button
              class="wtpick mono"
              :aria-pressed="activeRepo(r).id === c.id"
              :title="activeRepo(r).id === c.id ? 'Already selected' : 'Point this card at this worktree'"
              @click="selectWt(r, c)"
            >{{ activeRepo(r).id === c.id ? '◉' : '○' }} select</button>
            <span class="wtbranch mono">⎇ {{ c.branch || '—' }}</span>
            <span v-if="isRunWorktree(c.branch)" class="wtrun mono">run</span>
            <span class="hchip mono" :class="workTree(c).tone">{{ workTree(c).label }}</span>
            <UpstreamChip class="hchip mono" :repo="c" :disabled="busy === c.id || !agentUp" @pull="act(c, () => repoPull(c.id), 'pull')" />
            <span class="wtpath mono">{{ c.path }}</span>
          </div>
          <div v-for="w in untrackedWorktrees(r)" :key="w.path" class="wtrow untracked">
            <span class="wtbranch mono">⎇ {{ w.branch || (w.detached ? 'detached' : '—') }}</span>
            <span v-if="isRunWorktree(w.branch)" class="wtrun mono">run</span>
            <span class="wtpath mono">{{ w.path }}</span>
            <button class="btn tiny" :disabled="busy === 'add'" @click="addWorktree(w.path)">Add</button>
          </div>
          <div v-if="!trackedChildren(r).length && !untrackedWorktrees(r).length" class="wtempty mono">
            no additional worktrees — create one with <b>git worktree add</b>
          </div>
        </div>

        <!-- metadata: Git identity lives here (repo config), NOT in the action row -->
        <div class="metarow mono">
          <span class="slug">{{ ar.slug || ar.remote || 'local repo' }}</span>
          <template v-if="editing !== ar.id">
            <span class="idsep">·</span>
            <span class="idlabel">Commits as</span>
            <b class="idname">{{ ar.userName || '(unset)' }}</b>
            <span class="idemail" :title="ar.userEmail">&lt;{{ ar.userEmail || 'no email' }}&gt;</span>
            <button class="idchange" :disabled="!agentUp" @click="startEdit(ar)">Change</button>
            <span v-if="!ar.userName || !ar.userEmail" class="idwarn" title="New commits need a name + email">⚠ identity incomplete</span>
          </template>
        </div>

        <!-- expanded health rows (repo-spec §11) -->
        <div v-if="openHealth === ar.id" class="healthbox">
          <div class="hrow"><span class="hk mono">Working tree</span><span class="hv" :class="workTree(ar).tone">{{ workTree(ar).label }}</span>
            <span v-if="ar.staged || ar.unstaged || ar.untracked" class="mono hdet">{{ ar.staged }} staged · {{ ar.unstaged }} unstaged · {{ ar.untracked }} untracked</span></div>
          <div class="hrow"><span class="hk mono">Upstream</span>
            <UpstreamChip class="hv" :repo="ar" :disabled="busy === ar.id || !agentUp" @pull="act(ar, () => repoPull(ar.id), 'pull')" />
            <span class="mono hdet">{{ ar.upstream ? 'tracks ' + ar.upstream : 'no tracking branch configured' }}</span></div>
          <div class="hrow">
            <span class="hk mono">Source branch</span>
            <template v-if="sourceLoading[ar.id] && sourceStatus[ar.id] === undefined"><span class="hv info">checking…</span></template>
            <template v-else>
              <span class="hv" :class="sourceHealth(sourceStatus[ar.id]).tone">{{ sourceHealth(sourceStatus[ar.id]).label }}</span>
              <span class="mono hdet">
                {{ sourceOrigin(sourceStatus[ar.id]) }}
                <template v-if="sourceStatus[ar.id]?.sourceAhead">· ↑{{ sourceStatus[ar.id]?.sourceAhead }} ahead</template>
              </span>
              <button v-if="editSource !== ar.id" class="hedit mono" :disabled="!agentUp" @click="openSourceEdit(ar)">change</button>
            </template>
          </div>
          <div v-if="editSource === ar.id" class="hrow srcedit">
            <span class="hk mono"></span>
            <input
              class="srcin mono"
              list="src-branches"
              v-model="sourceInput[ar.id]"
              placeholder="branch name (blank = default)"
              @keyup.enter="saveSource(ar)"
            />
            <datalist id="src-branches">
              <option v-for="b in branchList[ar.id] ?? []" :key="b" :value="b" />
            </datalist>
            <button class="hedit mono" :disabled="sourceLoading[ar.id]" @click="saveSource(ar)">save</button>
            <button class="hedit mono ghost" @click="editSource = null">cancel</button>
          </div>
          <div class="hrow">
            <span class="hk mono">Conflict check</span>
            <template v-if="conflictLoading[ar.id] && conflict[ar.id] === undefined"><span class="hv info">checking…</span></template>
            <template v-else>
              <span class="hv" :class="conflictHealth(conflict[ar.id]).tone">{{ conflictHealth(conflict[ar.id]).label }}</span>
              <span class="mono hdet" :title="(conflict[ar.id]?.files ?? []).join('\n')">
                {{ conflict[ar.id]?.reason
                   || (conflict[ar.id]?.files?.length ? conflict[ar.id]?.files.slice(0, 3).join(', ') + ((conflict[ar.id]?.files.length ?? 0) > 3 ? '…' : '')
                   : (conflict[ar.id]?.ref ? 'vs ' + conflict[ar.id]?.ref + ' · predictive' : '')) }}
              </span>
            </template>
          </div>
          <div class="hrow">
            <span class="hk mono">Last refresh</span>
            <span class="hv" :class="{ warn: conflict[ar.id]?.stale }">{{ refreshLabel(conflict[ar.id]?.lastFetch) }}</span>
            <span v-if="conflict[ar.id]?.stale" class="mono hdet">refs may be out of date</span>
            <button class="hedit mono" :disabled="!agentUp || refreshing[ar.id]" @click="refreshRepo(ar)">
              {{ refreshing[ar.id] ? 'fetching…' : 'refresh' }}
            </button>
          </div>
          <div class="hrow">
            <span class="hk mono">Push guard</span>
            <template v-if="pushProt[ar.id]">
              <span class="hv" :class="pushProt[ar.id]!.mode === 'off' ? 'ok' : 'info'">
                {{ pushProt[ar.id]!.mode === 'off' ? 'Pushes allowed' : pushProt[ar.id]!.mode === 'all' ? 'All pushes blocked' : 'Protected: ' + pushProt[ar.id]!.patterns }}
              </span>
              <span class="mono hdet">{{ pushProt[ar.id]!.overridden ? 'repo override' : 'global default' }}</span>
              <button v-if="editProt !== ar.id" class="hedit mono" @click="openProtEdit(ar)">change</button>
            </template>
            <span v-else class="hv info">…</span>
          </div>
          <div v-if="editProt === ar.id" class="hrow srcedit">
            <span class="hk mono"></span>
            <select v-model="protMode[ar.id]" class="srcin mono" style="flex:0 0 auto">
              <option value="inherit">Inherit global ({{ pushProt[ar.id]?.globalMode }})</option>
              <option value="off">Off — allow all</option>
              <option value="protected">Protected branches</option>
              <option value="all">Block all</option>
            </select>
            <input v-if="protMode[ar.id] === 'protected'" v-model="protPatterns[ar.id]" class="srcin mono" placeholder="main, master, develop, dev" @keyup.enter="saveProt(ar)" />
            <button class="hedit mono" @click="saveProt(ar)">save</button>
            <button class="hedit mono ghost" @click="editProt = null">cancel</button>
          </div>
        </div>

        <div v-if="openBranch === ar.id" class="branchpanel">
          <span class="clab mono">Switch branch</span>
          <input
            v-if="(branchList[ar.id] ?? []).length > BRANCH_CHIP_LIMIT"
            v-model="branchQuery[ar.id]"
            class="in sm brfilter mono"
            :placeholder="`filter ${(branchList[ar.id] ?? []).length} branches…`"
          />
          <div class="branches" :class="{ tall: (branchList[ar.id] ?? []).length > BRANCH_CHIP_LIMIT }">
            <button
              v-for="b in visibleBranches(ar)"
              :key="b"
              class="brow mono"
              :class="{ cur: b === ar.branch }"
              :disabled="busy === ar.id"
              @click="switchBranch(ar, b)"
            >
              {{ b === ar.branch ? '● ' : '' }}{{ b }}
            </button>
            <span v-if="!(branchList[ar.id] ?? []).length" class="mono clean">no local branches</span>
            <span v-else-if="!visibleBranches(ar).length" class="mono clean">no branch matches “{{ branchQuery[ar.id] }}”</span>
          </div>
          <div class="newbranch">
            <input v-model="newBranch[ar.id]" class="in sm" placeholder="new-branch-name" @keydown.enter="switchBranch(ar, newBranch[ar.id], true)" />
            <button class="btn" :disabled="busy === ar.id || !(newBranch[ar.id] || '').trim()" @click="switchBranch(ar, newBranch[ar.id], true)">
              Create &amp; switch
            </button>
          </div>
        </div>

        <div v-if="editing === ar.id" class="idedit">
          <input v-model="editName" class="in sm" placeholder="git user.name" />
          <input v-model="editEmail" class="in sm" placeholder="git user.email" />
          <button class="btn pri" :disabled="busy === ar.id" @click="saveEdit(ar)">Save</button>
          <button class="btn ghost" @click="editing = null">Cancel</button>
          <span class="idnote mono">Applies to future commits only.</span>
        </div>

        <div class="acts">
          <button class="btn" :disabled="busy === ar.id || !agentUp" @click="toggleChanges(ar)">
            {{ openChanges === ar.id ? 'Hide changes' : 'Changes' }}
          </button>
          <button class="btn" :disabled="busy === ar.id || !agentUp" @click="toggleHistory(ar)">
            {{ openHistory === ar.id ? 'Hide history' : 'History' }}
          </button>
          <button class="btn" :disabled="busy === ar.id || !agentUp" @click="act(ar, () => repoPull(ar.id), 'pull')">Pull</button>
          <!-- Commit/Push split button: adapts to repo state; dropdown exposes each sub-action -->
          <div class="splitwrap">
            <button
              class="btn split"
              :disabled="busy === ar.id || !agentUp || pushBlocked(ar)"
              :title="pushBlocked(ar) ? 'Nothing to commit or push' : primaryPushLabel(ar)"
              @click="primaryPush(ar)"
            >{{ primaryPushLabel(ar) }}</button>
            <button
              class="btn caret"
              :disabled="busy === ar.id || !agentUp"
              title="More push options"
              @click="pushMenu = pushMenu === ar.id ? '' : ar.id"
            >▾</button>
            <div v-if="pushMenu === ar.id" class="bmenu" @click.self="pushMenu = ''">
              <button class="bmi" :disabled="!canCommit(ar)" @click="startCommit(ar, false)">
                Commit only…<span class="mono">stage all + commit, no push</span>
              </button>
              <button class="bmi" :disabled="!canCommit(ar)" @click="startCommit(ar, true)">
                Commit &amp; Push…<span class="mono">stage all, commit, then push</span>
              </button>
              <button class="bmi" :disabled="!canPush(ar)" @click="pushOnly(ar)">
                Push only<span class="mono">{{ canPush(ar) ? `push ${ar.ahead} commit${ar.ahead > 1 ? 's' : ''}` : 'nothing to push' }}</span>
              </button>
              <button class="bmi danger" :disabled="!canPush(ar) && pushReject !== ar.id" @click="forcePush(ar)">
                Force push<span class="mono">--force-with-lease · rewrites remote</span>
              </button>
            </div>
          </div>
          <button class="btn" :disabled="busy === ar.id || !agentUp || ar.host === 'none'" @click="act(ar, () => repoPr(ar.id), 'open PR/MR')">
            {{ ar.host === 'gitlab' ? 'Open MR' : 'Open PR' }}
          </button>
          <button
            class="btn"
            :class="{ localon: ar.localOnly }"
            :disabled="busy === ar.id"
            :title="ar.localOnly ? 'Local-only: only local models can brainstorm this repo' : 'Allow remote models for this repo'"
            @click="toggleLocalOnly(ar)"
          >{{ ar.localOnly ? '🔒 Local-only' : '🔓 Any model' }}</button>
          <RepoBrainstormButton
            :repo="r"
            :models="repoModels(ar)"
            :open="bmenu === ar.id"
            :disabled="busy === ar.id || !agentUp"
            @toggle="bmenu = bmenu === ar.id ? '' : ar.id"
            @pick="(m) => brainstormHere(ar, m)"
          />
          <button class="btn ghost" :disabled="busy === ar.id" @click="remove(ar)">Remove</button>
        </div>

        <!-- inline commit-message popover for the split button -->
        <div v-if="commitPrompt?.id === ar.id" class="commitprompt">
          <input
            v-model="commitMsg[ar.id]"
            class="in mono"
            placeholder="Commit message…"
            :disabled="busy === ar.id"
            @keydown.enter="commitAndPush(ar, commitPrompt!.push)"
            @keydown.esc="commitPrompt = null"
          />
          <button class="btn pri" :disabled="busy === ar.id || !(commitMsg[ar.id] || '').trim()" @click="commitAndPush(ar, commitPrompt!.push)">
            {{ commitPrompt.push ? 'Commit & Push' : 'Commit' }}
          </button>
          <button class="btn ghost" :disabled="busy === ar.id" @click="commitPrompt = null">Cancel</button>
          <span class="cphint mono">stages all changes ({{ changeCount(ar) }}) then commits{{ commitPrompt.push ? ' and pushes' : '' }}</span>
        </div>

        <!-- operation-in-progress warning: an unsupported state we can only let the user back out of -->
        <div v-if="ar.operation" class="opwarn">
          <span class="owicon">⚠</span>
          <span class="owtext">
            A <b>{{ ar.operation }}</b> is in progress{{ workTree(ar).label.includes('conflict') ? ' with conflicts' : '' }}.
            DevLoom doesn't resolve conflicts here — finish it in your editor/terminal, or abort to restore the previous state.
          </span>
          <button class="btn danger" :disabled="busy === ar.id" @click="abortOp(ar)">Abort {{ ar.operation }}</button>
        </div>

        <!-- push rejected (non-fast-forward): the remote moved; offer a lease-protected force push -->
        <div v-else-if="pushReject === ar.id" class="opwarn">
          <span class="owicon">⚠</span>
          <span class="owtext">
            Push was rejected — the remote branch has commits you don't. Pull/rebase first, or force-push
            (<span class="mono">--force-with-lease</span>) if you intend to overwrite the remote branch.
          </span>
          <button class="btn" :disabled="busy === ar.id" @click="act(ar, () => repoPull(ar.id), 'pull')">Pull</button>
          <button class="btn danger" :disabled="busy === ar.id" @click="forcePush(ar)">Force push</button>
          <button class="btn ghost" :disabled="busy === ar.id" @click="pushReject = ''">Dismiss</button>
        </div>

        <div v-if="sessionsFor(ar.path).length" class="rsessions">
          <span class="rslab mono">brainstorms:</span>
          <button v-for="s in sessionsFor(ar.path)" :key="s.id" class="rschip" @click="openSession(s.id)">
            ✎ {{ s.title }}
          </button>
        </div>

        <!-- history + guarded squash (repo-spec §8–§9) -->
        <div v-if="openHistory === ar.id" class="histpanel">
          <div class="histhead">
            <span class="clab mono">History · ⎇ {{ ar.branch }}</span>
            <div class="histseg">
              <button class="seg mono" :class="{ on: histUnique }" @click="setUnique(ar, true)">unique to {{ history[ar.id]?.sourceRef || 'source' }}</button>
              <button class="seg mono" :class="{ on: !histUnique }" @click="setUnique(ar, false)">all</button>
            </div>
            <span class="grow"></span>
            <button v-if="histUnique && selCount >= 2" class="btn pri tiny2" @click="openSquash(ar)">
              Squash {{ selCount }} commits…
            </button>
          </div>
          <div v-if="histLoading" class="mono wtempty">loading…</div>
          <div v-else-if="!commitsOf(ar).length" class="mono wtempty">
            {{ histUnique ? 'no commits unique to the source branch' : 'no commits' }}
          </div>
          <template v-else>
            <div v-for="(c, i) in commitsOf(ar)" :key="c.hash" class="crow" :class="{ sel: i <= squashThrough }">
              <input
                v-if="histUnique"
                type="checkbox"
                class="csel"
                :checked="i <= squashThrough"
                :disabled="!rangeEligible(ar, i)"
                :title="rangeEligible(ar, i) ? 'Squash this commit and everything newer' : 'Range contains a merge commit'"
                @click="selectThrough(i)"
              />
              <button class="cmain" @click="toggleCommit(ar, c.hash)">
                <span class="csha mono">{{ c.short }}</span>
                <span class="csubj">{{ c.subject }}</span>
                <span v-if="c.merge" class="ctag mono">merge</span>
                <span class="ctag mono" :class="c.published ? 'pushed' : 'localc'">{{ c.published ? 'pushed' : 'local' }}</span>
                <span class="cwho mono">{{ c.author }} · {{ relTime(c.date) }}</span>
              </button>
              <div v-if="openCommit === c.hash" class="cdetail mono">
                <template v-if="commitDetail">
                  <div class="cdl"><b>{{ commitDetail.hash }}</b></div>
                  <div class="cdl">author {{ commitDetail.author }} &lt;{{ commitDetail.email }}&gt; · {{ commitDetail.date }}</div>
                  <div v-if="commitDetail.committer !== commitDetail.author" class="cdl">committer {{ commitDetail.committer }} · {{ commitDetail.commitDate }}</div>
                  <pre class="cmsg">{{ commitDetail.message }}</pre>
                  <div v-for="f in commitDetail.files" :key="f.file" class="cfile">
                    <span class="cfst">{{ f.status }}</span>{{ f.file }}
                  </div>
                </template>
                <div v-else class="cdl">loading…</div>
              </div>
            </div>
            <div v-if="histUnique && commitsOf(ar).length" class="histhint mono">
              tick a commit to squash it and everything newer into one (a backup ref is kept; nothing is pushed)
            </div>
          </template>
        </div>

        <!-- changes / staging / commit -->
        <div v-if="openChanges === ar.id" class="changes">
          <div class="cgroup">
            <div class="clab mono">
              Staged ({{ changes[ar.id]?.staged.length ?? 0 }})
              <button v-if="changes[ar.id]?.staged.length" class="mini" @click="unstage(ar, [])">unstage all</button>
            </div>
            <div v-for="f in changes[ar.id]?.staged ?? []" :key="'s' + f.file" class="crow">
              <span class="cstat mono staged">{{ f.status }}</span>
              <span class="cfile mono">{{ f.file }}</span>
              <button class="mini" @click="unstage(ar, [f.file])">unstage</button>
            </div>
          </div>
          <div class="cgroup">
            <div class="clab mono">
              Unstaged ({{ (changes[ar.id]?.unstaged.length ?? 0) + (changes[ar.id]?.untracked.length ?? 0) }})
              <button
                v-if="(changes[ar.id]?.unstaged.length ?? 0) + (changes[ar.id]?.untracked.length ?? 0)"
                class="mini"
                @click="stage(ar, [])"
              >stage all</button>
            </div>
            <div v-for="f in changes[ar.id]?.unstaged ?? []" :key="'u' + f.file" class="crow">
              <span class="cstat mono">{{ f.status }}</span>
              <span class="cfile mono">{{ f.file }}</span>
              <button class="mini" @click="stage(ar, [f.file])">stage</button>
            </div>
            <div v-for="f in changes[ar.id]?.untracked ?? []" :key="'n' + f.file" class="crow">
              <span class="cstat mono new">new</span>
              <span class="cfile mono">{{ f.file }}</span>
              <button class="mini" @click="stage(ar, [f.file])">stage</button>
            </div>
            <div v-if="!changes[ar.id]?.staged.length && !changes[ar.id]?.unstaged.length && !changes[ar.id]?.untracked.length" class="mono clean">working tree clean</div>
          </div>
          <div class="commitbar">
            <input v-model="commitMsg[ar.id]" class="in" placeholder="Commit message…" @keydown.enter="commit(ar)" />
            <button class="btn pri" :disabled="busy === ar.id || !stagedCount(ar.id) || !(commitMsg[ar.id] || '').trim()" @click="commit(ar)">
              Commit
            </button>
            <button class="btn" :disabled="busy === ar.id" @click="act(ar, () => repoPush(ar.id), 'push')">Push</button>
          </div>
        </div>
        </template>
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

    <!-- guarded squash confirmation (repo-spec §9.3) -->
    <div v-if="squashDlg" class="modal" @click.self="squashDlg = null">
      <div class="picker sqbox">
        <div class="pkhead mono">
          Squash {{ selCount }} commits on ⎇ {{ squashDlg.branch }}
          <span class="pksub">rewrites history — a backup ref is created first; nothing is pushed automatically</span>
        </div>
        <div class="sqbody">
          <div class="sqmeta mono">
            <span>commits as <b>{{ squashDlg.userName || '(unset)' }}</b> &lt;{{ squashDlg.userEmail || 'no email' }}&gt;</span>
          </div>
          <div v-if="selectedPublished(squashDlg) > 0" class="opwarn sqwarn">
            <span class="owicon">⚠</span>
            <span class="owtext">
              <b>{{ selectedPublished(squashDlg) }}</b> of the selected commits are already pushed. Squashing them
              makes your branch diverge from its remote — you'll need a (lease-protected) force push afterwards.
            </span>
          </div>
          <label class="sqlab mono">Resulting commit message</label>
          <textarea v-model="squashMsg" class="in mono sqmsg" rows="6"></textarea>
          <label v-if="selectedPublished(squashDlg) > 0" class="sqack mono">
            <input type="checkbox" v-model="squashAck" />
            I understand this rewrites published history
          </label>
        </div>
        <div class="pkfoot">
          <button class="btn ghost" :disabled="squashBusy" @click="squashDlg = null">Cancel</button>
          <span class="spacer"></span>
          <button
            class="btn pri"
            :disabled="squashBusy || !squashMsg.trim() || (selectedPublished(squashDlg) > 0 && !squashAck)"
            @click="doSquash(squashDlg)"
          >{{ squashBusy ? 'Squashing…' : 'Squash commits' }}</button>
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
/* Past the chip limit the panel trades pretty for findable: one column, scrolling, filterable. */
.branches.tall { flex-direction: column; align-items: stretch; max-height: 220px; overflow-y: auto; flex-wrap: nowrap; }
.branches.tall .brow { text-align: left; }
.brfilter { max-width: 260px; margin: 6px 0 2px; }
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
.hv.pullable { border: 1px solid transparent; border-radius: 5px; padding: 0 4px; }
.hchip.ok { color: var(--healthy); border-color: color-mix(in srgb, var(--healthy) 50%, var(--line)); }
.hchip.info { color: var(--warp-hi); border-color: var(--warp); }
.hchip.warn { color: var(--chip-fail, #d88); border-color: var(--failed, #a55); }
.hmore { font-size: 10px; color: var(--dim); background: transparent; border: 1px solid var(--line); border-radius: 5px; padding: 2px 7px; cursor: pointer; }
.hmore:hover { border-color: var(--warp); color: var(--ink); }
.hmore.wt { color: var(--warp-hi); }
/* group-worktrees toggle */
.actingon { font-size: 11px; font-weight: 600; color: var(--on-warp); background: var(--warp); border: 1px solid var(--warp); border-radius: 20px; padding: 2px 10px; cursor: pointer; }
.wtpick { font-size: 11px; min-width: 74px; text-align: left; color: var(--dim); background: transparent; border: 1px solid var(--line); border-radius: 5px; padding: 2px 8px; cursor: pointer; }
.wtpick[aria-pressed="true"] { color: var(--warp-hi); border-color: var(--warp); }
.wtrow.sel { background: var(--warp-weft); border-radius: 6px; }
.wtoggle { margin-left: 16px; font-size: 11px; color: var(--faint-text); background: transparent; border: 1px solid var(--line); border-radius: 6px; padding: 3px 9px; cursor: pointer; }
.wtoggle:hover { color: var(--ink); border-color: var(--warp); }
.wtoggle.on { color: var(--warp-hi); border-color: var(--warp); }
/* nested worktrees */
.wtbox { margin: 6px 0 2px; padding: 6px 10px; border: 1px solid var(--line); border-left: 2px solid var(--warp); border-radius: 8px; background: var(--bg); }
.wtrow { display: flex; align-items: center; gap: 10px; padding: 4px 0; font-size: 12px; }
.wtrow.untracked { opacity: 0.85; }
.wtbranch { color: var(--warp-hi); }
.wtrun { font-size: 9px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--on-warp); background: var(--warp); border-radius: 4px; padding: 1px 5px; }
.wtpath { margin-left: auto; color: var(--faint-text); font-size: 11px; }
.wttag { font-size: 9px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--faint-text); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; }
.wtempty { color: var(--faint-text); font-size: 11.5px; padding: 4px 0; }
.btn.tiny { font-size: 11px; padding: 2px 9px; }
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
.hrow .hedit { border: 1px solid var(--line); background: var(--panel, var(--bg)); color: var(--text); border-radius: 6px; padding: 2px 8px; font-size: 11px; cursor: pointer; }
.hrow .hedit:hover:not(:disabled) { border-color: var(--warp-hi); }
.hrow .hedit:disabled { opacity: 0.5; cursor: default; }
.hrow .hedit.ghost { color: var(--faint-text); }
.hrow.srcedit .srcin { flex: 1; min-width: 0; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; color: var(--text); padding: 3px 8px; font-size: 12px; }
.hrow.srcedit .srcin:focus { outline: none; border-color: var(--warp-hi); }
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
.btn.danger { border-color: var(--failed, #a55); color: var(--chip-fail, #d88); }
.btn.danger:hover { border-color: var(--chip-fail, #d88); background: color-mix(in srgb, var(--chip-fail, #d88) 12%, transparent); }
.bmi.danger { color: var(--chip-fail, #d88); }
.bmi:disabled { opacity: 0.45; cursor: not-allowed; }
.bmi:disabled:hover { background: transparent; }
/* commit-message popover + operation warnings */
.commitprompt { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 8px 0 0; }
.cphint { color: var(--faint-text); font-size: 11px; }
.opwarn { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 10px 0 0; padding: 8px 12px; border: 1px solid var(--failed, #a55); border-radius: 8px; background: color-mix(in srgb, var(--chip-fail, #d88) 8%, var(--bg)); }
.opwarn .owicon { color: var(--chip-fail, #d88); font-size: 14px; }
.opwarn .owtext { flex: 1; min-width: 240px; font-size: 12px; color: var(--ink); }
.opwarn .owtext b { color: var(--chip-fail, #d88); text-transform: capitalize; }
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
.pkhead .pksub { font-size: 11px; color: var(--faint-text); font-weight: normal; }

/* history + squash */
.histpanel { margin-top: 12px; border-top: 1px solid var(--line); padding-top: 12px; }
.histhead { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.histhead .grow { flex: 1; }
.histseg { display: inline-flex; gap: 2px; padding: 2px; border: 1px solid var(--line); border-radius: 7px; }
.histseg .seg { font-size: 10px; padding: 2px 9px; border: 0; border-radius: 5px; background: transparent; color: var(--faint-text); cursor: pointer; }
.histseg .seg.on { background: var(--warp-weft); color: var(--warp-hi); }
.btn.tiny2 { font-size: 12px; padding: 3px 11px; }
.crow { border: 1px solid transparent; border-radius: 7px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 2px 6px; }
.crow.sel { background: var(--warp-weft); border-color: var(--warp); }
.csel { accent-color: var(--warp); }
.cmain { flex: 1; display: flex; align-items: center; gap: 10px; background: transparent; border: 0; padding: 4px 2px; cursor: pointer; color: inherit; font: inherit; text-align: left; min-width: 0; }
.cmain:hover .csubj { color: var(--warp-hi); }
.csha { font-size: 11px; color: var(--warp-hi); flex: 0 0 auto; }
.csubj { font-size: 13px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.ctag { font-size: 9px; text-transform: uppercase; letter-spacing: 0.07em; border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; color: var(--dim); flex: 0 0 auto; }
.ctag.pushed { color: var(--healthy); }
.ctag.localc { color: var(--warp-hi); border-color: var(--warp); }
.cwho { font-size: 11px; color: var(--faint-text); flex: 0 0 auto; }
.cdetail { flex-basis: 100%; margin: 2px 0 6px 26px; border: 1px solid var(--line); border-radius: 7px; background: var(--bg); padding: 8px 12px; font-size: 11.5px; color: var(--dim); }
.cdl { padding: 1px 0; }
.cmsg { white-space: pre-wrap; color: var(--ink); margin: 6px 0; font-size: 12px; }
.cfst { color: var(--warp-hi); display: inline-block; min-width: 20px; }
.histhint { margin-top: 8px; font-size: 11px; color: var(--faint-text); }
/* squash dialog */
.sqbox { width: 620px; }
.sqbody { padding: 12px 14px; overflow: auto; }
.sqmeta { font-size: 12px; color: var(--dim); margin-bottom: 10px; }
.sqwarn { margin: 0 0 10px; }
.sqlab { display: block; font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--faint-text); margin-bottom: 6px; }
.sqmsg { width: 100%; box-sizing: border-box; resize: vertical; font-size: 12.5px; }
.sqack { display: flex; align-items: center; gap: 8px; margin-top: 10px; font-size: 12px; color: var(--warp-hi); }
</style>
