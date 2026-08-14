<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useDashboardStore } from '../stores/dashboard'
import { fleetRuns, launchRun, cancelRun, fetchRepos, fleetRunChanges, rerunRun, deleteRun, createBrainstormSession, fetchSettings, applyRun, discardRun } from '../api'
import type { AgentRun, RepoView, RepoChanges, RunLaunch } from '../types'
import { renderMarkdown } from '../utils/markdown'

const router = useRouter()
const store = useDashboardStore()
const { models } = storeToRefs(store)
// Claude models run through the Claude Code CLI — the agentic path that can edit files.
const claudeModels: { value: string | undefined; label: string }[] = [
  { value: undefined, label: 'Claude (default)' },
  { value: 'sonnet', label: 'Claude Sonnet' },
  { value: 'opus', label: 'Claude Opus' },
  { value: 'haiku', label: 'Claude Haiku' },
]
// Everything else (local Ollama, keyed API models) runs DevLoom's own tool loop: it can read a
// repo, and on an edit run write to it inside a worktree. claude-cli/claude-code are terminal-only.
const otherModels = computed(() =>
  models.value.filter((m) => !m.startsWith('claude-cli') && !m.startsWith('claude-code')),
)
function isCliModel(m: string | undefined) {
  if (!m) return true
  const l = m.toLowerCase()
  return l.startsWith('claude') || l === 'sonnet' || l === 'opus' || l === 'haiku'
}
// Not "can't edit" — measured "usually won't". eval/features.mjs grades written files rather than
// prose: qwen2.5-coder:7b produced them on the simpler tiers, qwen3-coder:30b described the change
// every time and wrote nothing, even when told explicitly with the write tool in front of it. That
// is the afternoon this warning exists to save, and it belongs here, not only in Settings.
const localEdit = computed(() => !isCliModel(form.value.model) && form.value.permission === 'edit')

const runs = ref<AgentRun[]>([])
const repos = ref<RepoView[]>([])
const loading = ref(true)
const flash = ref('')
let timer: number | undefined

// Attention-routed groups. 'input' = a detached terminal that went quiet — it may be waiting on you.
const needsReview = computed(() => runs.value.filter((r) => ['review', 'failed', 'input'].includes(r.status)))
const running = computed(() => runs.value.filter((r) => r.status === 'running'))
const active = computed(() => runs.value.filter((r) => r.status === 'active'))
const recent = computed(() => runs.value.filter((r) => ['done', 'canceled', 'ended'].includes(r.status)))

async function refresh() {
  try { runs.value = await fleetRuns() } catch { /* keep last */ }
}
const worktreesDefault = ref(true)
onMounted(async () => {
  store.ensureLoaded() // populate the model list for the launch dialog
  await refresh()
  try { repos.value = (await fetchRepos()).repos } catch { /* agent offline */ }
  try { worktreesDefault.value = (await fetchSettings()).fleetWorktreesDefault } catch { /* default true */ }
  loading.value = false
  timer = window.setInterval(refresh, 4000)
})
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })

function repoName(path: string): string {
  const p = (path || '').replace(/\\/g, '/')
  return p.substring(p.lastIndexOf('/') + 1)
}
function elapsed(r: AgentRun): string {
  const start = r.startedAt ? new Date(r.startedAt).getTime() : new Date(r.createdAt).getTime()
  const end = r.finishedAt ? new Date(r.finishedAt).getTime() : Date.now()
  const s = Math.max(0, Math.round((end - start) / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`
}
const statusLabel: Record<string, string> = {
  running: 'running', review: 'needs review', done: 'done', failed: 'failed',
  canceled: 'canceled', active: 'active', ended: 'ended', input: 'may need input',
}

// ---- launch dialog ----
const dlg = ref(false)
const busy = ref(false)
const form = ref<RunLaunch>({ repoId: '', prompt: '', model: undefined, permission: 'readonly', allowTests: false, isolate: false })
// A local edit run is only allowed in a worktree (the backend rejects it outright otherwise), so
// choosing one turns the other on rather than letting the dialog offer a launch that would 400.
watch(() => form.value.model, (m) => {
  if (!isCliModel(m) && form.value.permission === 'edit') form.value.isolate = true
})
watch(() => form.value.permission, (p) => {
  if (p === 'edit' && !isCliModel(form.value.model)) form.value.isolate = true
})
function openLaunch() {
  form.value = { repoId: repos.value[0]?.id ?? '', prompt: '', model: undefined, permission: 'readonly', allowTests: false, isolate: worktreesDefault.value }
  flash.value = ''
  dlg.value = true
}
async function submit() {
  if (busy.value || !form.value.repoId || !form.value.prompt.trim()) return
  busy.value = true; flash.value = ''
  try {
    const run = await launchRun({ ...form.value, model: form.value.model || undefined })
    dlg.value = false
    runs.value = [run, ...runs.value]
  } catch {
    flash.value = form.value.permission === 'edit'
      ? 'Launch failed — an edit run needs a clean working tree (commit or stash first), or turn isolation on.'
      : 'Launch failed — is the host agent running?'
  } finally { busy.value = false }
}
async function stop(r: AgentRun) {
  try { const u = await cancelRun(r.id); runs.value = runs.value.map((x) => (x.id === u.id ? u : x)) } catch { /* ignore */ }
}

// ---- run detail ----
const detail = ref<AgentRun | null>(null)
const detailChanges = ref<RepoChanges | null>(null)
const detailBusy = ref(false)
async function openDetail(r: AgentRun) {
  detail.value = r
  detailChanges.value = null
  if (r.permission === 'edit') {
    try { detailChanges.value = await fleetRunChanges(r.id) } catch { detailChanges.value = null }
  }
}
const changedFiles = computed(() => {
  const c = detailChanges.value
  if (!c) return [] as { file: string; status: string }[]
  return [...c.staged, ...c.unstaged, ...c.untracked]
})
async function rerun(r: AgentRun) {
  detailBusy.value = true
  try { const run = await rerunRun(r.id); runs.value = [run, ...runs.value]; detail.value = null }
  catch { flash.value = 'Re-run failed — is the host agent running?' }
  finally { detailBusy.value = false }
}
// Finished runs accumulate — a day of work leaves a wall of them, each needing an individual
// dismiss. Only ever clears what's already in Recent, so nothing live can be swept up by it.
const clearing = ref(false)
async function dismissRecent() {
  if (clearing.value) return
  clearing.value = true
  const ids = recent.value.map((r) => r.id)
  try {
    for (const id of ids) await deleteRun(id).catch(() => {})
    runs.value = runs.value.filter((r) => !ids.includes(r.id))
    if (detail.value && ids.includes(detail.value.id)) detail.value = null
  } finally { clearing.value = false }
}

async function dismiss(r: AgentRun) {
  detailBusy.value = true
  try { await deleteRun(r.id); runs.value = runs.value.filter((x) => x.id !== r.id); if (detail.value?.id === r.id) detail.value = null }
  finally { detailBusy.value = false }
}
// Apply an isolated run: 'branch' keeps devloom/run-<id>; 'patch' lands the diff on your current
// branch (on conflict the run stays reviewable with the error shown).
async function applyIsolated(r: AgentRun, mode: 'branch' | 'patch' = 'branch') {
  detailBusy.value = true
  try { const u = await applyRun(r.id, mode); runs.value = runs.value.map((x) => (x.id === u.id ? u : x)); detail.value = u }
  catch { flash.value = 'Apply failed — is the host agent running?' }
  finally { detailBusy.value = false }
}
async function discardIsolated(r: AgentRun) {
  detailBusy.value = true
  try { const u = await discardRun(r.id); runs.value = runs.value.map((x) => (x.id === u.id ? u : x)); detail.value = u }
  catch { flash.value = 'Discard failed — is the host agent running?' }
  finally { detailBusy.value = false }
}
// "Did the task" is a verdict, not a measurement — show it as one.
function adherenceLabel(v: number): string {
  return v >= 1 ? 'yes' : v > 0 ? 'partly' : 'no'
}
// Bands, not a gradient: a run either did the job cleanly, wobbled, or went wrong.
function qualityClass(score: number): string {
  return score >= 0.9 ? 'good' : score >= 0.6 ? 'ok' : 'bad'
}
const isolatedReview = computed(() => !!detail.value && detail.value.isolated && detail.value.status === 'review')
// Reopen a run where its work actually lives. Anything born in Brainstorm — a cli terminal or a
// local-model chat alike — goes back to that session; keying off the session id rather than the
// kind is what stops a chat run being answered with a brand-new terminal it has no history in.
// Only a background run, which has no session, opens a fresh cli session to continue the work.
async function openInTerminal(r: AgentRun) {
  if (r.brainstormSessionId) {
    router.push({ path: '/brainstorm', query: { session: r.brainstormSessionId } })
    return
  }
  // A chat run whose session is gone has nothing to reopen and nothing a terminal could continue;
  // spawning an empty one just looks like the click did something. Show the run instead.
  if (r.kind === 'chat') { openDetail(r); return }
  await continueWith(r, 'claude-cli')
}

// Continue a run. A run that came out of a conversation goes back INTO that conversation — forking
// a fresh session and pasting "prior result: …" into it threw away the exchange you were mid-way
// through and made the model start over from a summary of itself. Only a run with no session of its
// own (a background analysis) needs a new one.
async function continueWith(r: AgentRun, model: string) {
  if (r.brainstormSessionId) {
    router.push({ path: '/brainstorm', query: { session: r.brainstormSessionId } })
    return
  }
  detailBusy.value = true
  try {
    const s = await createBrainstormSession(`Continue · ${r.title}`, r.runDir || r.repoPath, model)
    if (r.resultSummary) {
      store.setPendingSeed(s.id, `Continue this task:\n\n${r.title}\n\nPrior result:\n${r.resultSummary}`,
                           model === 'claude-cli' ? 'cli' : 'chat')
    }
    router.push({ path: '/brainstorm', query: { session: s.id } })
  } finally { detailBusy.value = false }
}

// A run's own model is the natural way to continue it — offered whenever that isn't the cli.
const continueModel = computed(() => {
  const m = detail.value?.model
  return m && m !== 'claude-cli' && m !== 'claude-code' ? m : null
})
</script>

<template>
  <main class="main">
    <div class="head">
      <h1>Fleet</h1>
      <span v-if="needsReview.length" class="attn mono">{{ needsReview.length }} need review</span>
      <span class="grow"></span>
      <button class="btn pri" @click="openLaunch">＋ New run</button>
    </div>
    <p class="sub">Fan out background AI runs and check back. Each runs headless in its repo; read-only proposes, edit changes files (never pushes).</p>

    <div v-if="flash" class="flash mono">{{ flash }}</div>
    <div v-if="loading" class="mono empty">loading…</div>

    <template v-else>
      <section v-if="needsReview.length" class="grp">
        <div class="glab mono">Needs {{ needsReview.some((r) => r.status === 'input') ? 'you' : 'review' }}</div>
        <button v-for="r in needsReview" :key="r.id" class="run" :class="r.status"
                @click="r.status === 'input' ? openInTerminal(r) : openDetail(r)">
          <span class="rstatus mono" :class="r.status">{{ statusLabel[r.status] }}</span>
          <span class="rtitle">{{ r.title }}</span>
          <span class="rbadges mono">
            <span v-if="r.kind !== 'background'" class="rb kindb">{{ r.kind === 'interactive' ? '⌨' : '✎' }} {{ r.kind }}</span>
            <span class="rb">{{ repoName(r.repoPath) }}</span>
            <span v-if="r.permission" class="rb" :class="r.permission">{{ r.permission }}</span>
            <span v-if="r.model" class="rb">{{ r.model }}</span>
            <span class="rb">{{ elapsed(r) }}</span>
          </span>
          <span class="chev">{{ r.status === 'input' ? 'open ›' : '›' }}</span>
        </button>
      </section>

      <section v-if="running.length" class="grp">
        <div class="glab mono">Running</div>
        <!-- A run tied to a brainstorm session is a live conversation: clicking it goes back there. -->
        <component :is="r.brainstormSessionId ? 'button' : 'div'" v-for="r in running" :key="r.id"
                   class="run running" @click="r.brainstormSessionId ? openInTerminal(r) : undefined">
          <span class="rstatus mono running"><span class="spin"></span>running</span>
          <span class="rtitle">{{ r.title }}</span>
          <span class="rbadges mono">
            <span v-if="r.kind !== 'background'" class="rb kindb">{{ r.kind === 'interactive' ? '⌨' : '✎' }} {{ r.kind }}</span>
            <span class="rb">{{ repoName(r.repoPath) }}</span>
            <span v-if="r.permission" class="rb" :class="r.permission">{{ r.permission }}</span>
            <span v-if="r.model" class="rb">{{ r.model }}</span>
            <span class="rb">{{ elapsed(r) }}</span>
          </span>
          <button v-if="r.kind === 'background'" class="link mono danger" @click.stop="stop(r)">cancel</button>
          <span v-else-if="r.brainstormSessionId" class="chev">open ›</span>
        </component>
      </section>

      <section v-if="active.length" class="grp">
        <div class="glab mono">Active · interactive</div>
        <button v-for="r in active" :key="r.id" class="run active" @click="openInTerminal(r)">
          <span class="rstatus mono active">⌨ terminal</span>
          <span class="rtitle">{{ r.title }}</span>
          <span class="rbadges mono"><span class="rb">{{ repoName(r.repoPath) }}</span></span>
          <span class="chev">open ›</span>
        </button>
      </section>

      <section v-if="recent.length" class="grp">
        <div class="glab mono">
          Recent
          <button class="link mono clearall" :disabled="clearing" @click="dismissRecent">
            {{ clearing ? 'clearing…' : `dismiss all (${recent.length})` }}
          </button>
        </div>
        <button v-for="r in recent" :key="r.id" class="run recent" @click="openDetail(r)">
          <span class="rstatus mono" :class="r.status">{{ statusLabel[r.status] }}</span>
          <span class="rtitle">{{ r.title }}</span>
          <span class="rbadges mono">
            <span class="rb">{{ repoName(r.repoPath) }}</span>
            <span v-if="r.model" class="rb">{{ r.model }}</span>
            <span class="rb">{{ elapsed(r) }}</span>
          </span>
          <span class="chev">›</span>
        </button>
      </section>

      <div v-if="!runs.length" class="mono empty">No runs yet — launch one to fan out background work.</div>
    </template>

    <!-- run detail -->
    <div v-if="detail" class="over" @click.self="detail = null">
      <div class="box detail">
        <div class="dh">
          <span class="rstatus mono" :class="detail.status">{{ statusLabel[detail.status] }}</span>
          <span class="dtitle">{{ detail.title }}</span>
          <button class="x" @click="detail = null">✕</button>
        </div>
        <div class="dmeta mono">
          <span class="rb">{{ repoName(detail.repoPath) }}</span>
          <span v-if="detail.branch" class="rb">⎇ {{ detail.branch }}</span>
          <span v-if="detail.permission" class="rb" :class="detail.permission">{{ detail.permission }}</span>
          <span v-if="detail.model" class="rb">{{ detail.model }}</span>
          <span class="rb">{{ elapsed(detail) }}</span>
        </div>

        <div v-if="detail.permission === 'edit' && changedFiles.length" class="dsec">
          <div class="dlab mono">Changed files ({{ changedFiles.length }})</div>
          <div class="difflist mono">
            <div v-for="f in changedFiles" :key="f.file" class="diffrow"><span class="dfstat">{{ f.status }}</span>{{ f.file }}</div>
          </div>
        </div>
        <div v-else-if="detail.permission === 'edit'" class="dsec mono muted">No file changes were produced.</div>

        <!-- How it went about the work, distinct from whether the answer is right — which only you
             can judge. A clean run is unremarkable, so only the imperfect ones say why. -->
        <div v-if="detail.adherenceScore !== null && detail.adherenceScore !== undefined" class="quality mono">
          <span class="qval" :class="qualityClass(detail.adherenceScore)">{{ adherenceLabel(detail.adherenceScore) }}</span>
          <span class="qlab">did the task</span>
          <span v-if="detail.adherenceNote" class="qpen">{{ detail.adherenceNote }}</span>
          <span v-if="detail.grounded === false" class="qsep">·</span>
          <span v-if="detail.grounded === false" class="qpen">not grounded in what it read</span>
          <span v-if="detail.retried" class="qsep">·</span>
          <!-- A rescued run and a first-time-right run are not the same result; say which. -->
          <span v-if="detail.retried" class="qpen">second attempt — the first missed the task</span>
        </div>
        <div v-if="detail.qualityScore !== null && detail.qualityScore !== undefined" class="quality mono">
          <span class="qval" :class="qualityClass(detail.qualityScore)">{{ detail.qualityScore.toFixed(2) }}</span>
          <span class="qlab" title="How it went about the work — repeats, invented tools, step budget. Not whether the answer is right.">how it worked</span>
          <span v-if="detail.toolCalls" class="qsep">·</span>
          <span v-if="detail.toolCalls">{{ detail.toolCalls }} tool call{{ detail.toolCalls === 1 ? '' : 's' }}</span>
          <span v-if="detail.qualityNotes && detail.qualityNotes !== 'clean'" class="qsep">·</span>
          <span v-if="detail.qualityNotes && detail.qualityNotes !== 'clean'" class="qpen">{{ detail.qualityNotes }}</span>
        </div>

        <div class="dsec">
          <div class="dlab mono">{{ detail.error ? 'Error' : 'Result' }}</div>
          <!-- Errors stay verbatim (stack traces and JSON must not be reflowed); a model's answer
               is markdown, so render it. -->
          <pre v-if="detail.error" class="rbody mono">{{ detail.error }}</pre>
          <div v-else-if="detail.resultSummary" class="rbody md" v-html="renderMarkdown(detail.resultSummary)"></div>
          <pre v-else class="rbody mono">(no output)</pre>
        </div>

        <p v-if="detail.isolated && detail.branch" class="wtnote mono">⑂ isolated on <b>{{ detail.branch }}</b> — apply keeps that branch, patch lands it on your current branch, discard removes it.</p>
        <div class="df">
          <button v-if="detail.status === 'running' && detail.kind === 'background'" class="btn danger" @click="stop(detail)">Cancel</button>
          <button v-if="isolatedReview" class="btn pri" :disabled="detailBusy" @click="applyIsolated(detail, 'branch')">Apply → branch</button>
          <button v-if="isolatedReview" class="btn" :disabled="detailBusy" @click="applyIsolated(detail, 'patch')">Apply as patch</button>
          <button v-if="isolatedReview" class="btn danger" :disabled="detailBusy" @click="discardIsolated(detail)">Discard</button>
          <!-- Continuing on the run's own model comes first: reaching for the Claude terminal to
               follow up on a local run is the opposite of local-first. -->
          <button v-if="continueModel" class="btn" :disabled="detailBusy"
                  @click="continueWith(detail, continueModel)">Continue with {{ continueModel }} ▸</button>
          <button v-if="detail.kind !== 'chat'" class="btn" :disabled="detailBusy" @click="openInTerminal(detail)">Open in terminal ▸</button>
          <button v-if="detail.kind === 'background'" class="btn" :disabled="detailBusy" @click="rerun(detail)">Re-run</button>
          <span class="grow"></span>
          <button class="btn ghost" :disabled="detailBusy" @click="dismiss(detail)">Dismiss</button>
        </div>
      </div>
    </div>

    <!-- launch dialog -->
    <div v-if="dlg" class="over" @click.self="dlg = false">
      <div class="box">
        <div class="bh mono">New background run</div>
        <label class="fld"><span class="flab mono">Repository</span>
          <select v-model="form.repoId" class="in mono">
            <option v-for="r in repos" :key="r.id" :value="r.id">{{ r.name }} · {{ r.branch }}</option>
          </select>
        </label>
        <label class="fld col"><span class="flab mono">Task</span>
          <textarea v-model="form.prompt" class="in mono ta" rows="4" placeholder="e.g. Add input validation to the config loader and a test for it"></textarea>
        </label>
        <label class="fld"><span class="flab mono">Model</span>
          <select v-model="form.model" class="in mono">
            <optgroup label="Claude Code — can edit files">
              <option v-for="m in claudeModels" :key="m.label" :value="m.value">{{ m.label }}</option>
            </optgroup>
            <optgroup v-if="otherModels.length" label="Analysis only — reads and reports">
              <option v-for="m in otherModels" :key="m" :value="m">{{ m }}</option>
            </optgroup>
          </select>
        </label>
        <p class="hint2 mono">
          {{ isCliModel(form.model)
            ? 'Runs headless Claude Code in the repo — can edit files, never pushes.'
            : 'Runs on your machine through DevLoom’s tool loop: it reads the repo, and on an edit run writes to it in a worktree. Nothing leaves. It runs in the background — you can navigate away.' }}
        </p>
        <div class="fld"><span class="flab mono">Permission</span>
          <div class="perm">
            <label class="pr"><input type="radio" value="readonly" v-model="form.permission" /> Read-only<span class="mono">proposes a plan; writes nothing</span></label>
            <label class="pr">
              <input type="radio" value="edit" v-model="form.permission" />
              Edit in repo<span class="mono">changes files; never pushes/deploys</span>
            </label>
          </div>
        </div>
        <label v-if="form.permission === 'edit'" class="fld ck"><input type="checkbox" v-model="form.allowTests" /> <span>Let it run tests</span></label>
        <label v-if="form.permission === 'edit'" class="fld ck"><input type="checkbox" v-model="form.isolate" :disabled="localEdit" /> <span>Isolate in a worktree<span class="mono hint"> — its own branch; many can run at once{{ localEdit ? '; required for a local model' : '' }}</span></span></label>
        <p v-if="form.permission === 'edit' && !form.isolate" class="warn mono">Without isolation the run edits your main checkout, which must be clean.</p>
        <p v-if="localEdit" class="warn mono">
          Local models are unreliable at <b>writing</b> code — they often describe the change
          instead of making it, and qwen3-coder wrote no files at all when measured. The run gets
          its own branch, so a bad one is discarded rather than landed, but expect to check it.
        </p>
        <div class="bf">
          <button class="btn ghost" @click="dlg = false">Cancel</button>
          <button class="btn pri" :disabled="busy || !form.repoId || !form.prompt.trim()" @click="submit">{{ busy ? 'Launching…' : 'Launch run ▸' }}</button>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.main { padding: 22px 26px; overflow: auto; }
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.head h1 { font-size: 26px; }
.grow { flex: 1; }
.attn { font-size: 12px; color: var(--on-warp); background: var(--warp); border-radius: 6px; padding: 2px 9px; }
.sub { color: var(--dim); font-size: 13px; margin: 0 0 16px; max-width: 76ch; }
.empty { color: var(--faint-text); padding: 20px 0; }
.flash { color: var(--warp-hi); margin-bottom: 10px; font-size: 12px; }
.grp { margin-bottom: 18px; }
.glab { font-size: 10px; letter-spacing: 0.12em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 8px; }
.clearall { float: right; font-size: 10px; letter-spacing: 0.08em; text-transform: none; }
.run { display: flex; align-items: center; gap: 12px; width: 100%; text-align: left; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); padding: 10px 14px; margin-bottom: 8px; cursor: pointer; color: inherit; font: inherit; }
button.run:hover { border-color: var(--warp); }
.run.review { border-left: 2px solid var(--warp); }
.run.failed { border-left: 2px solid var(--failed, #a55); }
.run.running { border-left: 2px solid var(--warp-hi); cursor: default; }
.run.active { border-left: 2px solid var(--warp-hi); }
.chev { color: var(--faint-text); font-size: 13px; }
.rtitle { flex: 1; font-size: 14px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rstatus { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; padding: 2px 7px; border-radius: 5px; border: 1px solid var(--line); color: var(--dim); display: inline-flex; align-items: center; gap: 5px; }
.rstatus.review { color: var(--warp-hi); border-color: var(--warp); }
.rstatus.failed { color: var(--chip-fail, #d88); border-color: var(--failed, #a55); }
.rstatus.running { color: var(--warp-hi); border-color: var(--warp); }
.rstatus.done { color: var(--healthy); }
.rstatus.active { color: var(--warp-hi); border-color: var(--warp); }
.rstatus.input { color: var(--on-warp); background: var(--warp); border-color: var(--warp); }
.run.input { border-left: 2px solid var(--warp); }
.rb.kindb { color: var(--warp-hi); }
.rbadges { display: flex; gap: 6px; }
.rb { font-size: 10px; color: var(--faint-text); border: 1px solid var(--line); border-radius: 5px; padding: 1px 6px; }
.rb.edit { color: var(--warp-hi); border-color: var(--warp); }
.rb.readonly { color: var(--healthy); }
.link { background: transparent; border: 0; color: var(--dim); font-size: 12px; cursor: pointer; }
.link:hover { color: var(--ink); }
.link.danger { color: var(--chip-fail, #d88); }
.rbody { margin: 10px 0 0; padding: 10px 12px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; font-size: 12px; color: var(--dim); white-space: pre-wrap; max-height: 320px; overflow: auto; }
/* a rendered answer supplies its own block spacing — pre-wrap would double every gap */
.rbody.md { white-space: normal; }
.md :deep(.md-p) { margin: 0 0 8px; } .md :deep(.md-p:last-child) { margin-bottom: 0; }
.md :deep(.md-h) { font-weight: 600; color: var(--ink); margin: 10px 0 5px; }
.md :deep(.md-ul), .md :deep(.md-ol) { margin: 4px 0 8px; padding-left: 20px; }
.md :deep(li) { margin: 2px 0; }
.md :deep(strong) { color: var(--ink); font-weight: 600; }
.md :deep(.md-code) { font-family: var(--mono); font-size: 11.5px; background: var(--chip-bg); border: 1px solid var(--line); border-radius: 4px; padding: 1px 5px; }
.md :deep(.md-pre) { background: var(--surface); border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; overflow: auto; margin: 6px 0; }
.md :deep(.md-pre code) { font-family: var(--mono); font-size: 11.5px; white-space: pre; background: none; border: 0; padding: 0; }
.md :deep(a) { color: var(--warp-hi); text-decoration: underline; }
.md :deep(hr) { border: 0; border-top: 1px solid var(--line); margin: 10px 0; }
.md :deep(.md-tablewrap) { overflow-x: auto; margin: 8px 0; }
.md :deep(.md-table) { border-collapse: collapse; font-size: 12px; }
.md :deep(.md-table th), .md :deep(.md-table td) { border: 1px solid var(--line); padding: 5px 9px; text-align: left; vertical-align: top; }
.md :deep(.md-table th) { background: var(--chip-bg); color: var(--ink); font-weight: 600; }
.quality { display: flex; align-items: center; gap: 8px; margin: 10px 0 0; font-size: 11px; color: var(--dim); }
.qval { font-size: 13px; font-weight: 600; }
.qval.good { color: var(--ok, #6ea87f); }
.qval.ok { color: var(--warp-hi); }
.qval.bad { color: var(--danger, #c96a5b); }
.qlab { letter-spacing: 0.08em; text-transform: uppercase; color: var(--faint-text); }
.qsep { color: var(--faint-text); }
.qpen { color: var(--warp-hi); }
.spin { width: 8px; height: 8px; border-radius: 50%; border: 2px solid var(--warp); border-top-color: transparent; display: inline-block; animation: sp 0.7s linear infinite; }
@keyframes sp { to { transform: rotate(360deg); } }
/* dialog */
.over { position: fixed; inset: 0; z-index: 60; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; }
.box { width: min(560px, 92vw); background: var(--surface); border: 1px solid var(--line); border-radius: 12px; padding: 16px; }
.bh { font-size: 12px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--warp-hi); margin-bottom: 12px; }
.fld { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.fld.col { flex-direction: column; align-items: stretch; gap: 6px; }
.fld.ck { gap: 8px; }
.flab { font-size: 12px; color: var(--dim); min-width: 96px; }
.in { flex: 1; background: var(--bg); border: 1px solid var(--line); border-radius: 6px; color: var(--ink); padding: 7px 10px; font-size: 13px; }
.ta { resize: vertical; font-family: var(--mono); }
.perm { display: flex; flex-direction: column; gap: 6px; }
.pr { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink); }
.pr .mono { font-size: 11px; color: var(--faint-text); }
.pr.off { opacity: 0.55; }
.warn { font-size: 11px; color: var(--warp-hi); margin: 0 0 8px; }
.hint { color: var(--faint-text); }
.hint2 { font-size: 11px; color: var(--faint-text); margin: -6px 0 10px; }
.wtnote { font-size: 11.5px; color: var(--warp-hi); margin: 0 0 8px; }
.wtnote b { color: var(--ink); }
.bf { display: flex; justify-content: flex-end; gap: 8px; margin-top: 6px; }
.btn { font-size: 13px; font-weight: 500; border-radius: var(--r-ctl); padding: 7px 13px; border: 1px solid var(--line); background: var(--btn-bg); color: var(--ink); cursor: pointer; }
.btn.pri { background: var(--warp); border-color: var(--warp); color: var(--on-warp); font-weight: 600; }
.btn.ghost { background: transparent; color: var(--dim); border-color: transparent; }
.btn.danger { border-color: var(--failed, #a55); color: var(--chip-fail, #d88); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
/* run detail */
/* Grows with the window: a review dialog holds a model's whole answer plus up to six actions, and
   at 680px the actions wrapped into a ragged block. Capped so it stays readable on a wide monitor. */
.box.detail { width: min(1040px, 94vw); max-height: 86vh; display: flex; flex-direction: column; }
.dh { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.dtitle { flex: 1; font-size: 15px; color: var(--ink); }
.x { background: transparent; border: 0; color: var(--faint-text); font-size: 15px; cursor: pointer; }
.x:hover { color: var(--ink); }
.dmeta { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
.dsec { margin-bottom: 12px; overflow: auto; }
.dsec.muted { color: var(--faint-text); font-size: 12px; }
.dlab { font-size: 10px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--faint-text); margin-bottom: 6px; }
.difflist { border: 1px solid var(--line); border-radius: 8px; background: var(--bg); }
.diffrow { display: flex; gap: 10px; padding: 4px 10px; font-size: 12px; color: var(--dim); border-bottom: 1px solid var(--line); }
.diffrow:last-child { border-bottom: 0; }
.dfstat { color: var(--warp-hi); min-width: 22px; }
/* Wraps deliberately rather than squeezing: on a narrow window the actions stack in rows instead
   of each button shrinking to fit its own label. */
.df { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; }
.df .btn { white-space: nowrap; }
.df .grow { flex: 1; }
</style>
